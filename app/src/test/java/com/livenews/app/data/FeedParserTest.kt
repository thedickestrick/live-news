package com.livenews.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Runs on Android's own XML parser (through Robolectric), the one the phone uses. */
@RunWith(RobolectricTestRunner::class)
class FeedParserTest {

    private fun parse(xml: String, base: String = "https://example.com/feeds/news.xml") =
        FeedParser().parse(xml.trimIndent().byteInputStream(), base)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun rss() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Example News</title>
                <link>https://example.com/</link>
                <image><title>Logo title</title><url>https://example.com/logo.png</url></image>
                <item>
                  <title>Markets rally as rates hold &amp;amp; inflation cools</title>
                  <link>https://example.com/business/markets-rally?utm_source=rss</link>
                  <guid isPermaLink="false">abc-123</guid>
                  <description><![CDATA[<p>Stocks rose <b>sharply</b> on Tuesday&nbsp;&mdash; the best day this year.</p><img src="https://example.com/px.gif" width="1" height="1">]]></description>
                  <pubDate>Tue, 03 Nov 2026 14:30:00 GMT</pubDate>
                  <media:thumbnail url="https://example.com/img/small.jpg" width="240" height="135"/>
                  <media:content url="https://example.com/img/large.jpg" medium="image" width="976"/>
                </item>
                <item>
                  <title>Relative link story</title>
                  <link>/world/story-2</link>
                  <description>Plain text summary.</description>
                  <pubDate>Tue, 03 Nov 2026 09:00:00 -0500</pubDate>
                  <enclosure url="http://example.com/img/enc.jpg" type="image/jpeg" length="1"/>
                </item>
                <item>
                  <title>A script link is dropped</title>
                  <link>javascript:alert(1)</link>
                </item>
                <item>
                  <guid>https://example.com/only-guid</guid>
                  <title>Guid used as link</title>
                  <content:encoded><![CDATA[<p>Body text here.</p><img src="//cdn.example.com/a.jpg">]]></content:encoded>
                </item>
              </channel>
            </rss>
            """,
        )
        assertEquals("Example News", feed.title)
        assertEquals(3, feed.items.size)

        val first = feed.items[0]
        assertEquals("Markets rally as rates hold & inflation cools", first.title)
        assertEquals("https://example.com/business/markets-rally?utm_source=rss", first.link)
        assertEquals("Stocks rose sharply on Tuesday — the best day this year.", first.summary)
        assertEquals("https://example.com/img/large.jpg", first.imageUrl)
        assertEquals(at("2026-11-03T14:30:00Z"), first.publishedAt)

        val second = feed.items[1]
        assertEquals("https://example.com/world/story-2", second.link)
        assertEquals("Plain text summary.", second.summary)
        assertEquals("https://example.com/img/enc.jpg", second.imageUrl)
        assertEquals(at("2026-11-03T14:00:00Z"), second.publishedAt)

        val third = feed.items[2]
        assertEquals("Guid used as link", third.title)
        assertEquals("https://example.com/only-guid", third.link)
        assertEquals("Body text here.", third.summary)
        assertEquals("https://cdn.example.com/a.jpg", third.imageUrl)
        assertNull(third.publishedAt)
    }

    @Test
    fun atom() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title type="text">Atom Example</title>
              <link rel="self" href="https://atom.example.org/feed.xml"/>
              <entry>
                <title type="html">Chip maker&amp;#8217;s &lt;em&gt;big&lt;/em&gt; bet</title>
                <link rel="replies" href="https://atom.example.org/comments/1"/>
                <link rel="alternate" type="text/html" href="https://atom.example.org/posts/1"/>
                <id>tag:atom.example.org,2026:1</id>
                <published>2026-11-04T08:15:00-05:00</published>
                <updated>2026-11-04T09:00:00-05:00</updated>
                <author><name>Jane</name></author>
                <content type="html">&lt;figure&gt;&lt;img src="https://atom.example.org/hero.png" alt=""/&gt;&lt;/figure&gt;&lt;p&gt;First paragraph.&lt;/p&gt;</content>
              </entry>
              <entry>
                <title>Second</title>
                <link href="https://atom.example.org/posts/2"/>
                <updated>2026-11-04T10:00:00Z</updated>
                <summary>Short one.</summary>
                <source><title>Other feed title</title></source>
              </entry>
            </feed>
            """,
            base = "https://atom.example.org/feed.xml",
        )
        assertEquals("Atom Example", feed.title)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Chip maker’s big bet", first.title)
        assertEquals("https://atom.example.org/posts/1", first.link)
        assertEquals("First paragraph.", first.summary)
        assertEquals("https://atom.example.org/hero.png", first.imageUrl)
        assertEquals(at("2026-11-04T13:15:00Z"), first.publishedAt)

        val second = feed.items[1]
        assertEquals("Second", second.title)
        assertEquals("https://atom.example.org/posts/2", second.link)
        assertEquals("Short one.", second.summary)
        assertEquals(at("2026-11-04T10:00:00Z"), second.publishedAt)
    }

    @Test
    fun rdf() {
        val feed = parse(
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://rdf.example.net/"><title>RDF Example</title><link>https://rdf.example.net/</link></channel>
              <item rdf:about="https://rdf.example.net/a">
                <title>RDF story</title><link>https://rdf.example.net/a</link><description>Desc</description>
                <dc:date>2026-11-02T06:00:00+01:00</dc:date>
              </item>
            </rdf:RDF>
            """,
        )
        assertEquals("RDF Example", feed.title)
        assertEquals(1, feed.items.size)
        assertEquals("RDF story", feed.items[0].title)
        assertEquals(at("2026-11-02T05:00:00Z"), feed.items[0].publishedAt)
    }

    @Test
    fun pictureAddressInsideAnImageTag() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>Pictures</title>
              <item><title>Plain</title><link>https://example.com/1</link>
                <image>
                  https://example.com/pic.jpg
                </image></item>
              <item><title>Nested</title><link>https://example.com/2</link>
                <image><url>https://example.com/nested.png</url><title>Caption</title></image></item>
              <item><title>Not an address</title><link>https://example.com/3</link><image>see attached</image></item>
            </channel></rss>
            """,
        )
        assertEquals("https://example.com/pic.jpg", feed.items[0].imageUrl)
        assertEquals("https://example.com/nested.png", feed.items[1].imageUrl)
        assertNull(feed.items[2].imageUrl)
        // The channel's own <image><title> must not be taken for the feed's name.
        assertEquals("Pictures", feed.title)
    }

    @Test
    fun htmlEntitiesOutsideCdataDoNotBreakTheFeed() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>Caf&eacute; News</title>
              <item><title>Caf&eacute; opens &nbsp; downtown</title><link>https://example.com/cafe</link></item>
            </channel></rss>
            """,
        )
        assertEquals("Café News", feed.title)
        assertEquals("Café opens downtown", feed.items.single().title)
    }

    @Test
    fun aFeedCutOffHalfWayStillGivesItsFirstStories() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>Cut</title>
              <item><title>Complete story</title><link>https://example.com/1</link></item>
              <item><title>Broken story</title><link>https://example.com/2</li
            """,
        )
        assertTrue(feed.items.isNotEmpty())
        assertEquals("Complete story", feed.items[0].title)
    }

    @Test
    fun aWebPageIsNotAFeed() {
        assertThrows(FeedParseException::class.java) {
            parse("<!DOCTYPE html><html><head><title>Hello</title></head><body><p>Hi</p></body></html>")
        }
        assertThrows(FeedParseException::class.java) { parse("just some words, no markup") }
        assertThrows(FeedParseException::class.java) { parse("") }
    }
}
