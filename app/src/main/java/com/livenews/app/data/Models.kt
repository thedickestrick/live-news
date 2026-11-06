package com.livenews.app.data

import kotlinx.serialization.Serializable

/** One RSS or Atom feed the app reads. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val enabled: Boolean = true,
    /** Added by the user (can be removed), as opposed to built in (can only be switched off). */
    val custom: Boolean = false,
)

@Serializable
data class Article(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val category: String,
    val title: String,
    val link: String,
    val summary: String = "",
    val imageUrl: String? = null,
    val publishedAt: Long,
    /** When this app first saw the article; drives the "new headlines" count. */
    val firstSeenAt: Long,
    val read: Boolean = false,
    val saved: Boolean = false,
) {
    /** The id as a number, for list rows that need a numeric key (the widget). */
    val stableId: Long get() = id.take(15).toLongOrNull(16) ?: id.hashCode().toLong()
}

data class RefreshStatus(
    /** False until the saved headlines and sources have been read from disk. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Last time at least one source answered. 0 = never. */
    val lastUpdatedAt: Long = 0L,
    /** Source id to a short reason, for the sources that failed in the last refresh. */
    val failedSources: Map<String, String> = emptyMap(),
    /** Set when the last refresh reached no source at all. */
    val lastError: String? = null,
)

data class RefreshOutcome(val total: Int, val failed: Int, val added: Int)

object Categories {
    const val TOP = "Top stories"
    const val US = "U.S."
    const val WORLD = "World"
    const val BUSINESS = "Business"
    const val TECH = "Tech"
    const val SCIENCE = "Science"
    const val SPORTS = "Sports"
    const val CUSTOM = "My feeds"

    val order = listOf(TOP, US, WORLD, BUSINESS, TECH, SCIENCE, SPORTS, CUSTOM)
}
