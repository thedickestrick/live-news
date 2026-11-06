package com.livenews.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val a = testSource("a")
    private val b = testSource("b")
    private val feeds = FakeFeeds()
    private lateinit var store: NewsStore

    @Before
    fun setUp() {
        store = NewsStore(folder.root)
        // Built-in sources off, so each test only talks to its own two feeds.
        store.saveSources(DefaultSources.all.map { it.copy(enabled = false) } + listOf(a, b))
        feeds[a.url] = ParsedFeed("A", listOf(item("A one", "https://a.test/1", clock - hour), item("A two", "https://a.test/2", clock - 2 * hour)))
        feeds[b.url] = ParsedFeed("B", listOf(item("B one", "https://b.test/1", clock - 90 * 60 * 1000)))
    }

    private fun repository() = NewsRepository(store, feeds, now = { clock })

    @Test
    fun refreshCollectsHeadlinesAndTheyAreStillThereAfterARestart() = runBlocking {
        val repo = repository()
        var widgetUpdates = 0
        repo.onArticlesChanged = { widgetUpdates++ }

        val outcome = repo.refresh()
        assertEquals(RefreshOutcome(total = 2, failed = 0, added = 3), outcome)
        assertEquals(listOf("A one", "B one", "A two"), repo.articles.value.map { it.title })
        assertEquals(clock, repo.status.value.lastUpdatedAt)
        assertNull(repo.status.value.lastError)
        assertFalse(repo.status.value.refreshing)
        assertEquals(1, widgetUpdates)

        val restarted = repository()
        restarted.ensureLoaded()
        assertEquals(repo.articles.value, restarted.articles.value)
        assertEquals(clock, restarted.status.value.lastUpdatedAt)
    }

    @Test
    fun oneDeadSourceIsReportedAndTheRestStillLoad() = runBlocking {
        feeds[b.url] = null
        val repo = repository()
        val outcome = repo.refresh()
        assertEquals(RefreshOutcome(total = 2, failed = 1, added = 2), outcome)
        assertEquals(setOf("b"), repo.status.value.failedSources.keys)
        assertNull(repo.status.value.lastError)

        // It recovers on the next round.
        feeds[b.url] = ParsedFeed("B", listOf(item("B back", "https://b.test/9", clock)))
        clock += 60_000
        assertEquals(RefreshOutcome(total = 2, failed = 0, added = 1), repo.refresh())
        assertTrue(repo.status.value.failedSources.isEmpty())
        assertEquals("B back", repo.articles.value.first().title)
    }

    @Test
    fun beingOfflineKeepsTheOldHeadlines() = runBlocking {
        val repo = repository()
        repo.refresh()
        val updatedAt = repo.status.value.lastUpdatedAt
        feeds[a.url] = null
        feeds[b.url] = null
        clock += 5 * 60_000

        val outcome = repo.refresh()
        assertEquals(2, outcome.failed)
        assertNotNull(repo.status.value.lastError)
        assertEquals(updatedAt, repo.status.value.lastUpdatedAt)
        assertEquals(3, repo.articles.value.size)
    }

    @Test
    fun savedAndReadMarksSurviveARestart() = runBlocking {
        val repo = repository()
        repo.refresh()
        val target = repo.articles.value.first { it.title == "A two" }
        repo.setSaved(target.id, true)
        repo.markRead(target.id)

        val restarted = repository()
        restarted.ensureLoaded()
        val reloaded = restarted.articles.value.first { it.id == target.id }
        assertTrue(reloaded.saved && reloaded.read)
    }

    @Test
    fun switchingASourceOffRemovesItsHeadlinesAndTheChoiceSticks() = runBlocking {
        val repo = repository()
        repo.refresh()
        repo.setSourceEnabled("a", false)
        assertEquals(listOf("B one"), repo.articles.value.map { it.title })

        val restarted = repository()
        restarted.ensureLoaded()
        assertFalse(restarted.sources.value.first { it.id == "a" }.enabled)
        clock += 60_000
        assertEquals(1, restarted.refresh().total)
    }

    @Test
    fun aFeedCanBeAddedOnceAndRemoved() = runBlocking {
        val repo = repository()
        repo.refresh()
        feeds["https://blog.test/rss"] = ParsedFeed("The Blog", listOf(item("Blog post", "https://blog.test/p1", clock)))

        val added = repo.addSource("http://blog.test/rss")
        assertEquals("The Blog", added.name)
        assertTrue(added.custom && added.enabled)
        assertEquals("Blog post", repo.articles.value.first().title)

        val duplicate = assertThrows(IllegalStateException::class.java) { runBlocking { repo.addSource("blog.test/rss") } }
        assertTrue(duplicate.message!!.contains("already"))
        assertThrows(FeedParseException::class.java) { runBlocking { repo.addSource("nothing.test") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addSource("not a url") } }

        repo.removeSource(added.id)
        assertTrue(repo.sources.value.none { it.id == added.id })
        assertTrue(repo.articles.value.none { it.title == "Blog post" })

        // Built-in sources can be switched off but not removed.
        val builtIn = DefaultSources.all.first().id
        repo.removeSource(builtIn)
        assertTrue(repo.sources.value.any { it.id == builtIn })
    }

    @Test
    fun builtInSourcesComeFromTheAppAndKeepTheUsersChoice() {
        val first = DefaultSources.all.first()
        val stored = listOf(first.copy(enabled = !first.enabled, url = "https://old.example/feed"), a)
        val merged = DefaultSources.mergeWithStored(stored)
        assertEquals(DefaultSources.all.size + 1, merged.size)
        assertEquals(first.url, merged.first().url)
        assertEquals(!first.enabled, merged.first().enabled)
        assertEquals(a, merged.last())
        assertEquals(DefaultSources.all, DefaultSources.mergeWithStored(null))
        assertEquals(DefaultSources.all.size, DefaultSources.all.map { it.id }.toSet().size)
    }
}
