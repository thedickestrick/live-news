package com.livenews.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.livenews.app.LiveNewsApp
import com.livenews.app.MainActivity
import com.livenews.app.OpenLinkActivity
import com.livenews.app.R
import com.livenews.app.data.Article
import com.livenews.app.data.RefreshStatus
import com.livenews.app.ui.TimeFormat
import com.livenews.app.work.RefreshWorker

/**
 * The home-screen widget: a scrolling list of the newest headlines. It reads the same saved
 * headlines as the app, so it shows something the moment it is placed, even offline.
 */
class NewsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as LiveNewsApp).repository
        repository.ensureLoaded()
        provideContent {
            val articles by repository.articles.collectAsState()
            val status by repository.status.collectAsState()
            GlanceTheme {
                WidgetContent(articles.take(MAX_HEADLINES), status)
            }
        }
    }

    private companion object {
        const val MAX_HEADLINES = 40
    }
}

class NewsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NewsWidget()
}

/** The widget's refresh button. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
        // Keeps the widget listening while the refresh runs, so "Updating…" and the new headlines show as they happen.
        NewsWidget().update(context, glanceId)
    }
}

@Composable
internal fun WidgetContent(articles: List<Article>, status: RefreshStatus) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val subtitle = when {
        status.refreshing -> "Updating…"
        status.lastUpdatedAt == 0L -> "Tap refresh to load headlines"
        status.lastError != null -> "Offline · last updated ${TimeFormat.clock(context, now, status.lastUpdatedAt)}"
        else -> "Updated ${TimeFormat.clock(context, now, status.lastUpdatedAt)}"
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        ) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Text(
                    text = "Live News",
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = subtitle,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier
                    .size(44.dp)
                    .padding(11.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            )
        }

        if (articles.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            ) {
                Text(
                    text = if (status.refreshing) "Getting the latest headlines…" else "No headlines yet. Tap to refresh.",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
                )
            }
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(articles, itemId = { it.stableId }) { article ->
                    Headline(article, TimeFormat.clock(context, now, article.publishedAt))
                }
            }
        }
    }
}

@Composable
private fun Headline(article: Article, time: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .clickable(
                actionStartActivity<OpenLinkActivity>(
                    actionParametersOf(
                        OpenLinkActivity.UrlKey to article.link,
                        OpenLinkActivity.ArticleIdKey to article.id,
                    ),
                ),
            ),
    ) {
        Text(
            text = article.title,
            maxLines = 3,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            text = "${article.sourceName} · $time",
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
        )
    }
}
