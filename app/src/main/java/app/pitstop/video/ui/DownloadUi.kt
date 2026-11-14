package app.pitstop.video.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.core.I18n
import app.pitstop.video.core.S
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoQuality
import app.pitstop.video.download.DownloadNames
import app.pitstop.video.download.DownloadTask
import kotlinx.coroutines.launch

/** Videos offer a resolution or sound only; songs and local files are downloaded as they are. */
private fun qualityChoices(item: VideoItem): List<VideoQuality> =
    if (item.source.music || item.source == SourceId.LOCAL) emptyList()
    else listOf(VideoQuality.AUTO, VideoQuality.P720, VideoQuality.P480, VideoQuality.P360, VideoQuality.AUDIO)

/**
 * Download one video or song: pick a format, then where to save it (this phone, Google Drive, or
 * both). Drive asks for `drive.file` access the first time.
 */
@Composable
fun DownloadDialog(graph: AppGraph, item: VideoItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = graph.downloads
    val choices = remember(item) { qualityChoices(item) }
    var quality by rememberSaveable { mutableStateOf(if (choices.isEmpty()) VideoQuality.AUDIO else VideoQuality.P720) }
    val local = item.source == SourceId.LOCAL || item.source == SourceId.LOCAL_MUSIC
    var toPhone by rememberSaveable { mutableStateOf(!local) }
    var toDrive by rememberSaveable { mutableStateOf(local) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start() {
        downloads.enqueue(item, quality, toPhone, toDrive)
        android.widget.Toast.makeText(context, I18n.s.downloadStarted, android.widget.Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { downloads.drive.account.completeSignIn(res.data) }
        if (downloads.drive.account.signedIn.value) start() else error = I18n.s.driveUploadSignIn
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.downloadTitle) },
        text = {
            Column {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (choices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(S.downloadFormat, style = MaterialTheme.typography.titleSmall)
                    choices.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { quality = q },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = quality == q, onClick = { quality = q })
                            Text(if (q == VideoQuality.AUDIO) S.downloadAudioOnly else S.downloadVideoP(q.maxHeight))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(S.downloadSaveTo, style = MaterialTheme.typography.titleSmall)
                if (!local) {
                    CheckRow(S.downloadToPhoneOnly, toPhone) { toPhone = it }
                    if (toPhone) PhoneFolderRow(graph, Modifier.padding(start = 48.dp))
                }
                CheckRow(S.downloadToDriveOnly, toDrive) { toDrive = it }
                if (toDrive) DriveFolderRow(graph, Modifier.padding(start = 48.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = toPhone || toDrive, onClick = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (!toDrive) { start(); return@TextButton }
                scope.launch {
                    runCatching { downloads.drive.account.beginSignIn() }
                        .onSuccess { pi ->
                            if (pi == null) start() else driveConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                        .onFailure { error = I18n.s.googleFailed(it.message) }
                }
            }) { Text(S.downloadStart) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Where phone downloads go (Download/Pitstop or a folder from the system picker), with Change / Default. */
@Composable
fun PhoneFolderRow(graph: AppGraph, modifier: Modifier = Modifier) {
    val downloads = graph.downloads
    val folder by downloads.phoneFolder.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { downloads.setPhoneFolder(uri) }.onFailure { error = it.message }
    }
    FolderRow(
        name = folder?.name ?: S.downloadPhoneDefault, isDefault = folder == null, error = error, modifier = modifier,
        onChange = { error = null; pick.launch(null) }, onDefault = { downloads.setPhoneFolder(null) },
    )
}

/** Where Drive uploads go (the app's Pitstop folder, or a folder picked in Google's Drive picker). */
@Composable
fun DriveFolderRow(graph: AppGraph, modifier: Modifier = Modifier) {
    val drive = graph.downloads.drive
    val folder by drive.chosenFolder.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            runCatching { drive.completePickFolder(res.data) }
                .onSuccess { if (!it) error = I18n.s.drivePickAFolder }
                .onFailure { error = it.message }
        }
    }
    FolderRow(
        name = folder?.name ?: S.downloadDriveDefault, isDefault = folder == null, error = error, modifier = modifier,
        onChange = {
            error = null
            scope.launch {
                runCatching { drive.beginPickFolder() }
                    .onSuccess { pick.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
                    .onFailure { error = I18n.s.googleFailed(it.message) }
            }
        },
        onDefault = { drive.setFolder(null) },
    )
}

@Composable
private fun FolderRow(
    name: String, isDefault: Boolean, error: String?, modifier: Modifier,
    onChange: () -> Unit, onDefault: () -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                S.downloadFolder(name), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onChange) { Text(S.downloadChangeFolder) }
            if (!isDefault) TextButton(onClick = onDefault) { Text(S.downloadDefaultFolder) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Settings → Downloads: every download of this session with its progress, cancel / retry / open. */
@Composable
internal fun DownloadsSection(graph: AppGraph) {
    val context = LocalContext.current
    val tasks by graph.downloads.tasks.collectAsStateWithLifecycle()
    Text(S.downloads, style = MaterialTheme.typography.titleLarge)
    Text(S.downloadFolders, style = MaterialTheme.typography.titleSmall)
    Text(S.downloadToPhoneOnly, style = MaterialTheme.typography.bodyMedium)
    PhoneFolderRow(graph)
    Text(S.downloadToDriveOnly, style = MaterialTheme.typography.bodyMedium)
    DriveFolderRow(graph)
    if (tasks.isEmpty()) {
        Text(S.downloadNothing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    tasks.asReversed().forEach { t ->
        Column(Modifier.fillMaxWidth()) {
            Text(t.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            val where = listOfNotNull(if (t.toPhone) S.downloadPhoneShort else null,
                if (t.toDrive) S.googleDrive else null).joinToString(" + ")
            Text(
                listOfNotNull(I18n.s.downloadStage(t.stage), t.error, where).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (t.stage == DownloadTask.Stage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!t.finished && t.stage != DownloadTask.Stage.QUEUED) {
                Spacer(Modifier.height(4.dp))
                if (t.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
            }
            Row {
                if (!t.finished) TextButton(onClick = { graph.downloads.cancel(t.id) }) { Text(S.cancel) }
                if (t.stage == DownloadTask.Stage.FAILED || t.stage == DownloadTask.Stage.CANCELLED) {
                    TextButton(onClick = { graph.downloads.retry(t.id) }) { Text(S.retry) }
                }
                val saved = t.savedUri
                if (t.stage == DownloadTask.Stage.DONE && saved != null) {
                    TextButton(onClick = {
                        val ext = t.fileName?.substringAfterLast('.', "").orEmpty()
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(saved), DownloadNames.mimeFor(ext))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text(S.downloadOpen) }
                }
            }
        }
    }
    if (tasks.any { it.finished }) {
        TextButton(onClick = graph.downloads::clearFinished) { Text(S.downloadClearFinished) }
    }
    Spacer(Modifier.width(0.dp))
}
