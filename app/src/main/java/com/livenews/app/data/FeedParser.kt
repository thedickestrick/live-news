package com.livenews.app.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream

class FeedParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ParsedItem(
    val title: String,
    val link: String,
    val summary: String,
    val imageUrl: String?,
    val publishedAt: Long?,
)

data class ParsedFeed(val title: String?, val items: List<ParsedItem>)

/**
 * Reads RSS 2.0, RSS 1.0 (RDF) and Atom. Feed content is untrusted: only http(s) links and
 * pictures are kept, and all text is reduced to plain text.
 */
class FeedParser {

    fun parse(input: InputStream, baseUrl: String): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        // Real feeds are often slightly broken (stray entities, unclosed tags); read what can be read.
        try {
            parser.setFeature(FEATURE_RELAXED, true)
        } catch (e: XmlPullParserException) {
        }

        var feedTitle: String? = null
        val items = ArrayList<ParsedItem>()
        var rootSeen = false
        try {
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.orEmpty().lowercase()
                    if (!rootSeen) {
                        rootSeen = true
                        if (name !in ROOTS) throw FeedParseException("That address is not a news feed.")
                    } else if (name == "item" || name == "entry") {
                        readItem(parser, baseUrl)?.let(items::add)
                    } else if (name == "title" && feedTitle == null && parser.depth <= 3) {
                        feedTitle = HtmlText.toPlain(readText(parser)).ifBlank { null }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            if (items.isEmpty()) throw FeedParseException("That address is not a readable news feed.", e)
        } catch (e: FeedParseException) {
            throw e
        } catch (e: IOException) {
            if (items.isEmpty()) throw e
        } catch (e: RuntimeException) {
            // The platform parser throws these on some malformed input.
            if (items.isEmpty()) throw FeedParseException("That address is not a readable news feed.", e)
        }
        if (!rootSeen) throw FeedParseException("That address is not a news feed.")
        return ParsedFeed(feedTitle, items)
    }

    private class ImagePick {
        var url: String? = null
        private var width = -1

        fun offer(candidate: String?, candidateWidth: Int) {
            if (candidate.isNullOrBlank()) return
            if (url == null || candidateWidth > width) {
                url = candidate
                width = candidateWidth
            }
        }
    }

    private fun readItem(p: XmlPullParser, baseUrl: String): ParsedItem? {
        val itemDepth = p.depth
        var title = ""
        var link: String? = null
        var guid: String? = null
        var summaryHtml: String? = null
        var contentHtml: String? = null
        var published: Long? = null
        var updated: Long? = null
        val media = ImagePick()
        var otherImage: String? = null

        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth <= itemDepth) break
            if (event != XmlPullParser.START_TAG) continue
            val name = p.name.orEmpty().lowercase()
            val direct = p.depth == itemDepth + 1
            when {
                direct && name == "title" -> title = HtmlText.toPlain(readText(p))
                direct && name == "link" -> {
                    val href = p.getAttributeValue(null, "href")
                    if (href != null) {
                        // Atom: several links per entry; the article itself is rel="alternate" (the default).
                        val rel = p.getAttributeValue(null, "rel") ?: "alternate"
                        if (rel == "alternate" && link == null) link = href
                    } else {
                        val text = readText(p).trim()
                        if (text.isNotEmpty() && link == null) link = text
                    }
                }
                direct && (name == "guid" || name == "id") -> {
                    val permalink = p.getAttributeValue(null, "isPermaLink") != "false"
                    val text = readText(p).trim()
                    if (permalink && Links.isWeb(text)) guid = text
                }
                direct && (name == "description" || name == "summary") -> summaryHtml = readText(p)
                direct && (name == "content:encoded" || name == "content") -> contentHtml = readText(p)
                direct && (name == "pubdate" || name == "published" || name == "dc:date" || name == "date") ->
                    published = DateParser.parse(readText(p)) ?: published
                direct && name == "updated" -> updated = DateParser.parse(readText(p))
                name == "media:thumbnail" -> media.offer(p.getAttributeValue(null, "url"), width(p))
                name == "media:content" -> {
                    val url = p.getAttributeValue(null, "url")
                    val medium = p.getAttributeValue(null, "medium")
                    val type = p.getAttributeValue(null, "type")
                    val isImage = medium == "image" || type?.startsWith("image/") == true ||
                        (medium == null && type == null && url != null && IMAGE_EXT.containsMatchIn(url))
                    if (isImage) media.offer(url, width(p))
                }
                direct && name == "enclosure" -> {
                    if (p.getAttributeValue(null, "type")?.startsWith("image/") == true) {
                        media.offer(p.getAttributeValue(null, "url"), 0)
                    }
                }
                direct && name == "itunes:image" -> otherImage = otherImage ?: p.getAttributeValue(null, "href")
                direct && name == "image" -> otherImage = readImageAddress(p) ?: otherImage
            }
        }

        val resolvedLink = (link ?: guid)?.let { Links.resolve(baseUrl, it) } ?: return null
        val summary = HtmlText.toPlain(summaryHtml ?: contentHtml, SUMMARY_LENGTH)
        if (title.isBlank()) title = HtmlText.toPlain(summaryHtml ?: contentHtml, 120)
        if (title.isBlank()) return null
        val image = (media.url ?: otherImage ?: HtmlText.firstImage(contentHtml) ?: HtmlText.firstImage(summaryHtml))
            ?.let { Links.resolve(baseUrl, it) }
            ?.let(Links::toHttps)
        return ParsedItem(
            title = title,
            link = resolvedLink,
            summary = if (summary.equals(title, ignoreCase = true)) "" else summary,
            imageUrl = image,
            publishedAt = published ?: updated,
        )
    }

    private fun width(p: XmlPullParser): Int = p.getAttributeValue(null, "width")?.trim()?.toIntOrNull() ?: 0

    /**
     * The picture address in an item's `<image>`: either straight inside it (CBS News) or in a
     * nested `<url>`. Ends on the closing tag.
     */
    private fun readImageAddress(p: XmlPullParser): String? {
        val depth = p.depth
        val own = StringBuilder()
        var nested: String? = null
        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth <= depth) break
            if (event == XmlPullParser.TEXT && p.depth == depth) own.append(p.text.orEmpty())
            if (event == XmlPullParser.START_TAG && p.name.equals("url", ignoreCase = true)) nested = readText(p).trim()
        }
        return (nested ?: own.toString().trim()).takeIf { Links.isWeb(it) && it.none(Char::isWhitespace) }
    }

    /** All the text inside the current element, with any nested markup dropped. Ends on its closing tag. */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val text = StringBuilder()
        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth <= depth) break
            if (event == XmlPullParser.TEXT) text.append(p.text.orEmpty())
        }
        return text.toString()
    }

    private companion object {
        const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        const val SUMMARY_LENGTH = 280
        val ROOTS = setOf("rss", "feed", "rdf:rdf", "rdf")
        val IMAGE_EXT = Regex("""(?i)\.(jpe?g|png|webp|gif|avif)(\?|$)""")
    }
}
