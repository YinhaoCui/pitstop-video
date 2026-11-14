package app.pitstop.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.pitstop.video.R
import app.pitstop.video.core.ReorderMath
import app.pitstop.video.core.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for the rows of one LazyColumn. Call [bind] before the list each composition:
 * [first] is the lazy index of data row 0 (headers come before it), [count] the number of rows,
 * [onMove] commits a move (from, to) when the finger is lifted. While dragging, rows are only
 * translated, so nothing is saved until the drop.
 */
@Stable
class ReorderState internal constructor(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
) {
    var from by mutableStateOf<Int?>(null); private set
    private var to by mutableIntStateOf(0)
    private var offset by mutableFloatStateOf(0f)
    private var size = 0
    private var first = 0
    private var count = 0
    private var onMove: (Int, Int) -> Unit = { _, _ -> }
    private var scroller: Job? = null

    fun bind(first: Int, count: Int, onMove: (Int, Int) -> Unit) {
        this.first = first
        this.count = count
        this.onMove = onMove
    }

    fun move(from: Int, to: Int) { if (from != to && to in 0 until count) onMove(from, to) }

    internal fun start(index: Int) {
        val info = info(index) ?: return
        from = index
        to = index
        offset = 0f
        size = info.size
    }

    internal fun drag(dy: Float) {
        if (from == null) return
        offset += dy
        update()
        autoScroll()
    }

    internal fun end() {
        scroller?.cancel()
        val f = from ?: return
        val t = to
        if (f != t) onMove(f, t)
        from = null
        offset = 0f
    }

    /** Vertical offset to draw row [index] at while a drag is going on. */
    fun translation(index: Int): Float {
        val f = from ?: return 0f
        return if (index == f) offset else ReorderMath.shift(index, f, to) * size.toFloat()
    }

    private fun info(index: Int) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index + first }

    private fun update() {
        val f = from ?: return
        val info = info(f) ?: return
        val slots = list.layoutInfo.visibleItemsInfo
            .filter { it.index - first in 0 until count }
            .map { ReorderMath.Slot(it.index - first, it.offset, it.size) }
        to = ReorderMath.target(f, info.offset + offset, size, slots, count)
    }

    /**
     * Scrolls while the dragged row is held near the top or bottom edge. Stops before the row's own
     * layout slot would leave the list, since the list would then drop the row and end the drag.
     */
    private fun autoScroll() {
        if (scroller?.isActive == true) return
        scroller = scope.launch {
            while (true) {
                val f = from ?: break
                val info = info(f) ?: break
                val layout = list.layoutInfo
                val top = info.offset + offset
                val step = when {
                    top < layout.viewportStartOffset + edgePx -> -SCROLL_STEP
                    top + size > layout.viewportEndOffset - edgePx -> SCROLL_STEP
                    else -> break
                }
                val stillShown = if (step > 0) info.offset + info.size - step > layout.viewportStartOffset
                else info.offset - step < layout.viewportEndOffset
                if (!stillShown) break
                val used = list.scrollBy(step)
                if (used == 0f) break
                offset += used
                update()
                delay(16)
            }
        }
    }

    private companion object {
        const val SCROLL_STEP = 14f
    }
}

@Composable
fun rememberReorderState(list: LazyListState): ReorderState {
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(list) { ReorderState(list, scope, edge) }
}

/** Put on a row's outermost element: draws it where the drag says, lifted above the others while dragged. */
fun Modifier.reorderable(state: ReorderState, index: Int, background: Color): Modifier {
    val dragging = state.from == index
    return zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            translationY = state.translation(index)
            if (dragging) { shadowElevation = 8.dp.toPx(); scaleX = 1.02f; scaleY = 1.02f }
        }
        .then(if (dragging) Modifier.background(background) else Modifier)
}

/** The ⋮⋮ grip at the left of a row: drag it up or down. Screen readers get Move up / Move down actions. */
@Composable
fun DragHandle(state: ReorderState, index: Int, count: Int) {
    val haptics = LocalHapticFeedback.current
    val moveUp = S.moveUp
    val moveDown = S.moveDown
    Box(
        Modifier.size(40.dp)
            .pointerInput(state, index) {
                awaitEachGesture {
                    // The grip owns every touch that starts on it: consuming the down keeps the row's
                    // tap and long-press (the item menu) from firing when a finger rests here before dragging.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var overSlop = 0f
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        overSlop = over
                    } ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.start(index)
                    state.drag(overSlop)
                    verticalDrag(drag.id) { change ->
                        state.drag(change.positionChange().y)
                        change.consume()
                    }
                    state.end()
                }
            }
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { state.move(index, index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction(moveDown) { state.move(index, index + 1); true })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_drag_handle), S.dragToReorder, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
