package com.livenews.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Not part of the normal test run. `gradlew testDebugUnitTest -Plive=true` runs the app's real
 * updater against the real GitHub releases: a phone on an older version must be offered the
 * published one and receive its APK.
 */
class LiveUpdateCheck {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aPhoneOnAnOlderVersionGetsThePublishedRelease() = runBlocking {
        assumeTrue(System.getProperty("livenews.live") == "true")
        val installer = FakeInstaller(allowed = true, facts = ApkFacts("com.livenews.app", Long.MAX_VALUE))
        val updater = AppUpdater(
            source = GitHubUpdateSource(OkHttpClient()),
            installer = installer,
            packageName = "com.livenews.app",
            currentVersionCode = 1,
            downloadDir = File(folder.root, "update"),
            scope = CoroutineScope(Dispatchers.Default),
        )

        updater.check(quiet = false)
        val offered = updater.state.value
        assertTrue("Expected an update to be offered, got $offered", offered is UpdateState.Available)
        val info = (offered as UpdateState.Available).info
        assertTrue(info.toString(), info.versionCode >= 2 && info.versionName.isNotBlank() && info.apkUrl.startsWith(UpdateConfig.REPO_URL))

        updater.startUpdate()
        val end = withTimeout(180_000) { updater.state.first { it is UpdateState.Installing || it is UpdateState.Failed } }
        assertEquals(UpdateState.Installing(info), end)

        // "Installing" is announced just before the file is handed over, on another thread.
        withTimeout(10_000) { while (installer.installed.isEmpty()) delay(20) }

        // What reached the installer is a real APK (a zip), not an error page.
        val apk = installer.installed.single()
        assertTrue("Only ${apk.size} bytes arrived", apk.size > 1_000_000)
        assertEquals("PK", String(apk, 0, 2, Charsets.US_ASCII))
        File(System.getProperty("livenews.report") ?: "live-update.txt").resolveSibling("live-update.txt")
            .writeText("offered=${info.label} versionCode=${info.versionCode} apkBytes=${apk.size} from=${info.apkUrl} notes=${info.notes}")
    }
}
