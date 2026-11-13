package app.pitstop.video.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.S
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.formatDuration
import kotlinx.coroutines.delay

/**
 * Music tab: one chip per music service, a sign-in card where an account unlocks more (shown
 * until signed in; managed later in Settings), then the service's feeds and search.
 */
@Composable
fun MusicTab(graph: AppGraph) {
    val sources = graph.musicSources
    var selected by rememberSaveable { mutableStateOf(sources.first().id) }
    val source = sources.firstOrNull { it.id == selected } ?: sources.first()

    val ytSignedIn by graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val biliLoggedIn by graph.biliCookies.loggedIn.collectAsStateWithLifecycle()
    val neteaseSignedIn by graph.neteaseCookies.signedIn.collectAsStateWithLifecycle()
    val driveSignedIn by graph.driveAccount.signedIn.collectAsStateWithLifecycle()
    val drivePicked by graph.driveSource.picked.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        SourceChips(sources.map { S.sourceName(it.id) }, sources.indexOf(source)) { selected = sources[it].id }
        val needsLogin = when (source.id) {
            SourceId.YT_MUSIC -> !ytSignedIn
            SourceId.BILI_MUSIC -> !biliLoggedIn
            SourceId.NETEASE -> !neteaseSignedIn
            SourceId.GDRIVE -> !driveSignedIn
            else -> false
        }
        if (needsLogin) MusicLoginCard(graph, source.id)
        // Without sign-in, Drive songs can still be picked one by one in the system file picker.
        if (source.id == SourceId.GDRIVE && (!driveSignedIn || drivePicked.isNotEmpty())) {
            DrivePickerCard(graph, hasFiles = drivePicked.isNotEmpty())
        }
        SourceTab(graph, source, refreshKey = "$ytSignedIn/$biliLoggedIn/$neteaseSignedIn/$driveSignedIn/${drivePicked.size}")
    }
}

/** Google Drive: songs are picked with the system file picker (Drive appears there), no sign-in. */
@Composable
private fun DrivePickerCard(graph: AppGraph, hasFiles: Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) graph.driveSource.addPicked(uris)
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!hasFiles) Text(S.drivePickHint, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text(S.driveAddSongs) }
            if (hasFiles) TextButton(onClick = graph.driveSource::clearPicked) { Text(S.driveClear) }
        }
    }
}

/** Which service / source to browse: a scrollable row of chips under the Video / Music switch. */
@Composable
fun SourceChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * The login portal for one music service: a one-line banner that opens the Settings sign-in
 * section in place, so the song list keeps its room on small screens.
 */
@Composable
private fun MusicLoginCard(graph: AppGraph, id: SourceId) {
    var open by rememberSaveable(id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = if (open) 14.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(S.signInFor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = true }) { Text(S.signIn) }
            }
            return@Column
        }
        when (id) {
            SourceId.NETEASE -> NeteaseSection(graph)
            SourceId.GDRIVE -> DriveSection(graph)
            SourceId.YT_MUSIC -> {
                Text(S.ytMusicSignedOut, style = MaterialTheme.typography.bodySmall)
                YouTubeSection(graph)
            }
            SourceId.BILI_MUSIC -> {
                Text(S.biliMusicLogin, style = MaterialTheme.typography.bodySmall)
                BilibiliSection(graph)
            }
            else -> Unit
        }
    }
}

/** Now Playing for songs: cover, title, artist and a seek bar. */
@Composable
fun MusicStage(graph: AppGraph, modifier: Modifier = Modifier) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    val item = s.current ?: return
    val art by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item, 600) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(item) {
        while (true) {
            position = graph.hub.player.currentPosition.coerceAtLeast(0)
            duration = graph.hub.player.duration.takeIf { it > 0 } ?: 0
            delay(500)
        }
    }

    Box(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(96.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    val a = art
                    if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(painterResource(R.drawable.ic_music), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    item.author?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(S.sourceName(item.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(2.dp))
            val fraction = dragging ?: if (duration > 0) position.toFloat() / duration else 0f
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { graph.hub.seekToFraction(it) }
                    dragging = null
                },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth()) {
                val shown = dragging?.let { (it * duration).toLong() } ?: position
                Text(formatDuration(shown / 1000).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(duration / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
