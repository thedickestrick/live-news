package com.livenews.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleMergerTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val a = testSource("a")
    private val b = testSource("b")
    private val sources = listOf(a, b)

    private fun merge(existing: List<Article>, vararg fetched: Pair<Source, List<ParsedItem>>, at: Long = now, among: List<Source> = sources) =
        ArticleMerger.merge(existing, fetched.map { (source, items) -> source to ParsedFeed(source.name, items) }, among, at)

    @Test
    fun newStoriesAreAddedNewestFirst() {
        val merged = merge(
            emptyList(),
            a to listOf(item("Older", "https://a.test/1", now - 2 * hour), item("Newest", "https://a.test/2", now - hour)),
            b to listOf(item("Middle", "https://b.test/1", now - 90 * 60 * 1000)),
        )
        assertEquals(3, merged.added)
        assertEquals(listOf("Newest", "Middle", "Older"), merged.articles.map { it.title })
        assertTrue(merged.articles.all { it.firstSeenAt == now && !it.read && !it.saved })
        assertEquals("A", merged.articles.first().sourceName)
    }

    @Test
    fun aStorySeenAgainKeepsWhatTheReaderDidWithIt() {
        val first = merge(emptyList(), a to listOf(item("Draft headline", "https://a.test/1", now - hour, summary = "Old")))
        val touched = first.articles.map { it.copy(read = true, saved = true) }
        val later = now + 10 * 60 * 1000
        val second = merge(touched, a to listOf(item("Corrected headline", "https://a.test/1?utm_source=rss", now - hour)), at = later)

        assertEquals(0, second.added)
        val article = second.articles.single()
        assertEquals("Corrected headline", article.title)
        assertEquals("Old", article.summary)
        assertTrue(article.read && article.saved)
        assertEquals(now, article.firstSeenAt)
    }

    @Test
    fun theSameStoryFromTwoFeedsIsListedOnce() {
        val merged = merge(
            emptyList(),
            a to listOf(item("Shared", "https://news.test/story?at_medium=RSS", now - hour)),
            b to listOf(item("Shared", "https://www.news.test/story/", now - hour)),
        )
        assertEquals(1, merged.articles.size)
        assertEquals("a", merged.articles.single().sourceId)
    }

    @Test
    fun undatedAndFutureDatedStoriesAreTreatedAsNow() {
        val merged = merge(
            emptyList(),
            a to listOf(item("No date", "https://a.test/1"), item("From the future", "https://a.test/2", now + 5 * hour)),
        )
        assertTrue(merged.articles.all { it.publishedAt == now })

        // An undated story keeps the time it was first seen instead of jumping back to the top on every refresh.
        val again = merge(merged.articles, a to listOf(item("No date", "https://a.test/1")), at = now + hour)
        assertEquals(now, again.articles.first { it.title == "No date" }.publishedAt)
    }

    @Test
    fun oldStoriesAgeOutButSavedOnesStay() {
        val merged = merge(
            emptyList(),
            a to listOf(item("Ancient", "https://a.test/old", now - 8 * day), item("Recent", "https://a.test/new", now - day)),
        )
        assertEquals(listOf("Recent"), merged.articles.map { it.title })

        val saved = merged.articles.map { it.copy(saved = true) }
        val muchLater = merge(saved, at = now + 30 * day)
        assertEquals(listOf("Recent"), muchLater.articles.map { it.title })

        val unsaved = merge(merged.articles, at = now + 30 * day)
        assertTrue(unsaved.articles.isEmpty())
    }

    @Test
    fun switchingASourceOffRemovesItsStoriesExceptSavedOnes() {
        val merged = merge(
            emptyList(),
            a to listOf(item("A1", "https://a.test/1", now - hour), item("A2", "https://a.test/2", now - 2 * hour)),
            b to listOf(item("B1", "https://b.test/1", now - 3 * hour)),
        )
        val withSaved = merged.articles.map { if (it.title == "A2") it.copy(saved = true) else it }
        val pruned = ArticleMerger.prune(withSaved, listOf(a.copy(enabled = false), b), now)
        assertEquals(listOf("A2", "B1"), pruned.map { it.title })
    }

    @Test
    fun theListIsCapped() {
        val many = (1..ArticleMerger.MAX_ARTICLES + 50).map { item("Story $it", "https://a.test/$it", now - it * 1000L) }
        val merged = merge(emptyList(), a to many)
        assertEquals(ArticleMerger.MAX_ARTICLES, merged.articles.size)
        assertEquals("Story 1", merged.articles.first().title)
        assertFalse(merged.articles.any { it.title == "Story ${ArticleMerger.MAX_ARTICLES + 1}" })
    }
}
