package com.livenews.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livenews.app.BuildConfig
import com.livenews.app.LiveNewsApp
import com.livenews.app.update.UpdateState
import com.livenews.app.data.AppSettings
import com.livenews.app.data.Article
import com.livenews.app.data.Categories
import com.livenews.app.data.NewsRepository
import com.livenews.app.data.RefreshStatus
import com.livenews.app.data.Source
import com.livenews.app.work.RefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val FILTER_ALL = "All"
const val FILTER_SAVED = "Saved"

data class FeedUi(
    val loaded: Boolean = false,
    val articles: List<Article> = emptyList(),
    val categories: List<String> = listOf(FILTER_ALL),
    val category: String = FILTER_ALL,
    val query: String = "",
    /** Headlines in the current view that arrived since the reader was last at the top of the list. */
    val newCount: Int = 0,
    val anySourceOn: Boolean = true,
)

class NewsViewModel(private val app: LiveNewsApp) : ViewModel() {
    private val repo = app.repository

    private data class Filter(val category: String = FILTER_ALL, val query: String = "")

    private val filter = MutableStateFlow(Filter())

    /** Headlines first seen after this moment count as new. Nothing is new until the saved list has loaded. */
    private val seenUpTo = MutableStateFlow(Long.MAX_VALUE)
    private val _userRefreshing = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var lastAttemptAt = 0L

    val settings: StateFlow<AppSettings> = app.settings.settings
    val sources: StateFlow<List<Source>> = repo.sources
    val status: StateFlow<RefreshStatus> = repo.status

    /** True only for a refresh the reader asked for; the automatic ones stay quiet. */
    val userRefreshing: StateFlow<Boolean> = _userRefreshing.asStateFlow()

    /** One-line notices for the reader (shown as a snackbar). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val ui: StateFlow<FeedUi> =
        combine(repo.articles, repo.sources, repo.status, filter, seenUpTo) { articles, sources, status, filter, seen ->
            val enabled = sources.filter { it.enabled }
            val present = enabled.mapTo(HashSet()) { it.category }
            val categories = buildList {
                add(FILTER_ALL)
                // Second, not last: the row scrolls sideways and the end of it is off screen.
                add(FILTER_SAVED)
                addAll(Categories.order.filter { it in present })
            }
            val category = filter.category.takeIf { it in categories } ?: FILTER_ALL
            val query = filter.query.trim()
            val shown = articles.filter { article ->
                val inCategory = when (category) {
                    FILTER_ALL -> true
                    FILTER_SAVED -> article.saved
                    else -> article.category == category
                }
                inCategory && (
                    query.isEmpty() ||
                        article.title.contains(query, ignoreCase = true) ||
                        article.summary.contains(query, ignoreCase = true) ||
                        article.sourceName.contains(query, ignoreCase = true)
                    )
            }
            FeedUi(
                loaded = status.loaded,
                articles = shown,
                categories = categories,
                category = category,
                query = filter.query,
                newCount = shown.count { it.firstSeenAt > seen },
                anySourceOn = enabled.isNotEmpty(),
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUi())

    init {
        viewModelScope.launch {
            repo.ensureLoaded()
            acknowledgeNew()
        }
    }

    /** Runs for as long as the app is on screen: refreshes whenever the headlines are older than the interval. */
    suspend fun autoRefresh(intervalMinutes: Int) {
        val interval = intervalMinutes.coerceAtLeast(1) * 60_000L
        repo.ensureLoaded()
        while (true) {
            val last = maxOf(repo.status.value.lastUpdatedAt, lastAttemptAt)
            val wait = (last + interval - System.currentTimeMillis()).coerceAtMost(interval)
            if (wait > 0) delay(wait)
            lastAttemptAt = System.currentTimeMillis()
            try {
                repo.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again after the next interval.
            }
        }
    }

    fun refreshNow() {
        if (_userRefreshing.value) return
        _userRefreshing.value = true
        viewModelScope.launch {
            try {
                lastAttemptAt = System.currentTimeMillis()
                val outcome = repo.refresh()
                when {
                    outcome.total == 0 -> _messages.tryEmit("No sources are switched on. Pick some in Settings.")
                    outcome.failed == outcome.total -> _messages.tryEmit("Couldn't reach the news sources. Check your connection.")
                    outcome.failed > 0 -> _messages.tryEmit("${outcome.failed} of ${outcome.total} sources didn't answer.")
                    outcome.added == 0 -> _messages.tryEmit("You're up to date.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't refresh: ${NewsRepository.describe(e)}")
            } finally {
                _userRefreshing.value = false
            }
        }
    }

    /** The reader is looking at the top of the list, so everything in it has been seen. */
    fun acknowledgeNew() {
        if (!repo.status.value.loaded) return
        seenUpTo.value = repo.articles.value.maxOfOrNull { it.firstSeenAt } ?: 0L
    }

    fun setCategory(category: String) = filter.update { it.copy(category = category) }

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun markRead(article: Article) {
        if (!article.read) viewModelScope.launch { repo.markRead(article.id) }
    }

    fun toggleSaved(article: Article) {
        viewModelScope.launch { repo.setSaved(article.id, !article.saved) }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setSourceEnabled(sourceId, enabled)
            if (enabled) {
                lastAttemptAt = System.currentTimeMillis()
                repo.refresh()
            }
        }
    }

    fun removeSource(sourceId: String) {
        viewModelScope.launch { repo.removeSource(sourceId) }
    }

    /** [done] gets the new source's name, or a sentence saying why the feed could not be added. */
    fun addFeed(input: String, done: (name: String?, problem: String?) -> Unit) {
        viewModelScope.launch {
            try {
                done(repo.addSource(input).name, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(null, NewsRepository.describe(e))
            }
        }
    }

    fun setForegroundMinutes(minutes: Int) = app.settings.update { it.copy(foregroundRefreshMinutes = minutes) }

    fun setBackgroundMinutes(minutes: Int) {
        app.settings.update { it.copy(backgroundRefreshMinutes = minutes) }
        RefreshWorker.schedule(app, minutes, keepExisting = false)
    }

    // ---- The app updating itself ----

    val update: StateFlow<UpdateState> = app.updater.state

    /** "1.1.0", shown in Settings. */
    val versionName: String = BuildConfig.VERSION_NAME

    /** Runs each time the app comes on screen. */
    suspend fun onAppVisible(autoCheck: Boolean) {
        app.updater.onAppVisible()
        if (autoCheck) app.updater.checkIfDue()
    }

    fun checkForUpdate() {
        viewModelScope.launch { app.updater.check(quiet = false) }
    }

    fun startUpdate() = app.updater.startUpdate()

    fun dismissUpdate() = app.updater.dismiss()

    fun setAutoUpdateCheck(on: Boolean) = app.settings.update { it.copy(autoUpdateCheck = on) }

    fun setOpenInApp(inApp: Boolean) = app.settings.update { it.copy(openInApp = inApp) }

    fun setShowImages(show: Boolean) = app.settings.update { it.copy(showImages = show) }
}
