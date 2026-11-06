package com.livenews.app.data

/** The built-in sources. Public RSS/Atom feeds only: no accounts, no API keys. */
object DefaultSources {

    private fun source(id: String, name: String, category: String, url: String, on: Boolean) =
        Source(id = id, name = name, url = url, category = category, enabled = on)

    val all: List<Source> = listOf(
        source("bbc-top", "BBC News", Categories.TOP, "https://feeds.bbci.co.uk/news/rss.xml", true),
        source("npr", "NPR", Categories.TOP, "https://feeds.npr.org/1001/rss.xml", true),

        source("cbs", "CBS News", Categories.US, "https://www.cbsnews.com/latest/rss/main", true),
        source("abc", "ABC News", Categories.US, "https://abcnews.go.com/abcnews/topstories", true),
        source("pbs", "PBS NewsHour", Categories.US, "https://www.pbs.org/newshour/feeds/rss/headlines", true),
        source("nbc", "NBC News", Categories.US, "https://feeds.nbcnews.com/nbcnews/public/news", false),
        source("nyt", "The New York Times", Categories.US, "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml", false),
        source("fox", "Fox News", Categories.US, "https://moxie.foxnews.com/google-publisher/latest.xml", false),
        source("politico", "Politico", Categories.US, "https://rss.politico.com/politics-news.xml", false),

        source("bbc-world", "BBC World", Categories.WORLD, "https://feeds.bbci.co.uk/news/world/rss.xml", true),
        source("guardian-world", "The Guardian", Categories.WORLD, "https://www.theguardian.com/world/rss", true),
        source("aljazeera", "Al Jazeera", Categories.WORLD, "https://www.aljazeera.com/xml/rss/all.xml", true),
        source("dw", "DW", Categories.WORLD, "https://rss.dw.com/rdf/rss-en-all", false),
        source("france24", "France 24", Categories.WORLD, "https://www.france24.com/en/rss", false),

        source("cnbc", "CNBC", Categories.BUSINESS, "https://www.cnbc.com/id/100003114/device/rss/rss.html", true),
        source("marketwatch", "MarketWatch", Categories.BUSINESS, "https://feeds.content.dowjones.io/public/rss/mw_topstories", true),
        source("bbc-business", "BBC Business", Categories.BUSINESS, "https://feeds.bbci.co.uk/news/business/rss.xml", false),
        source("fortune", "Fortune", Categories.BUSINESS, "https://fortune.com/feed/", false),

        source("ars", "Ars Technica", Categories.TECH, "https://feeds.arstechnica.com/arstechnica/index", true),
        source("verge", "The Verge", Categories.TECH, "https://www.theverge.com/rss/index.xml", true),
        source("techcrunch", "TechCrunch", Categories.TECH, "https://techcrunch.com/feed/", true),
        source("hn", "Hacker News", Categories.TECH, "https://hnrss.org/frontpage", false),
        source("engadget", "Engadget", Categories.TECH, "https://www.engadget.com/rss.xml", false),
        source("wired", "Wired", Categories.TECH, "https://www.wired.com/feed/rss", false),

        source("sciencedaily", "ScienceDaily", Categories.SCIENCE, "https://www.sciencedaily.com/rss/top/science.xml", true),
        source("nasa", "NASA", Categories.SCIENCE, "https://www.nasa.gov/news-release/feed/", true),
        source("bbc-science", "BBC Science", Categories.SCIENCE, "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", false),
        source("physorg", "Phys.org", Categories.SCIENCE, "https://phys.org/rss-feed/", false),

        source("espn", "ESPN", Categories.SPORTS, "https://www.espn.com/espn/rss/news", true),
        source("bbc-sport", "BBC Sport", Categories.SPORTS, "https://feeds.bbci.co.uk/sport/rss.xml", false),
        source("cbs-sports", "CBS Sports", Categories.SPORTS, "https://www.cbssports.com/rss/headlines/", false),
    )

    /**
     * The source list to use given what was saved on the phone. Built-in sources always come from
     * this file (so a corrected address reaches existing installs) and keep the user's on/off
     * choice; feeds the user added are kept as they are.
     */
    fun mergeWithStored(stored: List<Source>?): List<Source> {
        if (stored == null) return all
        val storedById = stored.associateBy { it.id }
        val builtIn = all.map { default -> storedById[default.id]?.let { default.copy(enabled = it.enabled) } ?: default }
        val builtInIds = all.mapTo(HashSet()) { it.id }
        return builtIn + stored.filter { it.custom && it.id !in builtInIds }
    }
}
