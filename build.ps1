# Builds the installable APK. Add -Install to also put it on a phone connected by USB
# (USB debugging on), or -Test to run the unit tests first.
param(
    [switch]$Install,
    [switch]$Test
)
$ErrorActionPreference = 'Stop'

# No Java on PATH on this PC; the Android tools ship one.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
}

$tasks = @()
if ($Test) { $tasks += 'testDebugUnitTest' }
$tasks += 'assembleRelease'

& (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot @tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
Write-Host "Built $apk"

if ($Install) {
    $sdk = (Get-Content (Join-Path $PSScriptRoot 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk\.dir=', ''
    & (Join-Path $sdk 'platform-tools\adb.exe') install -r $apk
    exit $LASTEXITCODE
}
