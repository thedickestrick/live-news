package com.livenews.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppUpdaterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val pkg = "com.livenews.app"
    private val installedVersion = 4
    private val newer = UpdateInfo(versionCode = 5, versionName = "1.4.0", notes = "Faster refresh.", apkUrl = UpdateConfig.APK_URL)
    private val source = FakeUpdateSource(newer)
    private val installer = FakeInstaller(allowed = true, facts = ApkFacts(pkg, 5))
    private var clock = 1_000_000L
    private val hour = 60 * 60 * 1000L

    // Unconfined: the download in these tests never waits, so an update runs to its end inside startUpdate().
    private fun updater() = AppUpdater(
        source = source,
        installer = installer,
        packageName = pkg,
        currentVersionCode = installedVersion,
        downloadDir = File(folder.root, "update"),
        scope = CoroutineScope(Dispatchers.Unconfined),
        now = { clock },
    )

    @Test
    fun aNewerVersionIsOffered() = runBlocking {
        val updater = updater()
        updater.check(quiet = false)
        assertEquals(UpdateState.Available(newer), updater.state.value)
    }

    @Test
    fun theSameOrAnOlderVersionIsNotAnUpdate() = runBlocking {
        val updater = updater()
        source.info = newer.copy(versionCode = installedVersion)
        updater.check(quiet = false)
        assertEquals(UpdateState.UpToDate, updater.state.value)

        source.info = newer.copy(versionCode = installedVersion - 1)
        updater.check(quiet = true)
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun beingOfflineIsSilentForTheAutomaticCheckAndSaidForTheButton() = runBlocking {
        val updater = updater()
        source.info = null
        updater.check(quiet = true)
        assertEquals(UpdateState.Idle, updater.state.value)

        updater.check(quiet = false)
        val failed = updater.state.value as UpdateState.Failed
        assertNull(failed.info)
        assertTrue(failed.message, failed.message.startsWith("Couldn't check for updates"))
    }

    @Test
    fun theAutomaticCheckRunsEveryFewHoursNotOnEveryOpen() = runBlocking {
        val updater = updater()
        source.info = newer.copy(versionCode = installedVersion)
        updater.checkIfDue()
        updater.checkIfDue()
        clock += 5 * hour
        updater.checkIfDue()
        assertEquals(1, source.checks)

        clock += 2 * hour
        source.info = newer
        updater.checkIfDue()
        assertEquals(2, source.checks)
        assertEquals(UpdateState.Available(newer), updater.state.value)

        // An offer that is already showing is left alone.
        clock += 7 * hour
        updater.checkIfDue()
        assertEquals(2, source.checks)
    }

    @Test
    fun anUpdateIsDownloadedCheckedAndHandedToAndroid() = runBlocking {
        val updater = updater()
        updater.check(quiet = true)
        updater.startUpdate()

        assertEquals(UpdateState.Installing(newer), updater.state.value)
        assertEquals(1, source.downloads)
        assertArrayEquals(source.apkBytes, installer.installed.single())
        assertEquals(0, installer.settingsOpened)
    }

    @Test
    fun withoutPermissionItAsksOnceThenCarriesOnWhenTheAppReturns() = runBlocking {
        val updater = updater()
        installer.allowed = false
        updater.check(quiet = true)
        updater.startUpdate()
        assertEquals(UpdateState.NeedsPermission(newer), updater.state.value)
        assertEquals(1, installer.settingsOpened)
        assertEquals(0, source.downloads)

        // Came back without switching it on: still waiting, and not nagging.
        updater.onAppVisible()
        assertEquals(UpdateState.NeedsPermission(newer), updater.state.value)
        assertEquals(1, installer.settingsOpened)

        installer.allowed = true
        updater.onAppVisible()
        assertEquals(UpdateState.Installing(newer), updater.state.value)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun aDownloadThatIsNotANewerLiveNewsIsRefused() = runBlocking {
        for (wrong in listOf(ApkFacts("com.other.app", 9), ApkFacts(pkg, installedVersion.toLong()), null)) {
            val updater = updater()
            installer.facts = wrong
            updater.check(quiet = true)
            updater.startUpdate()

            val failed = updater.state.value as UpdateState.Failed
            assertEquals(newer, failed.info)
            assertTrue(failed.message, failed.message.contains("not a newer Live News"))
            assertTrue(installer.installed.isEmpty())
            assertFalse(File(folder.root, "update/LiveNews-update.apk").exists())
        }
    }

    @Test
    fun aFailedDownloadCanBeTriedAgain() = runBlocking {
        val updater = updater()
        source.downloadProblem = "The download failed (error 503)."
        updater.check(quiet = true)
        updater.startUpdate()
        assertEquals(UpdateState.Failed("The download failed (error 503).", newer), updater.state.value)

        source.downloadProblem = null
        updater.startUpdate()
        assertEquals(UpdateState.Installing(newer), updater.state.value)
    }

    @Test
    fun whatAndroidReportsBack() = runBlocking {
        val updater = updater()
        updater.check(quiet = true)
        updater.startUpdate()

        // "Cancel" on Android's question keeps the offer.
        updater.onInstallCancelled()
        assertEquals(UpdateState.Available(newer), updater.state.value)

        updater.startUpdate()
        updater.onInstallFailed("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        val failed = updater.state.value as UpdateState.Failed
        assertEquals(newer, failed.info)
        assertTrue(failed.message, failed.message.endsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))

        updater.startUpdate()
        updater.onInstalled()
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun laterHidesTheOfferButNeverInterruptsAnInstall() = runBlocking {
        val updater = updater()
        updater.check(quiet = true)
        updater.dismiss()
        assertEquals(UpdateState.Idle, updater.state.value)

        updater.check(quiet = false)
        updater.startUpdate()
        updater.dismiss()
        assertEquals(UpdateState.Installing(newer), updater.state.value)
    }

    @Test
    fun theVersionFileIsReadAndOnlyThisAppsOwnDownloadsAreTrusted() {
        val full = UpdateInfo.parse(
            """
            {
              "versionCode": 7,
              "versionName": "1.6.0",
              "notes": "Widget fixes.",
              "apkUrl": "https://github.com/thedickestrick/live-news/releases/download/v1.6.0/LiveNews.apk",
              "somethingNew": true
            }
            """.trimIndent(),
        )
        assertEquals(7, full.versionCode)
        assertEquals("1.6.0", full.label)
        assertEquals("Widget fixes.", full.notes)
        assertEquals("https://github.com/thedickestrick/live-news/releases/download/v1.6.0/LiveNews.apk", full.apkUrl)

        // An address anywhere else is ignored in favour of the app's own release.
        val elsewhere = UpdateInfo.parse("""{"versionCode": 8, "apkUrl": "https://example.com/evil.apk"}""")
        assertEquals(UpdateConfig.APK_URL, elsewhere.apkUrl)
        assertEquals("8", elsewhere.label)

        // Windows tools like to put a byte-order mark in front.
        assertEquals(9, UpdateInfo.parse("﻿{\"versionCode\": 9}").versionCode)

        assertThrows(Exception::class.java) { UpdateInfo.parse("<html>Not Found</html>") }
        assertThrows(Exception::class.java) { UpdateInfo.parse("""{"versionName": "1.0"}""") }
    }
}
