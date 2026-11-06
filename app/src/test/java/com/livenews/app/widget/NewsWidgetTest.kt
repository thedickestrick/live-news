package com.livenews.app.widget

import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.runComposition
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasStartActivityClickAction
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.livenews.app.LiveNewsApp
import com.livenews.app.MainActivity
import com.livenews.app.OpenLinkActivity
import com.livenews.app.TestLiveNewsApp
import com.livenews.app.data.Article
import com.livenews.app.data.RefreshStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestLiveNewsApp::class)
class NewsWidgetTest {

    private val app: LiveNewsApp get() = ApplicationProvider.getApplicationContext()

    private fun texts(view: View, into: MutableList<String> = ArrayList()): List<String> {
        if (view is TextView && view.text.isNotEmpty()) into.add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) texts(view.getChildAt(i), into)
        return into
    }

    private fun lists(view: View, into: MutableList<AdapterView<*>> = ArrayList()): List<AdapterView<*>> {
        if (view is AdapterView<*>) into.add(view)
        else if (view is ViewGroup) for (i in 0 until view.childCount) lists(view.getChildAt(i), into)
        return into
    }

    /**
     * The widget the way the home screen gets it: Glance turns it into RemoteViews, and those are
     * inflated into real views here. Catches a widget that cannot be drawn at all.
     */
    @OptIn(ExperimentalGlanceApi::class)
    private fun render(): View = runBlocking {
        val remoteViews = withTimeout(30_000) {
            NewsWidget().runComposition(app, sizes = listOf(DpSize(300.dp, 220.dp))).first()
        }
        remoteViews.apply(app, FrameLayout(app))
    }

    @Test
    fun beforeAnyRefreshItInvitesOne() {
        val root = render()
        val shown = texts(root)
        assertTrue(shown.toString(), "Live News" in shown)
        assertTrue(shown.toString(), "Tap refresh to load headlines" in shown)
        assertTrue(shown.toString(), "No headlines yet. Tap to refresh." in shown)
        assertTrue(lists(root).isEmpty())
    }

    @Test
    fun afterARefreshItDrawsTheHeaderAndAHeadlineList() {
        runBlocking { app.repository.refresh() }
        val root = render()
        val shown = texts(root)
        assertTrue(shown.toString(), "Live News" in shown)
        assertTrue(shown.toString(), shown.any { it.startsWith("Updated ") })
        assertTrue(shown.toString(), shown.none { it.startsWith("No headlines") })
        // The rows themselves are filled in by the launcher, which this test has none of;
        // their content is checked in the next test.
        assertEquals(1, lists(root).size)
    }

    @Test
    fun headlinesOpenTheirStoryAndTheButtonsDoWhatTheySay() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            Article(
                id = "00000000000000a1", sourceId = "one", sourceName = "Source One", category = "World",
                title = "First story", link = "https://one.test/first", publishedAt = now - 60_000, firstSeenAt = now,
            ),
            Article(
                id = "00000000000000b2", sourceId = "two", sourceName = "Source Two", category = "Tech",
                title = "Second story", link = "https://two.test/second", publishedAt = now - 120_000, firstSeenAt = now,
            ),
        )
        runGlanceAppWidgetUnitTest {
            setContext(app)
            setAppWidgetSize(DpSize(300.dp, 220.dp))
            provideComposable {
                GlanceTheme { WidgetContent(articles, RefreshStatus(loaded = true, lastUpdatedAt = now)) }
            }

            onNode(hasTextEqualTo("Live News")).assertExists()
            onNode(hasText("Updated ")).assertExists()
            onNode(hasTextEqualTo("First story")).assertExists()
            onNode(hasTextEqualTo("Second story")).assertExists()
            onNode(hasText("Source One · ")).assertExists()

            // Tapping a headline opens that story (and tells the app which one, to mark it read).
            onNode(
                hasStartActivityClickAction<OpenLinkActivity>(
                    actionParametersOf(
                        OpenLinkActivity.UrlKey to "https://one.test/first",
                        OpenLinkActivity.ArticleIdKey to "00000000000000a1",
                    ),
                ),
            ).assertExists()
            onNode(
                hasStartActivityClickAction<OpenLinkActivity>(
                    actionParametersOf(
                        OpenLinkActivity.UrlKey to "https://two.test/second",
                        OpenLinkActivity.ArticleIdKey to "00000000000000b2",
                    ),
                ),
            ).assertExists()

            // The title opens the app; the round arrow refreshes.
            onNode(hasStartActivityClickAction<MainActivity>()).assertExists()
            onNode(hasContentDescriptionEqualTo("Refresh")).assert(hasRunCallbackClickAction<RefreshAction>())
        }
    }
}
