package com.livenews.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livenews.app.data.Categories
import com.livenews.app.data.Links
import com.livenews.app.data.Source
import com.livenews.app.update.UpdateState

private val foregroundChoices = listOf(1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min")
private val backgroundChoices = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 0 to "Off")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: NewsViewModel, onBack: () -> Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val grouped = remember(sources) {
        Categories.order.mapNotNull { category ->
            sources.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { category to it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { SectionHeader("Refresh") }
            item {
                Choice(
                    title = "While the app is open",
                    detail = "How often new headlines are fetched while you are reading.",
                    choices = foregroundChoices,
                    selected = settings.foregroundRefreshMinutes,
                    onSelect = vm::setForegroundMinutes,
                )
            }
            item {
                Choice(
                    title = "In the background",
                    detail = "Keeps the home-screen widget current. Android allows every 15 minutes at most, and may wait longer to save battery.",
                    choices = backgroundChoices,
                    selected = settings.backgroundRefreshMinutes,
                    onSelect = vm::setBackgroundMinutes,
                )
            }

            item { SectionHeader("Reading") }
            item {
                Toggle(
                    title = "Open stories inside the app",
                    detail = "Off opens them in your browser instead.",
                    checked = settings.openInApp,
                    onChange = vm::setOpenInApp,
                )
            }
            item { Toggle(title = "Show pictures", detail = null, checked = settings.showImages, onChange = vm::setShowImages) }

            item { SectionHeader("App updates") }
            item { UpdateRow(vm.versionName, update, onCheck = vm::checkForUpdate, onUpdate = vm::startUpdate) }
            item {
                Toggle(
                    title = "Check for updates automatically",
                    detail = "Looks for a new version when you open the app and offers to install it.",
                    checked = settings.autoUpdateCheck,
                    onChange = vm::setAutoUpdateCheck,
                )
            }

            item { SectionHeader("Sources") }
            item(key = "add-feed") { AddFeed(add = vm::addFeed) }
            grouped.forEach { (category, list) ->
                item(key = "category-$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.id }) { source ->
                    SourceRow(
                        source = source,
                        problem = status.failedSources[source.id].takeIf { source.enabled },
                        onToggle = { vm.setSourceEnabled(source.id, it) },
                        onRemove = { vm.removeSource(source.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(title: String, detail: String, choices: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            choices.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The installed version, what the updater is doing, and one button for whatever comes next. */
@Composable
private fun UpdateRow(versionName: String, state: UpdateState, onCheck: () -> Unit, onUpdate: () -> Unit) {
    val text = updateText(state)
    val checks = state is UpdateState.Idle || state is UpdateState.UpToDate || (state is UpdateState.Failed && state.info == null)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Version $versionName", style = MaterialTheme.typography.bodyLarge)
            val said = listOfNotNull(text.title.ifBlank { null }, text.detail).joinToString(" ")
            if (said.isNotEmpty()) {
                Text(
                    said,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(onClick = if (checks) onCheck else onUpdate, enabled = text.action != null) {
            Text(text.action ?: "Please wait")
        }
    }
}

/**
 * Type a site or feed address, press Add. The line underneath says what to type, then what went
 * wrong or what was added, so nothing here needs a pop-up.
 */
@Composable
private fun AddFeed(add: (String, (String?, String?) -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var addedName by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        if (busy || text.isBlank()) return
        busy = true
        addedName = null
        keyboard?.hide()
        add(text) { name, failure ->
            busy = false
            problem = failure
            if (failure == null) {
                addedName = name
                text = ""
            }
        }
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    problem = null
                },
                label = { Text("Add a feed") },
                placeholder = { Text("theverge.com") },
                singleLine = true,
                enabled = !busy,
                isError = problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Box(contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { submit() }, enabled = !busy && text.isNotBlank()) { Text("Add") }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        val added = addedName
        Text(
            text = when {
                problem != null -> problem.orEmpty()
                added != null -> "Added $added. It is listed under ${Categories.CUSTOM} below."
                else -> "Type a news site or its feed address. Any site with an RSS or Atom feed works."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

@Composable
private fun SourceRow(source: Source, problem: String?, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                problem ?: Links.host(source.url),
                style = MaterialTheme.typography.bodySmall,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (source.custom) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${source.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = source.enabled, onCheckedChange = onToggle)
    }
}
