package com.livenews.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livenews.app.data.FeedParseException
import com.livenews.app.data.FeedSource
import com.livenews.app.data.Links
import com.livenews.app.data.ParsedFeed
import com.livenews.app.data.ParsedItem
import com.livenews.app.data.ResolvedFeed
import com.livenews.app.update.ApkFacts
import com.livenews.app.update.FakeInstaller
import com.livenews.app.update.FakeUpdateSource
import com.livenews.app.update.Installer
import com.livenews.app.update.UpdateInfo
import com.livenews.app.update.UpdateSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real app, but every source answers instantly with one made-up story and nothing touches the network. */
open class TestLiveNewsApp : LiveNewsApp() {
    private val minute = 60_000L

    /** What the pretend release server offers. By default: the version already installed, so no update. */
    open val published: UpdateInfo get() = UpdateInfo(versionCode = BuildConfig.VERSION_CODE, versionName = BuildConfig.VERSION_NAME)

    val fakeInstaller: FakeInstaller by lazy { FakeInstaller(allowed = true, facts = ApkFacts(packageName, 99)) }

    val fakeUpdateSource: FakeUpdateSource by lazy { FakeUpdateSource(published) }

    override fun createUpdateSource(): UpdateSource = fakeUpdateSource

    override fun createInstaller(): Installer = fakeInstaller

    override fun createFeedSource(): FeedSource = object : FeedSource {
        override suspend fun fetch(url: String): ParsedFeed {
            val host = Links.host(url)
            // A fixed order, so the tests know which stories are at the top of the list.
            val age = when {
                url.contains("goodfeed") -> 0L
                host == "feeds.npr.org" -> 1 * minute
                host == "techcrunch.com" -> 2 * minute
                host == "espn.com" -> 3 * minute
                else -> 10 * minute + (host.hashCode() and 0xfff) * 1000L
            }
            return ParsedFeed(
                host,
                listOf(
                    ParsedItem(
                        title = "Headline from $host",
                        link = "https://$host/story",
                        summary = "A short summary of the story from $host.",
                        imageUrl = null,
                        publishedAt = System.currentTimeMillis() - age,
                    ),
                ),
            )
        }

        override suspend fun resolve(input: String): ResolvedFeed {
            val url = Links.cleanUserUrl(input) ?: throw IllegalArgumentException("That doesn't look like a web address.")
            if (!url.contains("goodfeed")) throw FeedParseException("No news feed found at that address.")
            return ResolvedFeed(url, fetch(url).copy(title = "Good Feed"))
        }
    }

    override fun scheduleBackgroundRefresh() {}

    override suspend fun refreshWidgets() {}
}

/**
 * Starts the real MainActivity (on Robolectric, no phone needed) and walks the main paths:
 * headlines load by themselves, filters and search narrow them, a story can be saved,
 * settings open, and a feed can be added.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestLiveNewsApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
// Real text measuring and drawing. Robolectric's default stand-in measures every character as one
// pixel, which sends a Material text field with a hint line into an endless re-measure.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) = waitOrShowScreen("Never saw \"$text\"") {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForNoText(text: String) = waitOrShowScreen("\"$text\" never went away") {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
    }

    /** Like waitUntil, but a failure says what was on screen instead of just "timed out". */
    private fun waitOrShowScreen(problem: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(20_000, condition)
        } catch (e: ComposeTimeoutException) {
            val texts = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            }
            throw AssertionError("$problem. On screen:\n" + texts.joinToString("\n"), e)
        }
    }

    /** The filter chips scroll sideways, so bring the chip on screen before tapping it. */
    private fun tapFilter(label: String) {
        rule.onNodeWithTag("filters").performScrollToNode(hasText(label))
        rule.onNodeWithText(label).performClick()
    }

    @Test
    fun headlinesLoadOnStartAndCanBeFilteredAndSearched() {
        waitForText("Headline from feeds.npr.org")
        rule.onNodeWithText("Live News").assertIsDisplayed()
        // Not "just now": the test clock runs fast, so by now it may read "1 min ago".
        waitForText("Updated ")

        // A category chip narrows the list to that category's sources.
        tapFilter("Tech")
        waitForNoText("Headline from feeds.npr.org")
        waitForText("Headline from techcrunch.com")

        tapFilter("All")
        waitForText("Headline from feeds.npr.org")

        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNodeWithText("Search headlines").performTextInput("espn")
        waitForNoText("Headline from feeds.npr.org")
        waitForText("Headline from espn.com")

        rule.onNodeWithContentDescription("Close search").performClick()
        waitForText("Headline from feeds.npr.org")
    }

    @Test
    fun savingAStoryPutsItUnderSaved() {
        waitForText("Headline from feeds.npr.org")
        tapFilter("Saved")
        waitForText("Nothing saved yet")

        tapFilter("Tech")
        waitForText("Headline from techcrunch.com")
        rule.onAllNodes(hasContentDescription("Save"))[0].performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasContentDescription("Remove from saved")).fetchSemanticsNodes().size == 1 }

        tapFilter("Saved")
        waitForNoText("Headline from theverge.com")
        waitForText("Headline from techcrunch.com")
    }

    @Test
    fun settingsListSourcesAndAFeedCanBeAdded() {
        waitForText("Headline from feeds.npr.org")
        rule.onNodeWithContentDescription("Settings").performClick()
        waitForText("While the app is open")
        // The feed screen is gone once the screen change has finished.
        rule.waitUntil(20_000) { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1 }

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("PBS NewsHour"))
        rule.onNodeWithText("PBS NewsHour").assertIsDisplayed()

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Add a feed"))
        waitForText("Type a news site")

        // An address with no feed says so, and keeps what was typed so it can be corrected.
        rule.onNodeWithText("Add a feed").performTextInput("nofeed.example.com")
        rule.onNodeWithText("Add").performClick()
        waitForText("No news feed found at that address.")

        rule.onNodeWithText("Add a feed").performTextInput("/goodfeed")
        rule.onNodeWithText("Add").performClick()
        waitForText("Added Good Feed")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Good Feed"))
        rule.onNodeWithText("Good Feed").assertIsDisplayed()

        // Its stories are in the list straight away, without waiting for the next refresh.
        rule.onNodeWithContentDescription("Back").performClick()
        waitForText("Headline from nofeed.example.com")
    }

    @Test
    fun settingsShowTheVersionAndCanCheckForUpdates() {
        waitForText("Headline from feeds.npr.org")
        // Nothing newer is published, so the headlines screen stays quiet about updates.
        rule.onNodeWithText("Update").assertDoesNotExist()

        rule.onNodeWithContentDescription("Settings").performClick()
        waitForText("While the app is open")
        rule.waitUntil(20_000) { rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1 }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check for updates automatically"))
        waitForText("Version ${BuildConfig.VERSION_NAME}")

        rule.onNodeWithText("Check now").performClick()
        waitForText("You have the latest version.")
    }
}
