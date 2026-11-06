package com.livenews.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livenews.app.update.UpdateInfo
import com.livenews.app.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The test app, with a newer version sitting on the pretend release server. */
class UpdateOfferingApp : TestLiveNewsApp() {
    override val published: UpdateInfo
        get() = UpdateInfo(versionCode = 99, versionName = "9.9.0", notes = "Faster refresh and a sharper widget.")
}

/** Opening the app when a newer version has been published: offered, installed on one tap. */
@RunWith(AndroidJUnit4::class)
@Config(application = UpdateOfferingApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: UpdateOfferingApp get() = ApplicationProvider.getApplicationContext()

    private fun waitForText(text: String) {
        try {
            rule.waitUntil(20_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val shown = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            }
            throw AssertionError(
                "Never saw \"$text\". Updater: ${app.updater.state.value}, checks made: ${app.fakeUpdateSource.checks}, " +
                    "settings: ${app.settings.settings.value}. On screen:\n" + shown.joinToString("\n"),
                e,
            )
        }
    }

    private fun waitForNoText(text: String) {
        rule.waitUntil(20_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aNewVersionIsOfferedOnOpeningAndInstalledOnOneTap() {
        // Nobody asked: the offer appears by itself, with what is new, above the headlines.
        waitForText("Live News 9.9.0 is available")
        waitForText("Faster refresh and a sharper widget.")
        waitForText("Headline from feeds.npr.org")

        rule.onNodeWithText("Update").performClick()
        waitForText("Installing Live News 9.9.0")
        assertEquals(1, app.fakeInstaller.installed.size)

        // Saying no to Android's own question puts the offer back rather than losing it.
        app.updater.onInstallCancelled()
        waitForText("Live News 9.9.0 is available")

        // A failure is shown, with a way to try again.
        rule.onNodeWithText("Update").performClick()
        waitForText("Installing Live News 9.9.0")
        app.updater.onInstallFailed("not enough storage")
        waitForText("Android could not install the update: not enough storage")
        rule.onNodeWithText("Try again").performClick()
        waitForText("Installing Live News 9.9.0")
        assertEquals(3, app.fakeInstaller.installed.size)
    }

    @Test
    fun laterPutsTheOfferAwayAndHeadlinesStayUsable() {
        waitForText("Live News 9.9.0 is available")
        rule.onNodeWithText("Later").performClick()
        waitForNoText("Live News 9.9.0 is available")
        assertEquals(UpdateState.Idle, app.updater.state.value)
        assertTrue(app.fakeInstaller.installed.isEmpty())
        waitForText("Headline from feeds.npr.org")
    }

    @Test
    fun androidIsAskedForPermissionOnceAndTheUpdateCarriesOnAfterwards() {
        app.fakeInstaller.allowed = false
        waitForText("Live News 9.9.0 is available")
        rule.onNodeWithText("Update").performClick()
        waitForText("Allow Live News to install its update")
        assertEquals(1, app.fakeInstaller.settingsOpened)
        assertTrue(app.fakeInstaller.installed.isEmpty())

        // The user switches it on in Android's settings and comes back to the app.
        app.fakeInstaller.allowed = true
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("Installing Live News 9.9.0")
        assertEquals(1, app.fakeInstaller.installed.size)
    }
}
