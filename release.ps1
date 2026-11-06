<#
.SYNOPSIS
  Builds a new version of Live News and publishes it as a GitHub release. The app on the phone
  looks at the newest release, so publishing here is what makes phones offer the update.

.DESCRIPTION
  1. Raises versionCode by one and sets versionName in version.properties.
  2. Runs the tests and builds the signed APK.
  3. Checks the APK is signed with this app's key (Android refuses an update signed with another).
  4. Creates the GitHub release vX.Y.Z with two files: LiveNews.apk and version.json.

  If the build fails, version.properties is put back as it was and nothing is published.

.PARAMETER VersionName
  The version people see, for example 1.2.0.

.PARAMETER Notes
  One or two sentences on what changed. Shown in the app's update banner.

.PARAMETER NoBump
  Publish the versionCode already in version.properties (used for the very first release).

.PARAMETER BuildOnly
  Build and check everything, but publish nothing.

.EXAMPLE
  .\release.ps1 -VersionName 1.2.0 -Notes "Sharper widget and two new sources."
#>
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = "Bug fixes and improvements.",
    [switch]$NoBump,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$repo = 'thedickestrick/live-news'
# SHA-256 of the certificate in livenews.keystore, the key every installed copy was signed with.
$appCertificate = '218a01a3fec2fdbc528df0e3196a149f86c252bd3f11b4c529cb1dcac92f2ed1'

if ($VersionName -notmatch '^\d+(\.\d+){1,3}$') { throw "VersionName should look like 1.2.0, not '$VersionName'." }
if (-not (Test-Path (Join-Path $root 'livenews.keystore'))) {
    throw "livenews.keystore is missing from $root. Without that exact key, phones will refuse the update. Restore it from a backup of this folder."
}

# Native tools (gradlew, gh, apksigner) write progress to stderr; that must not stop the script.
function Invoke-Tool([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
}

# No Java on PATH on this PC; the Android tools ship one.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
}

# -- What is published now --------------------------------------------------
$publishedCode = 0
if (-not $BuildOnly) {
    Invoke-Tool { gh repo view $repo --json name } | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "The GitHub repository $repo does not exist yet (or gh is not signed in). Create it first." }
    Invoke-Tool { gh release view "v$VersionName" --repo $repo --json tagName } | Out-Null
    if ($LASTEXITCODE -eq 0) { throw "Release v$VersionName already exists. Pick a new VersionName." }
    try {
        $publishedCode = [int](Invoke-RestMethod "https://github.com/$repo/releases/latest/download/version.json" -TimeoutSec 20).versionCode
    } catch {
        $publishedCode = 0   # nothing published yet
    }
}

# -- Version ----------------------------------------------------------------
$versionFile = Join-Path $root 'version.properties'
$before = Get-Content $versionFile -Raw
if ($before -notmatch '(?m)^versionCode=(\d+)\s*$') { throw "versionCode is missing from version.properties." }
$code = [int]$Matches[1]
if (-not $NoBump) { $code++ }
if ($code -le $publishedCode) {
    throw "versionCode $code is not higher than the published one ($publishedCode), so phones would not be offered it. Run without -NoBump."
}
$after = $before -replace '(?m)^versionCode=.*$', "versionCode=$code" -replace '(?m)^versionName=.*$', "versionName=$VersionName"
[System.IO.File]::WriteAllText($versionFile, $after)

# -- Build ------------------------------------------------------------------
Write-Host "Building Live News $VersionName (versionCode $code)..." -ForegroundColor Cyan
try {
    Invoke-Tool { & (Join-Path $root 'gradlew.bat') -p $root testDebugUnitTest assembleRelease --console=plain -q } | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "The build or its tests failed. Nothing was published." }

    $built = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $built)) { throw "The APK was not produced at $built." }

    $sdk = (Get-Content (Join-Path $root 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1) -replace '^sdk\.dir=', ''
    $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter apksigner.bat | Sort-Object FullName | Select-Object -Last 1
    if (-not $apksigner) { throw "apksigner was not found under $sdk\build-tools." }
    $certificate = Invoke-Tool { & $apksigner.FullName verify --print-certs $built } | Select-String 'SHA-256 digest: ([0-9a-f]+)'
    if (-not $certificate -or $certificate.Matches[0].Groups[1].Value -ne $appCertificate) {
        throw "The APK is not signed with this app's key, so phones would refuse it. Nothing was published."
    }
} catch {
    [System.IO.File]::WriteAllText($versionFile, $before)   # leave the version as it was
    throw
}

# -- The two files of a release ---------------------------------------------
$out = Join-Path $root 'build\release'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$apk = Join-Path $out 'LiveNews.apk'
$json = Join-Path $out 'version.json'
Copy-Item $built $apk -Force
$manifest = [ordered]@{
    versionCode = $code
    versionName = $VersionName
    notes       = $Notes
    # This release's own file, so the version and the APK can never come from two different releases.
    apkUrl      = "https://github.com/$repo/releases/download/v$VersionName/LiveNews.apk"
}
[System.IO.File]::WriteAllText($json, ($manifest | ConvertTo-Json))
$size = [Math]::Round((Get-Item $apk).Length / 1MB, 1)

if ($BuildOnly) {
    Write-Host "Built Live News $VersionName (versionCode $code, $size MB). Nothing published." -ForegroundColor Green
    Write-Host "  $apk"
    Write-Host "  $json"
    return
}

# -- Publish ----------------------------------------------------------------
Invoke-Tool { gh release create "v$VersionName" $apk $json --repo $repo --title "Live News $VersionName" --notes $Notes } | Write-Host
if ($LASTEXITCODE -ne 0) { throw "Publishing to GitHub failed. The files are in $out; version.properties already says $VersionName ($code)." }

Write-Host "Published Live News $VersionName (versionCode $code, $size MB)." -ForegroundColor Green
Write-Host "Phones offer it the next time the app is opened (it checks every few hours), or from Settings > App updates > Check now."
