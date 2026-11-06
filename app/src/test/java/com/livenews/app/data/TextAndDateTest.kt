package com.livenews.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TextAndDateTest {

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun htmlBecomesPlainText() {
        assertEquals(
            "Stocks rose sharply on Tuesday — the best day this year.",
            HtmlText.toPlain("<p>Stocks rose <b>sharply</b> on Tuesday&nbsp;&mdash; the best day this year.</p><script>x()</script>"),
        )
        assertEquals("First line Second line", HtmlText.toPlain("<p>First line</p><p>Second   line</p>"))
        assertEquals("Fish & chips", HtmlText.toPlain("Fish &amp; chips"))
        assertEquals("It’s here", HtmlText.toPlain("It&#8217;s here"))
        assertEquals("It’s here", HtmlText.toPlain("It&#x2019;s here"))
        assertEquals("It’s here", HtmlText.toPlain("It&#146;s here"))
        assertEquals("Café", HtmlText.toPlain("Caf&eacute;"))
        assertEquals("", HtmlText.toPlain(null))
    }

    @Test
    fun comparisonsInTextAreNotMistakenForTags() {
        assertEquals("Rates stay < 5% and > 3%", HtmlText.toPlain("Rates stay < 5% and > 3%"))
        assertEquals("AT&T and R&D; unknown &bogus; stays", HtmlText.toPlain("AT&T and R&D; unknown &bogus; stays"))
    }

    @Test
    fun htmlEscapedTwiceIsStillCleaned() {
        assertEquals("Hello there", HtmlText.toPlain("&lt;p&gt;Hello &lt;i&gt;there&lt;/i&gt;&lt;/p&gt;"))
    }

    @Test
    fun longTextIsCutAtAWord() {
        val cut = HtmlText.toPlain("The quick brown fox jumps over the lazy dog again and again", 30)
        assertEquals("The quick brown fox jumps over…", cut)
    }

    @Test
    fun firstRealPictureIsFound() {
        assertEquals(
            "https://example.com/a.jpg?w=1&h=2",
            HtmlText.firstImage("""<img src="https://example.com/px.gif" width="1" height="1"><p>x</p><img class='hero' src='https://example.com/a.jpg?w=1&amp;h=2' width="100">"""),
        )
        assertNull(HtmlText.firstImage("""<img src="data:image/png;base64,AAAA">"""))
        assertNull(HtmlText.firstImage("<p>No pictures</p>"))
    }

    @Test
    fun rssDates() {
        val expected = at("2026-11-03T14:30:00Z")
        assertEquals(expected, DateParser.parse("Tue, 03 Nov 2026 14:30:00 GMT"))
        assertEquals(expected, DateParser.parse("Tue, 03 Nov 2026 09:30:00 -0500"))
        assertEquals(expected, DateParser.parse("  Tue,  3 Nov 2026 14:30:00 +0000 "))
        assertEquals(expected, DateParser.parse("03 Nov 2026 14:30:00 +00:00"))
        assertEquals(expected, DateParser.parse("Tue, 03 Nov 2026 14:30:00 Z"))
        assertEquals(expected, DateParser.parse("Tue, 03 Nov 2026 14:30 UTC"))
        assertEquals(expected, DateParser.parse("Tue, 03 Nov 26 14:30:00 GMT"))
        assertEquals(expected, DateParser.parse("3 November 2026 14:30:00 GMT"))
        // A wrong weekday (it happens) must not lose the date.
        assertEquals(expected, DateParser.parse("Fri, 03 Nov 2026 14:30:00 GMT"))
        assertEquals(at("2026-11-03T19:30:00Z"), DateParser.parse("Tue, 03 Nov 2026 14:30:00 EST"))
    }

    @Test
    fun atomDates() {
        val expected = at("2026-11-03T14:30:00Z")
        assertEquals(expected, DateParser.parse("2026-11-03T14:30:00Z"))
        assertEquals(expected, DateParser.parse("2026-11-03T15:30:00+01:00"))
        assertEquals(expected, DateParser.parse("2026-11-03T15:30:00+0100"))
        assertEquals(expected + 123, DateParser.parse("2026-11-03T14:30:00.123Z"))
        assertEquals(expected, DateParser.parse("2026-11-03T14:30:00"))
        assertEquals(expected, DateParser.parse("2026-11-03 14:30:00"))
        assertEquals(at("2026-11-03T00:00:00Z"), DateParser.parse("2026-11-03"))
    }

    @Test
    fun nonsenseIsNotADate() {
        assertNull(DateParser.parse(null))
        assertNull(DateParser.parse(""))
        assertNull(DateParser.parse("yesterday afternoon"))
        assertNull(DateParser.parse("Mon, 01 Jan 0001 00:00:00 GMT"))
    }

    @Test
    fun linksAreComparedWithoutTracking() {
        assertEquals("https://example.com/a/b?id=5", Links.normalize("http://www.Example.com/a/b/?utm_source=x&id=5#frag"))
        assertEquals(
            Links.articleId("https://www.bbc.com/news/articles/c123?at_medium=RSS&at_campaign=rss"),
            Links.articleId("https://bbc.com/news/articles/c123"),
        )
        assertEquals(16, Links.articleId("https://example.com/x").length)
        assertEquals("not a url at all", Links.normalize("not a url at all"))
    }

    @Test
    fun linksAreResolvedAndOnlyWebLinksSurvive() {
        val base = "https://example.com/feeds/news.xml"
        assertEquals("https://example.com/world/story", Links.resolve(base, "/world/story"))
        assertEquals("https://example.com/feeds/story", Links.resolve(base, "story"))
        assertEquals("https://cdn.example.com/a.jpg", Links.resolve(base, "//cdn.example.com/a.jpg"))
        assertEquals("http://other.org/x", Links.resolve(base, "http://other.org/x"))
        assertNull(Links.resolve(base, "javascript:alert(1)"))
        assertNull(Links.resolve(base, "mailto:someone@example.com"))
        assertNull(Links.resolve(base, "file:///etc/passwd"))
        assertNull(Links.resolve(base, "  "))
    }

    @Test
    fun typedAddressesAreTidied() {
        assertEquals("https://theverge.com", Links.cleanUserUrl(" theverge.com "))
        assertEquals("https://x.org/feed", Links.cleanUserUrl("http://x.org/feed"))
        assertEquals("https://x.org/feed", Links.cleanUserUrl("feed://x.org/feed"))
        assertNull(Links.cleanUserUrl("not a url"))
        assertNull(Links.cleanUserUrl("ftp://x.org/feed"))
        assertNull(Links.cleanUserUrl("localhost"))
        assertNull(Links.cleanUserUrl(""))
    }

    @Test
    fun feedIsDiscoveredFromAWebPage() {
        val html = """
            <html><head>
            <link rel="stylesheet" href="/site.css">
            <link href='/feed/?format=rss&amp;x=1' type='application/rss+xml' rel='alternate' title="News">
            </head><body></body></html>
        """.trimIndent()
        assertEquals("https://example.com/feed/?format=rss&x=1", FeedDiscovery.find(html, "http://example.com/"))
        assertNull(FeedDiscovery.find("<html><head><link rel='alternate' hreflang='fr' href='/fr'></head></html>", "https://example.com/"))
    }
}
