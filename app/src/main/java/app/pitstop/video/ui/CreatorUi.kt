package app.pitstop.video.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.Creator
import app.pitstop.video.core.ListView
import app.pitstop.video.core.Picked
import app.pitstop.video.core.S
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.CancellationException

/** One creator's latest videos, with Save (adds them as a quick feed chip, also on the car screen). */
@Composable
fun CreatorPage(graph: AppGraph, source: VideoSource, id: String, name: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val saved by graph.creators.creators.collectAsStateWithLifecycle()
    val isSaved = saved.any { it.source == source.id && it.id == id }
    var picked by remember(source.id, id) { mutableStateOf<Picked>(emptyMap()) }
    var view by remember(source.id, id) { mutableStateOf(ListView()) }
    var showSortFilter by remember { mutableStateOf(false) }
    if (showSortFilter) SortFilterDialog(
        S.sourceName(source.id), remember(source.id) { source.creatorOptions() }, picked, view,
        onApply = { p, v -> picked = p; view = v }, onDismiss = { showSortFilter = false },
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), S.back) }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val toggle = { graph.creators.toggle(Creator(source.id, id, name)) }
            if (isSaved) FilledTonalButton(onClick = toggle) { Text(S.savedCreator) }
            else OutlinedButton(onClick = toggle) { Text(S.saveCreator) }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            SortFilterChip(active = picked.isNotEmpty() || !view.isDefault) { showSortFilter = true }
        }
        // Endless: older videos load as the end of the list comes into view.
        PagedVideoList(
            graph, source, loadKey = listOf(source.id, id, picked),
            page = { cursor -> source.creatorPage(id, cursor, picked) },
            onFolder = {}, onCreator = null, onAdd = null, view = view,
        )
    }
}
