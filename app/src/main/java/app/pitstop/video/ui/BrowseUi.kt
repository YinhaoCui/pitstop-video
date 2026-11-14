package app.pitstop.video.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.Category
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.ListView
import app.pitstop.video.core.S
import app.pitstop.video.core.Settings
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State of an endless list: the first page is loading / failed / loaded (with the next page's cursor). */
internal sealed interface LoadState {
    data object Loading : LoadState
    /** [next]: cursor of the following page; [moreFailed]: loading it failed, offer a retry. */
    data class Loaded(val items: List<VideoItem>, val next: String? = null, val moreFailed: String? = null) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * An endless list of videos / songs / rooms for any source: loads [page] (cursor null first), then the
 * next page whenever the end scrolls into view. Pages that add nothing new are skipped; three in a row
 * end the list. [loadKey] restarts it (another feed, category, search ...).
 */
@Composable
internal fun PagedVideoList(
    graph: AppGraph,
    source: VideoSource,
    loadKey: Any,
    page: suspend (cursor: String?) -> FeedPage,
    onFolder: (VideoItem) -> Unit,
    onCreator: ((VideoItem) -> Unit)?,
    onAdd: ((VideoItem) -> Unit)?,
    modifier: Modifier = Modifier,
    /** In-app sort & filter of what has loaded (Sort & filter window). */
    view: ListView = ListView(),
) {
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    LaunchedEffect(loadKey, reload) {
        load = LoadState.Loading
        load = try {
            page(null).let { p -> LoadState.Loaded(p.items.distinctBy { it.id }, p.next) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    var moreTry by remember { mutableIntStateOf(0) }
    suspend fun loadMore(from: LoadState.Loaded) {
        val cursor = from.next ?: return
        load = try {
            val seen = from.items.mapTo(HashSet()) { it.id }
            var next: String? = cursor
            var fresh = emptyList<VideoItem>()
            var tries = 0
            while (next != null && fresh.isEmpty() && tries++ < 3) {
                val p = page(next)
                fresh = p.items.filter { seen.add(it.id) }
                next = p.next
            }
            LoadState.Loaded(from.items + fresh, next.takeIf { fresh.isNotEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            from.copy(moreFailed = e.message ?: e.javaClass.simpleName)
        }
    }

    when (val l = load) {
        LoadState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadState.Failed -> Column(modifier.fillMaxWidth().padding(24.dp)) {
            Text(l.message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { reload++ }) { Text(S.retry) }
        }
        is LoadState.Loaded -> {
          val shown = remember(l.items, view) { view.apply(l.items, source::isFolder) }
          if (shown.isEmpty() && l.next == null) {
            Column(modifier.padding(24.dp)) {
                Text(S.noVideos)
                if (l.items.isNotEmpty()) Text(S.hiddenByFilters(l.items.size), style = MaterialTheme.typography.bodySmall)
            }
          } else LazyColumn(modifier.fillMaxSize()) {
            val hidden = l.items.size - shown.size
            if (hidden > 0) item(key = "hidden") {
                Text(
                    S.hiddenByFilters(hidden), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(shown, key = { i, it -> "$i:${it.id}" }) { _, item ->
                val onClick = if (source.isFolder(item)) {
                    { onFolder(item) }
                } else {
                    {
                        // The queue follows what's shown: the sorted / filtered order.
                        val tracks = shown.filterNot(source::isFolder)
                        graph.hub.playQueue(tracks, tracks.indexOf(item))
                    }
                }
                val openCreator = onCreator?.takeIf { item.authorId != null && source.supportsCreators }?.let { { it(item) } }
                VideoRow(graph, item, onClick = onClick, onAuthor = openCreator, onLongClick = onAdd?.let { a -> { a(item) } }) {
                    if (onAdd != null) IconButton(onClick = { onAdd(item) }) {
                        Icon(painterResource(R.drawable.ic_playlist_add), S.addToPlaylist)
                    }
                }
            }
            if (l.next != null) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (l.moreFailed != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(l.moreFailed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { load = l.copy(moreFailed = null); moreTry++ }) { Text(S.retry) }
                        }
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        LaunchedEffect(l.next, moreTry) { loadMore(l) }
                    }
                }
            }
          }
        }
    }
}

/**
 * Search field with suggestions while typing (from the platform) and recent searches when empty.
 * [onSearch] runs the search (it is also saved to the recent list).
 */
@Composable
internal fun SearchField(source: VideoSource, query: String, onQuery: (String) -> Unit, onSearch: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val history by Settings.searchHistory.collectAsStateWithLifecycle()
    // Suggestions: wait for a short typing pause, then ask the platform.
    val suggestions by produceState(emptyList<String>(), source.id, query, focused) {
        value = emptyList()
        val q = query.trim()
        if (!focused || q.isEmpty()) return@produceState
        delay(250)
        value = runCatching { source.suggestions(q) }.getOrDefault(emptyList()).filter { it != q }.take(8)
    }
    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        onQuery(t)
        Settings.addSearch(t)
        onSearch(t)
        focused = false
    }
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            placeholder = { Text(S.searchIn(S.sourceName(source.id))) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), S.clearHistory) } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
        )
        if (focused && query.isBlank() && history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(S.recentSearches, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.forEach { h -> AssistChip(onClick = { submit(h) }, label = { Text(h, maxLines = 1) }) }
                TextButton(onClick = Settings::clearSearchHistory) { Text(S.clearHistory) }
            }
        }
        if (suggestions.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Column {
                    suggestions.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(10.dp))
                            Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Every category of a platform, grouped under headings, with a filter box (Twitch has hundreds of games). */
@Composable
internal fun CategoryDialog(
    graph: AppGraph,
    source: VideoSource,
    categories: List<Category>,
    selected: String?,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${S.sourceName(source.id)} · ${S.allCategories}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_close), S.done) }
                }
                if (categories.size > 12) OutlinedTextField(
                    value = filter, onValueChange = { filter = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text(S.filterCategories) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                )
                val shown = categories.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) || it.group?.contains(filter.trim(), ignoreCase = true) == true }
                if (shown.isEmpty()) Text(S.noCategories, Modifier.padding(24.dp))
                // Keep the platform's order; a heading whenever the group changes.
                val rows = remember(shown) {
                    buildList<Any> {
                        var group: String? = null
                        shown.forEach { c ->
                            if (c.group != null && c.group != group) add(c.group)
                            group = c.group
                            add(c)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        when (row) {
                            is String -> {
                                HorizontalDivider(Modifier.padding(top = 6.dp))
                                Text(row, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            is Category -> CategoryRow(graph, source, row, row.key == selected) { onPick(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(graph: AppGraph, source: VideoSource, c: Category, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        c.thumbnailUrl?.let { url ->
            val art by produceState<android.graphics.Bitmap?>(null, url) {
                value = graph.thumbnails.load(VideoItem(source.id, "cat:$url", c.title, thumbnailUrl = url), 96)
            }
            Box(Modifier.size(36.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.size(12.dp))
        }
        Text(c.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Search everywhere: the same query on every platform of this tab at once; each platform's top
 * results under its name, with More to open that platform's full (endless) results.
 */
@Composable
internal fun EverywhereResults(
    graph: AppGraph,
    sources: List<VideoSource>,
    query: String,
    onMore: (VideoSource) -> Unit,
    onFolder: (VideoSource, VideoItem) -> Unit,
) {
    val results = remember(query, sources) { androidx.compose.runtime.mutableStateMapOf<app.pitstop.video.core.SourceId, Result<List<VideoItem>>>() }
    LaunchedEffect(query, sources) {
        results.clear()
        kotlinx.coroutines.coroutineScope {
            sources.filter { it.supportsSearch }.forEach { s ->
                launch {
                    results[s.id] = try {
                        Result.success(kotlinx.coroutines.withTimeout(20_000) { s.search(query) }.take(5))
                    } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException) Result.failure(e) else throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        sources.filter { it.supportsSearch }.forEach { s ->
            item(key = "h:${s.id}") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(S.sourceName(s.id), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { onMore(s) }) { Text(S.moreResults) }
                }
            }
            val r = results[s.id]
            when {
                r == null -> item(key = "l:${s.id}") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                }
                r.isFailure -> item(key = "e:${s.id}") {
                    Text(r.exceptionOrNull()?.message ?: S.noVideos, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                r.getOrNull().isNullOrEmpty() -> item(key = "n:${s.id}") {
                    Text(S.noVideos, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    val items = r.getOrDefault(emptyList())
                    itemsIndexed(items, key = { i, it -> "${s.id}:$i:${it.id}" }) { _, item ->
                        VideoRow(graph, item, onClick = {
                            if (s.isFolder(item)) onFolder(s, item)
                            else {
                                val tracks = items.filterNot(s::isFolder)
                                graph.hub.playQueue(tracks, tracks.indexOf(item))
                            }
                        })
                    }
                }
            }
        }
    }
}
