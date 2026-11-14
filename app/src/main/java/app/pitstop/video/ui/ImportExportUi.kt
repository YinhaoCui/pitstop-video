package app.pitstop.video.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.Playlist
import app.pitstop.video.core.S
import app.pitstop.video.core.Settings
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.importer.ImportRead
import app.pitstop.video.importer.MatchOutcome
import app.pitstop.video.importer.PlaylistFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Platforms songs / videos can be matched on, in the default search order. */
private val MUSIC_MATCH = listOf(SourceId.NETEASE, SourceId.YT_MUSIC, SourceId.BILI_MUSIC, SourceId.SOUNDCLOUD, SourceId.BANDCAMP)
private val VIDEO_MATCH = listOf(SourceId.YOUTUBE, SourceId.BILIBILI)

/**
 * Import: paste a link or song list, or open a file → read → find each track on the chosen platforms
 * (several at a time) → name and save. Pitstop files are restored as they are.
 */
@Composable
fun ImportDialog(graph: AppGraph, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var music by remember { mutableStateOf(true) }
    val hidden = Settings.hiddenSources.value
    var matchOn by remember(music) { mutableStateOf((if (music) MUSIC_MATCH else VIDEO_MATCH).filter { it !in hidden }) }
    var phase by remember { mutableStateOf(0) } // 0 input, 1 reading, 2 matching / done
    var error by remember { mutableStateOf<String?>(null) }
    var read by remember { mutableStateOf<ImportRead?>(null) }
    val outcomes = remember { mutableStateListOf<MatchOutcome?>() }
    var name by remember { mutableStateOf("") }

    fun start(text: String) {
        error = null
        phase = 1
        scope.launch {
            try {
                // A Pitstop file restores whole playlists, no matching.
                graph.importer.pitstopFile(text)?.let { lists ->
                    lists.forEach { graph.playlists.create(it.name, it.items) }
                    error = S_importedPlaylists(lists.size)
                    phase = 0
                    return@launch
                }
                val r = withContext(Dispatchers.IO) { graph.importer.read(text, music) }
                read = r
                name = r.name
                outcomes.clear()
                repeat(r.toMatch.size) { outcomes.add(null) }
                phase = 2
                val gate = Semaphore(4)
                r.toMatch.mapIndexed { i, t ->
                    async(Dispatchers.IO) { gate.withPermit { outcomes[i] = graph.importer.match(t, matchOn) } }
                }.awaitAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                phase = 0
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { start(it) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.importTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), S.close) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                when (phase) {
                    0 -> {
                        Text(S.importHint, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(true to S.importAsMusic, false to S.importAsVideo).forEachIndexed { i, (m, label) ->
                                SegmentedButton(selected = music == m, onClick = { music = m }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text(label) }
                            }
                        }
                        Text(S.importMatchOn, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (music) MUSIC_MATCH else VIDEO_MATCH).forEach { id ->
                                FilterChip(
                                    selected = id in matchOn,
                                    onClick = { matchOn = if (id in matchOn) matchOn - id else (matchOn + id).sortedBy { (if (music) MUSIC_MATCH else VIDEO_MATCH).indexOf(it) } },
                                    label = { Text(S.sourceName(id)) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { start(input) }, enabled = input.isNotBlank() && matchOn.isNotEmpty()) { Text(S.importStart) }
                            OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }) { Text(S.importOpenFile) }
                        }
                    }
                    1 -> {
                        Text(S.importReading)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        val r = read ?: return@Column
                        val done = outcomes.count { it != null }
                        val found = outcomes.count { it?.item != null }
                        Text(S.importFrom(r.from), style = MaterialTheme.typography.labelLarge)
                        if (r.toMatch.isNotEmpty()) {
                            Text(if (done < r.toMatch.size) S.importMatching(done, r.toMatch.size) else S.importSummary(found, r.toMatch.size))
                            LinearProgressIndicator(progress = { if (r.toMatch.isEmpty()) 1f else done.toFloat() / r.toMatch.size }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(S.importName) })
                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(r.ready) { _, v -> ImportRow(v.title, v.author, S.sourceName(v.source), ok = true) }
                            itemsIndexed(r.toMatch) { i, t ->
                                val o = outcomes.getOrNull(i)
                                ImportRow(
                                    title = (t.artists.firstOrNull()?.let { "$it - " } ?: "") + t.title,
                                    sub = o?.item?.let { "→ ${it.title}" } ?: if (o != null) S.importNotMatched else "…",
                                    badge = o?.item?.source?.let(S::sourceName),
                                    ok = o?.item != null,
                                )
                            }
                        }
                        val items = r.ready + outcomes.mapNotNull { it?.item }
                        Button(
                            onClick = {
                                val p = graph.playlists.create(name.ifBlank { r.from }, items)
                                onSaved(p.id)
                            },
                            enabled = items.isNotEmpty() && done == r.toMatch.size,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(S.importSave) }
                    }
                }
            }
        }
    }
}

/** Composables can't read S outside composition; the count text for a restored Pitstop file. */
private fun S_importedPlaylists(n: Int) = app.pitstop.video.core.I18n.s.importedPlaylists(n)

@Composable
private fun ImportRow(title: String, sub: String?, badge: String?, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "·", Modifier.size(20.dp), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            sub?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        badge?.let { PlatformBadge(it) }
    }
}

/** Small platform label for rows that mix platforms (playlists, import results, search everywhere). */
@Composable
fun PlatformBadge(name: String) {
    Box(
        Modifier.padding(start = 6.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/** A link to open an item in its platform's own app / site (for CSV export). */
fun shareLink(v: VideoItem): String? = when (v.source) {
    SourceId.YOUTUBE, SourceId.YT_MUSIC, SourceId.SOUNDCLOUD, SourceId.BANDCAMP, SourceId.PEERTUBE -> v.id.takeIf { it.startsWith("http") }
    SourceId.BILIBILI, SourceId.BILI_MUSIC -> "https://www.bilibili.com/video/${v.id}" + (v.part?.let { "?p=$it" } ?: "")
    SourceId.NETEASE -> "https://music.163.com/song?id=${v.id}"
    else -> v.id.takeIf { it.startsWith("http") }
}

/**
 * Export menu for one playlist (or all, when [p] is null): Pitstop file saved with the system file
 * picker; CSV and text through the share sheet.
 */
@Composable
fun ExportMenu(graph: AppGraph, p: Playlist?, expanded: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val all = graph.playlists.playlists.value
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(PlaylistFiles.toPitstop(p?.let(::listOf) ?: all).toByteArray()) }
        }
    }
    fun share(text: String, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, p?.name ?: "Pitstop")
        ctx.startActivity(Intent.createChooser(send, null))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(S.exportPitstop) }, onClick = { onDismiss(); save.launch("${p?.name ?: "pitstop-playlists"}.pitstop.json") })
        if (p != null) {
            DropdownMenuItem(text = { Text(S.exportCsv) }, onClick = { onDismiss(); share(PlaylistFiles.toCsv(p, ::shareLink), "text/csv") })
            DropdownMenuItem(text = { Text(S.exportText) }, onClick = { onDismiss(); share(PlaylistFiles.toText(p), "text/plain") })
        }
    }
}
