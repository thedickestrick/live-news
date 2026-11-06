package com.livenews.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.livenews.app.LinkOpener
import com.livenews.app.data.Article
import com.livenews.app.data.RefreshStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FRESH_MS = 30 * 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: NewsViewModel, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val userRefreshing by vm.userRefreshing.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var queryText by rememberSaveable { mutableStateOf("") }

    // Re-read the clock every half minute so "5m" and "Updated 2 min ago" stay true.
    val now by produceState(System.currentTimeMillis(), status.lastUpdatedAt) {
        value = System.currentTimeMillis()
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 24 }
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(atTop, ui.newCount) { if (atTop && ui.newCount > 0) vm.acknowledgeNew() }

    // A different filter or search is a different list: start it from the top. Done here, once the
    // new list is on screen, because the list otherwise stays anchored to the story it was showing.
    // Remembered across screens so that coming back from an article or Settings keeps your place.
    val shownFilter = ui.category + "\n" + ui.query
    var lastFilter by rememberSaveable { mutableStateOf(shownFilter) }
    LaunchedEffect(shownFilter) {
        if (shownFilter != lastFilter) {
            lastFilter = shownFilter
            listState.scrollToItem(0)
        }
    }

    fun closeSearch() {
        searching = false
        queryText = ""
        vm.setQuery("")
    }
    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    TopAppBar(
                        title = { FeedTitle(status, now) },
                        actions = {
                            IconButton(onClick = { if (searching) closeSearch() else searching = true }) {
                                Icon(
                                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = if (searching) "Close search" else "Search",
                                )
                            }
                            IconButton(onClick = vm::refreshNow) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                    if (searching) {
                        SearchField(
                            text = queryText,
                            onChange = {
                                queryText = it
                                vm.setQuery(it)
                            },
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("filters"),
                    ) {
                        items(ui.categories, key = { it }) { category ->
                            FilterChip(
                                selected = category == ui.category,
                                onClick = { vm.setCategory(category) },
                                label = { Text(category) },
                            )
                        }
                    }
                    if (update.showsBanner()) {
                        UpdateBanner(update, onUpdate = vm::startUpdate, onDismiss = vm::dismissUpdate)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = vm::refreshNow,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !ui.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                ui.articles.isEmpty() -> EmptyState(
                    when {
                        !ui.anySourceOn -> "No sources are switched on. Open Settings to pick some."
                        ui.query.isNotBlank() -> "No headlines match “${ui.query.trim()}”."
                        ui.category == FILTER_SAVED -> "Nothing saved yet. Tap the heart on a story to keep it here."
                        status.refreshing -> "Getting the latest headlines…"
                        status.lastError != null -> "Couldn't reach the news sources. Check your connection, then pull down to try again."
                        else -> "No headlines yet. Pull down to refresh."
                    },
                )

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(ui.articles, key = { it.id }) { article ->
                        ArticleRow(
                            article = article,
                            now = now,
                            showImage = settings.showImages,
                            onOpen = {
                                vm.markRead(article)
                                LinkOpener.open(context, article.link, settings.openInApp)
                            },
                            onShare = { LinkOpener.share(context, article.title, article.link) },
                            onToggleSaved = { vm.toggleSaved(article) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            AnimatedVisibility(
                visible = ui.newCount > 0 && !atTop,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) {
                Surface(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(0) }
                        vm.acknowledgeNew()
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = if (ui.newCount == 1) "1 new headline" else "${ui.newCount} new headlines",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedTitle(status: RefreshStatus, now: Long) {
    val subtitle = when {
        status.refreshing -> "Updating…"
        status.lastUpdatedAt == 0L -> if (status.lastError != null) "Offline" else "Not updated yet"
        status.lastError != null -> "Offline · updated ${TimeFormat.ago(now, status.lastUpdatedAt)}"
        else -> "Updated ${TimeFormat.ago(now, status.lastUpdatedAt)}"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(live = status.lastError == null)
            Spacer(Modifier.width(8.dp))
            Text("Live News", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Red and pulsing while headlines are coming in; grey when the last refresh reached nothing. */
@Composable
private fun LiveDot(live: Boolean) {
    if (live) {
        val pulse = rememberInfiniteTransition(label = "live")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(Color(0xFFE53935), CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search headlines") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun EmptyState(message: String) {
    // Scrollable so that pulling down to refresh still works on an empty list.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRow(
    article: Article,
    now: Long,
    showImage: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fresh = now - article.publishedAt < FRESH_MS
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onShare)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp),
    ) {
        Row(Modifier.padding(end = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (article.read) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (article.read) colors.onSurfaceVariant else colors.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (article.summary.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showImage && article.imageUrl != null) {
                Spacer(Modifier.width(12.dp))
                AsyncImage(
                    model = article.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium)) { append(article.sourceName) }
                    withStyle(SpanStyle(color = if (fresh) colors.primary else colors.onSurfaceVariant)) {
                        append("  ·  ")
                        append(TimeFormat.short(now, article.publishedAt))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Share",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleSaved) {
                Icon(
                    if (article.saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (article.saved) "Remove from saved" else "Save",
                    tint = if (article.saved) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
