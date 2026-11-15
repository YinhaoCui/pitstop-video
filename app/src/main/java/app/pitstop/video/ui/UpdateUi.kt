package app.pitstop.video.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.core.AppRelease
import app.pitstop.video.core.S
import app.pitstop.video.update.AppUpdater.State

/** Settings → App updates: installed version, check button, and the download / install progress. */
@Composable
fun UpdateSection(graph: AppGraph, inDialog: Boolean = false) {
    val updater = graph.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val auto by updater.autoCheck.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Back from "Install unknown apps": carry on if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.resumeAfterPermission() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!inDialog) Text(S.appUpdates, style = MaterialTheme.typography.titleMedium)
        Text(S.installedVersion(updater.currentVersion), style = MaterialTheme.typography.bodyMedium)
        when (val s = state) {
            State.Idle -> Unit
            State.Checking -> Text(S.checkingUpdates, style = MaterialTheme.typography.bodySmall)
            State.UpToDate -> Text(S.upToDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is State.Available -> ReleaseInfo(s.release)
            is State.Downloading -> {
                Text(S.downloadingUpdate(s.progress?.let { (it * 100).toInt() }), style = MaterialTheme.typography.bodySmall)
                if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is State.Installing -> Text(S.installingUpdate, style = MaterialTheme.typography.bodySmall)
            is State.NeedsPermission -> Text(S.updatePermission, style = MaterialTheme.typography.bodySmall)
            is State.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = state is State.Checking || state is State.Downloading
            val release = when (val s = state) {
                is State.Available -> s.release
                is State.Installing -> s.release
                is State.Failed -> s.release
                else -> null
            }
            when {
                state is State.NeedsPermission -> {
                    Button(onClick = { runCatching { context.startActivity(updater.permissionIntent()) } }) { Text(S.openSettings) }
                    OutlinedButton(onClick = updater::resumeAfterPermission) { Text(S.continueLabel) }
                }
                release != null -> {
                    Button(onClick = { updater.start(release) }) { Text(S.updateNow) }
                    OutlinedButton(onClick = { openPage(context, release) }) { Text(S.releasePage) }
                }
                else -> OutlinedButton(onClick = updater::startCheck, enabled = !busy) { Text(S.checkForUpdates) }
            }
        }
        if (!inDialog) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(S.updateAuto, style = MaterialTheme.typography.bodyLarge)
                Text(S.updateAutoText, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = auto, onCheckedChange = updater::setAutoCheck)
        }
    }
}

@Composable
private fun ReleaseInfo(release: AppRelease) {
    Text(S.updateAvailable(release.version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    if (release.notes.isNotBlank()) {
        Text(release.notes, style = MaterialTheme.typography.bodySmall, maxLines = 8)
    }
}

/**
 * The launch check (at most once a day, Settings → App updates → Check automatically): offers a new
 * version in a dialog. "Later" doesn't offer that version again; Settings still shows it.
 */
@Composable
fun UpdatePrompt(graph: AppGraph) {
    // Phone only: never a dialog on the car screen.
    if (LocalCarInstance.current) return
    val updater = graph.updater
    var offer by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) { offer = updater.checkIfDue() }
    val release = offer ?: return
    val state by updater.state.collectAsStateWithLifecycle()
    val started = state !is State.Available
    AlertDialog(
        onDismissRequest = { offer = null },
        title = { Text(S.updateTitle) },
        // The same view as Settings → App updates: download progress and the permission step show here too.
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                UpdateSection(graph, inDialog = true)
            }
        },
        confirmButton = { TextButton(onClick = { offer = null }) { Text(S.ok) } },
        dismissButton = {
            if (!started) TextButton(onClick = { updater.skip(release); offer = null }) { Text(S.later) }
        },
    )
}

private fun openPage(context: android.content.Context, release: AppRelease) {
    val url = release.pageUrl.ifEmpty { return }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
