package app.pitstop.video.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.pitstop.video.R
import app.pitstop.video.core.LengthFilter
import app.pitstop.video.core.ListSort
import app.pitstop.video.core.ListView
import app.pitstop.video.core.OptionGroup
import app.pitstop.video.core.Picked
import app.pitstop.video.core.S
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.normalized

/** The chip that opens Sort & filter; lit while anything differs from the defaults. */
@Composable
fun SortFilterChip(active: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = active, onClick = onClick,
        label = { Text(S.sortFilter, maxLines = 1) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(16.dp)) },
    )
}

/**
 * Sort & filter: first what the platform itself can do ([groups], e.g. YouTube's upload date), which
 * reloads the list; then what the app does with the loaded list ([view]: order, length, words,
 * platforms when [platforms] has more than one).
 */
@Composable
fun SortFilterDialog(
    platformName: String?,
    groups: List<OptionGroup>,
    picked: Picked,
    view: ListView,
    platforms: List<SourceId> = emptyList(),
    onApply: (Picked, ListView) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftPicked by remember { mutableStateOf(picked) }
    var draft by remember { mutableStateOf(view) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.sortFilter) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (groups.isNotEmpty()) {
                    Text(platformName?.let(S::onPlatform) ?: "", style = MaterialTheme.typography.titleSmall)
                    groups.forEach { g ->
                        val current = draftPicked[g.key] ?: g.default
                        ChipRow(g.title, g.options.map { it.key to it.title }, current) { draftPicked = draftPicked + (g.key to it) }
                    }
                    HorizontalDivider(Modifier.fillMaxWidth())
                }
                Text(S.inThisList, style = MaterialTheme.typography.titleSmall)
                ChipRow(
                    S.optSort,
                    listOf(
                        ListSort.DEFAULT to S.sortAsListed, ListSort.TITLE to S.sortTitle, ListSort.AUTHOR to S.sortAuthor,
                        ListSort.SHORTEST to S.sortShortest, ListSort.LONGEST to S.sortLongest,
                    ),
                    draft.sort,
                ) { draft = draft.copy(sort = it) }
                ChipRow(
                    S.optLength,
                    listOf(
                        LengthFilter.ANY to S.optAny, LengthFilter.SHORT to S.optUnderMin(4),
                        LengthFilter.MEDIUM to S.optMinRange(4, 20), LengthFilter.LONG to S.optOverMin(20),
                    ),
                    draft.length,
                ) { draft = draft.copy(length = it) }
                if (platforms.size > 1) {
                    Text(S.filterPlatforms, style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        platforms.forEach { p ->
                            val on = p in draft.platforms
                            FilterChip(selected = on, onClick = {
                                draft = draft.copy(platforms = if (on) draft.platforms - p else draft.platforms + p)
                            }, label = { Text(S.sourceName(p)) })
                        }
                    }
                }
                OutlinedTextField(
                    draft.text, { draft = draft.copy(text = it) }, Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text(S.filterTextHint) },
                )
                Spacer(Modifier.height(2.dp))
                Text(S.inThisListHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draftPicked.normalized(groups), draft); onDismiss() }) { Text(S.filterDone) }
        },
        dismissButton = {
            TextButton(onClick = { onApply(emptyMap(), ListView()); onDismiss() }) { Text(S.filterReset) }
        },
    )
}

@Composable
private fun <T> ChipRow(label: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, title) ->
            FilterChip(selected = key == selected, onClick = { onPick(key) }, label = { Text(title, maxLines = 1) })
        }
    }
}
