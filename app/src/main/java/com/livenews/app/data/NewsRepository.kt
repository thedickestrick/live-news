package com.livenews.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The one place that owns sources and headlines. The app screens, the widget and the background job all read it. */
class NewsRepository(
    private val store: NewsStore,
    private val feeds: FeedSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateLock = Mutex()
    private val refreshLock = Mutex()
    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    private val _status = MutableStateFlow(RefreshStatus())
    private var lastOutcome = RefreshOutcome(0, 0, 0)
    private var refreshFinishedAt = 0L

    val articles: StateFlow<List<Article>> = _articles.asStateFlow()
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    /** Called after the headline list changed in a way the home-screen widget should show. */
    var onArticlesChanged: suspend () -> Unit = {}

    suspend fun ensureLoaded() {
        if (_status.value.loaded) return
        stateLock.withLock {
            if (_status.value.loaded) return
            val storedSources = withContext(Dispatchers.IO) { store.loadSources() }
            val stored = withContext(Dispatchers.IO) { store.loadArticles() }
            val sources = DefaultSources.mergeWithStored(storedSources)
            _sources.value = sources
            _articles.value = ArticleMerger.prune(stored.articles, sources, now())
            if (storedSources != sources) saveSources()
            _status.value = RefreshStatus(loaded = true, lastUpdatedAt = stored.updatedAt)
        }
    }

    /** Fetches every enabled source. Never throws for network trouble: failures are reported in [status]. */
    suspend fun refresh(): RefreshOutcome {
        ensureLoaded()
        val askedAt = now()
        refreshLock.withLock {
            // Someone else finished a refresh while this call waited its turn: that result is fresh enough.
            if (refreshFinishedAt > askedAt) return lastOutcome
            val active = _sources.value.filter { it.enabled }
            if (active.isEmpty()) {
                _status.update { it.copy(failedSources = emptyMap(), lastError = null) }
                return RefreshOutcome(0, 0, 0)
            }
            _status.update { it.copy(refreshing = true) }
            try {
                val limiter = Semaphore(MAX_PARALLEL)
                val results = coroutineScope {
                    active.map { source -> async { limiter.withPermit { source to fetchOne(source) } } }.awaitAll()
                }
                val time = now()
                val succeeded = results.mapNotNull { (source, result) -> result.getOrNull()?.let { source to it } }
                val failed = results
                    .mapNotNull { (source, result) -> result.exceptionOrNull()?.let { source.id to describe(it) } }
                    .toMap()
                var added = 0
                stateLock.withLock {
                    if (succeeded.isNotEmpty()) {
                        val merged = ArticleMerger.merge(_articles.value, succeeded, _sources.value, time)
                        added = merged.added
                        _articles.value = merged.articles
                    }
                    _status.update {
                        it.copy(
                            lastUpdatedAt = if (succeeded.isNotEmpty()) time else it.lastUpdatedAt,
                            failedSources = failed,
                            lastError = if (succeeded.isEmpty()) "Couldn't reach the news sources." else null,
                        )
                    }
                    if (succeeded.isNotEmpty()) saveArticles()
                }
                if (succeeded.isNotEmpty()) onArticlesChanged()
                lastOutcome = RefreshOutcome(total = active.size, failed = failed.size, added = added)
                refreshFinishedAt = now()
                return lastOutcome
            } finally {
                _status.update { it.copy(refreshing = false) }
            }
        }
    }

    suspend fun setSaved(articleId: String, saved: Boolean) = changeArticle(articleId) { it.copy(saved = saved) }

    suspend fun markRead(articleId: String) = changeArticle(articleId) { it.copy(read = true) }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        ensureLoaded()
        stateLock.withLock {
            val current = _sources.value
            if (current.none { it.id == sourceId && it.enabled != enabled }) return
            _sources.value = current.map { if (it.id == sourceId) it.copy(enabled = enabled) else it }
            saveSources()
            if (!enabled) {
                _articles.value = ArticleMerger.prune(_articles.value, _sources.value, now())
                saveArticles()
            }
        }
        if (!enabled) onArticlesChanged()
    }

    /** Adds a feed from what the user typed. Throws with a message fit to show them when it cannot. */
    suspend fun addSource(input: String): Source {
        ensureLoaded()
        val resolved = feeds.resolve(input)
        val key = Links.normalize(resolved.url)
        val source = stateLock.withLock {
            _sources.value.firstOrNull { Links.normalize(it.url) == key }?.let {
                throw IllegalStateException("${it.name} is already in your sources.")
            }
            val added = Source(
                id = "custom-" + Links.articleId(resolved.url),
                name = resolved.feed.title?.take(60) ?: Links.host(resolved.url),
                url = resolved.url,
                category = Categories.CUSTOM,
                enabled = true,
                custom = true,
            )
            _sources.value = _sources.value + added
            saveSources()
            _articles.value = ArticleMerger.merge(_articles.value, listOf(added to resolved.feed), _sources.value, now()).articles
            saveArticles()
            added
        }
        onArticlesChanged()
        return source
    }

    suspend fun removeSource(sourceId: String) {
        ensureLoaded()
        stateLock.withLock {
            val current = _sources.value
            if (current.none { it.id == sourceId && it.custom }) return
            _sources.value = current.filterNot { it.id == sourceId }
            saveSources()
            _articles.value = ArticleMerger.prune(_articles.value, _sources.value, now())
            saveArticles()
        }
        onArticlesChanged()
    }

    private suspend fun changeArticle(articleId: String, change: (Article) -> Article) {
        ensureLoaded()
        stateLock.withLock {
            val list = _articles.value
            val index = list.indexOfFirst { it.id == articleId }
            if (index < 0) return
            val changed = change(list[index])
            if (changed == list[index]) return
            _articles.value = list.toMutableList().also { it[index] = changed }
            saveArticles()
        }
    }

    private suspend fun fetchOne(source: Source): Result<ParsedFeed> = try {
        Result.success(feeds.fetch(source.url))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun saveSources() {
        val snapshot = _sources.value
        withContext(Dispatchers.IO) { store.saveSources(snapshot) }
    }

    private suspend fun saveArticles() {
        val snapshot = StoredArticles(updatedAt = _status.value.lastUpdatedAt, articles = _articles.value)
        withContext(Dispatchers.IO) { store.saveArticles(snapshot) }
    }

    companion object {
        private const val MAX_PARALLEL = 6

        /** A short reason a person can act on, for a failed fetch. */
        fun describe(error: Throwable): String = when (error) {
            is UnknownHostException -> "Couldn't reach the site. Check the address and your connection."
            is SocketTimeoutException -> "The site took too long to answer."
            is FeedParseException, is IllegalArgumentException, is IllegalStateException ->
                error.message ?: "That address is not a news feed."
            else -> error.message?.takeIf { it.isNotBlank() && it.length < 120 } ?: "Couldn't load this source."
        }
    }
}
