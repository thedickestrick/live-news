package com.livenews.app.data

import java.io.IOException

/** Canned feeds for tests: an address maps to a feed, or to nothing (which fails like a dead site). */
class FakeFeeds(private val feeds: MutableMap<String, ParsedFeed> = HashMap()) : FeedSource {
    var fetchCount = 0
        private set

    operator fun set(url: String, feed: ParsedFeed?) {
        if (feed == null) feeds.remove(url) else feeds[url] = feed
    }

    override suspend fun fetch(url: String): ParsedFeed {
        fetchCount++
        return feeds[url] ?: throw IOException("The site answered with error 503.")
    }

    override suspend fun resolve(input: String): ResolvedFeed {
        val url = Links.cleanUserUrl(input) ?: throw IllegalArgumentException("That doesn't look like a web address.")
        val feed = feeds[url] ?: throw FeedParseException("No news feed found at that address.")
        return ResolvedFeed(url, feed)
    }
}

fun item(title: String, link: String, publishedAt: Long? = null, summary: String = "", imageUrl: String? = null) =
    ParsedItem(title = title, link = link, summary = summary, imageUrl = imageUrl, publishedAt = publishedAt)

fun testSource(id: String, enabled: Boolean = true) =
    Source(id = id, name = id.uppercase(), url = "https://$id.test/feed", category = Categories.CUSTOM, enabled = enabled, custom = true)
