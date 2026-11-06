package com.livenews.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Not part of the normal test run. `gradlew testDebugUnitTest -Plive=true` fetches every built-in
 * source over the network with the app's own fetcher and parser, and writes what it found to
 * app/build/live-feeds.txt.
 */
@RunWith(RobolectricTestRunner::class)
class LiveFeedsCheck {

    @Test
    fun everyBuiltInSource() {
        assumeTrue(System.getProperty("livenews.live") == "true")
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
        val fetcher = FeedFetcher(client)
        val now = System.currentTimeMillis()
        val lines = runBlocking {
            DefaultSources.all.map { source ->
                async(Dispatchers.IO) {
                    try {
                        val feed = fetcher.fetch(source.url)
                        val items = feed.items
                        val newest = items.mapNotNull { it.publishedAt }.maxOrNull()
                        val age = newest?.let { "%.1fh".format((now - it) / 3_600_000.0) } ?: "?"
                        val state = if (items.isEmpty()) "EMPTY" else "OK   "
                        "$state ${source.id.padEnd(15)} ${if (source.enabled) "on " else "off"} items=${items.size} dated=${items.count { it.publishedAt != null }} " +
                            "pictures=${items.count { it.imageUrl != null }} summaries=${items.count { it.summary.isNotEmpty() }} newest=$age " +
                            "feed=\"${feed.title}\" first=\"${items.firstOrNull()?.title?.take(70)}\""
                    } catch (e: Exception) {
                        "FAIL  ${source.id.padEnd(15)} ${if (source.enabled) "on " else "off"} ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }.awaitAll()
        }
        File(System.getProperty("livenews.report") ?: "live-feeds.txt").apply { parentFile?.mkdirs() }.writeText(lines.joinToString("\n"))
    }
}
