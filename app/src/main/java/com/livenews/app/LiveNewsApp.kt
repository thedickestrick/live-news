package com.livenews.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.livenews.app.data.FeedFetcher
import com.livenews.app.data.FeedSource
import com.livenews.app.data.NewsRepository
import com.livenews.app.data.NewsStore
import com.livenews.app.data.SettingsStore
import com.livenews.app.update.AppUpdater
import com.livenews.app.update.GitHubUpdateSource
import com.livenews.app.update.Installer
import com.livenews.app.update.PackageApkInstaller
import com.livenews.app.update.UpdateSource
import com.livenews.app.widget.NewsWidget
import com.livenews.app.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

open class LiveNewsApp : Application() {

    /** For jobs that must outlive the screen that started them (an update download, marking a story read from the widget). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore by lazy { SettingsStore(this) }

    val repository: NewsRepository by lazy {
        NewsRepository(NewsStore(File(filesDir, "news")), createFeedSource()).also { repo ->
            repo.onArticlesChanged = { refreshWidgets() }
        }
    }

    /** The app updating itself from its GitHub releases. */
    val updater: AppUpdater by lazy {
        AppUpdater(
            source = createUpdateSource(),
            installer = createInstaller(),
            packageName = packageName,
            currentVersionCode = BuildConfig.VERSION_CODE,
            downloadDir = File(cacheDir, "update"),
            scope = appScope,
        )
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // Lets sites answer "nothing changed" instead of sending the whole feed again.
            .cache(Cache(File(cacheDir, "feeds"), 10L * 1024 * 1024))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        scheduleBackgroundRefresh()
    }

    protected open fun createFeedSource(): FeedSource = FeedFetcher(httpClient)

    protected open fun createUpdateSource(): UpdateSource = GitHubUpdateSource(httpClient)

    protected open fun createInstaller(): Installer = PackageApkInstaller(this)

    protected open fun scheduleBackgroundRefresh() {
        RefreshWorker.schedule(this, settings.settings.value.backgroundRefreshMinutes, keepExisting = true)
    }

    protected open suspend fun refreshWidgets() {
        try {
            NewsWidget().updateAll(this)
        } catch (e: Exception) {
            // No widget on the home screen, or the launcher is busy: the next refresh updates it.
        }
    }
}
