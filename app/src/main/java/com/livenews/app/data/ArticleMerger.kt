package com.livenews.app.data

/** Folds freshly fetched feeds into the saved headlines. Pure, so it is easy to test. */
object ArticleMerger {
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_ARTICLES = 600

    data class Merged(val articles: List<Article>, val added: Int)

    fun merge(
        existing: List<Article>,
        fetched: List<Pair<Source, ParsedFeed>>,
        sources: List<Source>,
        now: Long,
    ): Merged {
        val byId = LinkedHashMap<String, Article>(existing.size + 64)
        existing.forEach { byId[it.id] = it }
        var added = 0
        for ((source, feed) in fetched) {
            for (item in feed.items) {
                val id = Links.articleId(item.link)
                val old = byId[id]
                if (old != null) {
                    // Same story seen again: take corrections to the text, keep what the reader did with it.
                    byId[id] = old.copy(
                        title = item.title,
                        summary = item.summary.ifEmpty { old.summary },
                        imageUrl = item.imageUrl ?: old.imageUrl,
                        // A feed dated in the future would otherwise sit on top of the list for hours.
                        publishedAt = item.publishedAt?.coerceAtMost(now) ?: old.publishedAt,
                    )
                } else {
                    val published = (item.publishedAt ?: now).coerceAtMost(now)
                    if (now - published > MAX_AGE_MS) continue
                    byId[id] = Article(
                        id = id,
                        sourceId = source.id,
                        sourceName = source.name,
                        category = source.category,
                        title = item.title,
                        link = item.link,
                        summary = item.summary,
                        imageUrl = item.imageUrl,
                        publishedAt = published,
                        firstSeenAt = now,
                    )
                    added++
                }
            }
        }
        return Merged(prune(byId.values, sources, now), added)
    }

    /** Newest first, without stories that are too old or from sources that are switched off. Saved stories always stay. */
    fun prune(articles: Collection<Article>, sources: List<Source>, now: Long): List<Article> {
        val enabled = sources.filter { it.enabled }.mapTo(HashSet()) { it.id }
        var budget = MAX_ARTICLES
        return articles
            .filter { it.saved || (it.sourceId in enabled && now - it.publishedAt <= MAX_AGE_MS) }
            .sortedWith(
                compareByDescending<Article> { it.publishedAt }
                    .thenByDescending { it.firstSeenAt }
                    .thenBy { it.title },
            )
            .filter { it.saved || budget-- > 0 }
    }
}
