package app.pitstop.video.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.formatDuration
import app.pitstop.video.local.LocalSource
import app.pitstop.video.player.DisplayMode
import kotlinx.coroutines.CancellationException

@Composable
fun AppRoot(graph: AppGraph, inPip: Boolean, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    if (inPip) {
        PhonePlayer(graph, Modifier.fillMaxSize(), showControls = false)
        return
    }
    if (fullscreen) {
        BackHandler { onFullscreenChange(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PhonePlayer(graph, Modifier.fillMaxSize(), showControls = true)
            IconButton(onClick = { onFullscreenChange(false) }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(painterResource(R.drawable.ic_split), contentDescription = "Exit full screen", tint = Color.White)
            }
        }
        return
    }

    val tabs = graph.sources.map { it.displayName } + "Playlists" + "Account"
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by graph.hub.state.collectAsStateWithLifecycle()
    // Re-read feed lists after sign-in / sign-out.
    val ytSignedIn by graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val biliLoggedIn by graph.biliCookies.loggedIn.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            if (state.current != null) {
                NowPlayingCard(graph, onFullscreen = { onFullscreenChange(true) })
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
            }
            if (tab < graph.sources.size) {
                val source = graph.sources[tab]
                SourceTab(graph, source, refreshKey = "$ytSignedIn/$biliLoggedIn")
            } else if (tab == graph.sources.size) {
                PlaylistsTab(graph)
            } else {
                AccountScreen(graph)
            }
        }
    }
}

@Composable
private fun NowPlayingCard(graph: AppGraph, onFullscreen: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    Column(Modifier.fillMaxWidth()) {
        if (s.carActive) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (s.drivingLocked) "Paused – car is moving" else "Playing on the car display",
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            PhonePlayer(graph, Modifier.fillMaxWidth().aspectRatio(16f / 9f), showControls = true)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.current?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                val sub = s.error ?: if (s.loading) "Loading…" else s.current?.author.orEmpty()
                Text(sub, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                    color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = graph.hub::togglePause) {
                Icon(painterResource(if (s.wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play), "Play/pause")
            }
            IconButton(onClick = graph.hub::next) { Icon(painterResource(R.drawable.ic_skip_next), "Next") }
            s.current?.let { cur ->
                IconButton(onClick = { adding = cur }) { Icon(painterResource(R.drawable.ic_playlist_add), "Add to playlist") }
            }
            if (s.carActive) {
                IconButton(onClick = graph.hub::toggleMode) {
                    Icon(painterResource(if (s.mode == DisplayMode.FULL) R.drawable.ic_split else R.drawable.ic_fullscreen), "Car layout")
                }
            } else {
                IconButton(onClick = onFullscreen) { Icon(painterResource(R.drawable.ic_fullscreen), "Full screen") }
            }
        }
    }
}

/** The phone's video view. It detaches while the car screen owns the video output. */
@OptIn(UnstableApi::class)
@Composable
fun PhonePlayer(graph: AppGraph, modifier: Modifier, showControls: Boolean) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx -> PlayerView(ctx).apply { setShowNextButton(false); setShowPreviousButton(false) } },
        update = { view ->
            view.useController = showControls
            val wanted = if (s.carActive) null else graph.hub.player
            if (view.player !== wanted) view.player = wanted
        },
        onRelease = { it.player = null },
    )
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val items: List<VideoItem>) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
private fun SourceTab(graph: AppGraph, source: VideoSource, refreshKey: String) {
    if (source is LocalSource) {
        var granted by remember { mutableStateOf(source.hasPermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = source.hasPermission()
        }
        if (!granted) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Allow access to videos stored on this phone to play them offline in the car.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                }) { Text("Allow video access") }
            }
            return
        }
    }

    val feeds = remember(source, refreshKey) { source.feeds() }
    var feedKey by rememberSaveable(source.id) { mutableStateOf(feeds.firstOrNull()?.key) }
    var query by rememberSaveable(source.id) { mutableStateOf("") }
    var activeQuery by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    val feed = feeds.firstOrNull { it.key == feedKey } ?: feeds.firstOrNull()

    val load by produceState<LoadState>(LoadState.Loading, source, feed, activeQuery, reload) {
        value = LoadState.Loading
        value = try {
            LoadState.Loaded(activeQuery?.let { source.search(it) } ?: feed?.let { source.load(it) } ?: emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (source.supportsSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search ${source.displayName}") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim().ifEmpty { null } }),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            feeds.forEach { f ->
                FilterChip(
                    selected = activeQuery == null && f.key == feed?.key,
                    onClick = {
                        activeQuery = null; query = ""
                        if (f.key == feed?.key) reload++ else feedKey = f.key
                    },
                    label = { Text(f.title) },
                )
            }
        }
        when (val l = load) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(l.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { reload++ }) { Text("Retry") }
            }
            is LoadState.Loaded -> if (l.items.isEmpty()) {
                Text("No videos", Modifier.padding(24.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(l.items, key = { i, it -> "$i:${it.id}" }) { i, item ->
                        VideoRow(graph, item, onClick = { graph.hub.playQueue(l.items, i) }) {
                            IconButton(onClick = { adding = item }) {
                                Icon(painterResource(R.drawable.ic_playlist_add), "Add to playlist")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoRow(graph: AppGraph, item: VideoItem, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val thumb by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(144.dp).aspectRatio(16f / 9f).background(Color(0xFF1F2937))) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            val d = formatDuration(item.durationSec)
            if (d.isNotEmpty()) Text(
                d, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAA000000)).padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            item.author?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}
