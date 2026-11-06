package com.livenews.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livenews.app.update.UpdateState

/** What to say about an update, shared by the banner on the headlines screen and the Settings row. */
data class UpdateText(
    val title: String,
    val detail: String?,
    /** Label for the main button, or null while nothing can be done (downloading, installing). */
    val action: String?,
)

fun updateText(state: UpdateState): UpdateText = when (state) {
    UpdateState.Idle -> UpdateText("", null, "Check now")
    UpdateState.Checking -> UpdateText("Checking for updates…", null, null)
    UpdateState.UpToDate -> UpdateText("You have the latest version.", null, "Check now")
    is UpdateState.Available -> UpdateText("Live News ${state.info.label} is available", state.info.notes.ifBlank { null }, "Update")
    is UpdateState.Downloading -> UpdateText(
        if (state.progress >= 0f) "Downloading Live News ${state.info.label}… ${(state.progress * 100).toInt()}%"
        else "Downloading Live News ${state.info.label}…",
        null,
        null,
    )
    is UpdateState.NeedsPermission -> UpdateText(
        "Allow Live News to install its update",
        "Android asks this once. Switch on “Allow from this source”, then come back here.",
        "Allow",
    )
    is UpdateState.Installing -> UpdateText("Installing Live News ${state.info.label}…", "Android may ask you to confirm. The app closes when it is done.", null)
    is UpdateState.Failed -> UpdateText(state.message, null, if (state.info != null) "Try again" else "Check now")
}

/** True when the headlines screen should show the banner: there is an update to act on. */
fun UpdateState.showsBanner(): Boolean = when (this) {
    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.NeedsPermission, is UpdateState.Installing -> true
    is UpdateState.Failed -> info != null
    else -> false
}

@Composable
fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = updateText(state)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (text.detail != null) {
                        Text(text.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (text.action != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) { Text("Later") }
                    FilledTonalButton(onClick = onUpdate) { Text(text.action) }
                    Spacer(Modifier.width(4.dp))
                }
            }
            if (state is UpdateState.Downloading) {
                val bar = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 8.dp)
                if (state.progress >= 0f) LinearProgressIndicator(progress = { state.progress }, modifier = bar) else LinearProgressIndicator(modifier = bar)
            }
        }
    }
}
