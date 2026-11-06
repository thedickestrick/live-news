package com.livenews.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException

data class ResolvedFeed(val url: String, val feed: ParsedFeed)

/** Where headlines come from. An interface so tests can run without the network. */
interface FeedSource {
    suspend fun fetch(url: String): ParsedFeed

    /** Finds the feed behind what a person typed: a feed address, or a site that advertises one. */
    suspend fun resolve(input: String): ResolvedFeed
}

class FeedFetcher(
    private val client: OkHttpClient,
    private val parser: FeedParser = FeedParser(),
) : FeedSource {

    private class Page(val url: String, val bytes: ByteArray)

    override suspend fun fetch(url: String): ParsedFeed = parse(download(url))

    override suspend fun resolve(input: String): ResolvedFeed {
        val url = Links.cleanUserUrl(input) ?: throw IllegalArgumentException("That doesn't look like a web address.")
        val page = download(url)
        try {
            return ResolvedFeed(page.url, parse(page))
        } catch (e: FeedParseException) {
            val html = String(page.bytes, 0, minOf(page.bytes.size, 512 * 1024), Charsets.UTF_8)
            val advertised = FeedDiscovery.find(html, page.url)
                ?: throw FeedParseException("No news feed found at that address.")
            val feedPage = download(advertised)
            return ResolvedFeed(feedPage.url, parse(feedPage))
        }
    }

    private suspend fun parse(page: Page): ParsedFeed = withContext(Dispatchers.Default) {
        parser.parse(ByteArrayInputStream(page.bytes), page.url)
    }

    private suspend fun download(url: String): Page {
        val request = try {
            Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", ACCEPT).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a valid address.")
        }
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    // The body is read here, on OkHttp's thread, so cancelling the call also stops the download.
                    continuation.resumeWith(runCatching { response.use { readPage(it) } })
                }
            })
        }
    }

    private fun readPage(response: Response): Page {
        if (!response.isSuccessful) throw IOException("The site answered with error ${response.code}.")
        val body = response.body ?: throw IOException("The site sent nothing back.")
        val source = body.source()
        if (source.request(MAX_BYTES + 1)) throw IOException("That feed is too large.")
        return Page(response.request.url.toString(), source.readByteArray())
    }

    private companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        const val USER_AGENT = "LiveNews/1.0 (Android news reader)"
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5"
    }
}

/** Finds the feed a web page advertises with `<link rel="alternate" type="application/rss+xml" href="...">`. */
object FeedDiscovery {
    private val linkTag = Regex("""(?is)<link\b[^>]*>""")
    private val attr = Regex("""(?is)\b([a-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun find(html: String, pageUrl: String): String? {
        for (match in linkTag.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (a in attr.findAll(match.value)) {
                val value = (a.groups[2] ?: a.groups[3] ?: a.groups[4])?.value.orEmpty()
                attrs[a.groupValues[1].lowercase()] = value.trim()
            }
            val rel = attrs["rel"]?.lowercase().orEmpty()
            val type = attrs["type"]?.lowercase().orEmpty()
            if (!rel.split(' ').contains("alternate")) continue
            if (!type.contains("rss") && !type.contains("atom")) continue
            val href = attrs["href"]?.replace("&amp;", "&") ?: continue
            Links.resolve(pageUrl, href)?.let { return Links.toHttps(it) }
        }
        return null
    }
}
