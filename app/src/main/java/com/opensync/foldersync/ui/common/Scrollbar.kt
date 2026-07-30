package com.opensync.foldersync.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/*
 * Interactive scrollbars. Compose ships none, so these draw a thumb on the trailing edge that you can
 * also grab and drag to fast-scroll. One overload per scrollable state type (LazyColumn,
 * LazyVerticalGrid, verticalScroll Column). The thumb is always faintly visible so it's discoverable,
 * and brightens while scrolling or dragging.
 */

private object Sb {
    val WIDTH = 5.dp
    val TOUCH = 28.dp   // grabbable strip on the right edge
    val GRAB_PAD = 10.dp // slack above/below the thumb so it's easy to catch
    const val MIN_THUMB = 28f
    const val IDLE_ALPHA = 0.32f
    const val ACTIVE_ALPHA = 0.72f
}

private data class SbMetrics(val thumbTop: Float, val thumbHeight: Float, val dragScale: Float)

fun Modifier.verticalScrollbar(state: LazyListState, width: Dp = Sb.WIDTH, color: Color = Color.Unspecified): Modifier =
    scrollbar({ state.isScrollInProgress }, { v -> lazyListMetrics(state, v) }, { d -> state.dispatchRawDelta(d) }, width, color)

fun Modifier.verticalScrollbar(state: LazyGridState, width: Dp = Sb.WIDTH, color: Color = Color.Unspecified): Modifier =
    scrollbar({ state.isScrollInProgress }, { v -> lazyGridMetrics(state, v) }, { d -> state.dispatchRawDelta(d) }, width, color)

fun Modifier.verticalScrollbar(state: ScrollState, width: Dp = Sb.WIDTH, color: Color = Color.Unspecified): Modifier =
    scrollbar({ state.isScrollInProgress }, { v -> scrollStateMetrics(state, v) }, { d -> state.dispatchRawDelta(d) }, width, color)

private fun Modifier.scrollbar(
    scrolling: () -> Boolean,
    metrics: (Float) -> SbMetrics?,
    dispatch: (Float) -> Unit,
    width: Dp,
    color: Color
): Modifier = composed {
    val barColor = if (color.isSpecified) color else MaterialTheme.colorScheme.onSurface
    var dragging by remember { mutableStateOf(false) }
    val target = if (scrolling() || dragging) Sb.ACTIVE_ALPHA else Sb.IDLE_ALPHA
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target > Sb.IDLE_ALPHA) 90 else 500),
        label = "scrollbarAlpha"
    )
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val touchPx = with(density) { Sb.TOUCH.toPx() }
    val grabPx = with(density) { Sb.GRAB_PAD.toPx() }

    this
        .pointerInput(Unit) {
            awaitEachGesture {
                // Claim on the Initial pass (before the list's own scroll) only when grabbing the thumb.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val m = metrics(size.height.toFloat()) ?: return@awaitEachGesture
                val onThumb = down.position.x >= size.width - touchPx &&
                    down.position.y >= m.thumbTop - grabPx &&
                    down.position.y <= m.thumbTop + m.thumbHeight + grabPx
                if (!onThumb || m.dragScale <= 0f) return@awaitEachGesture

                down.consume()
                dragging = true
                var lastY = down.position.y
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dy = change.position.y - lastY
                        lastY = change.position.y
                        change.consume()
                        if (dy != 0f) dispatch(dy * m.dragScale)
                        if (!change.pressed) break
                    }
                } finally {
                    dragging = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (alpha <= 0.01f) return@drawWithContent
            val m = metrics(size.height) ?: return@drawWithContent
            drawRoundRect(
                color = barColor.copy(alpha = alpha),
                topLeft = Offset(size.width - widthPx, m.thumbTop),
                size = Size(widthPx, m.thumbHeight),
                cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
            )
        }
}

private fun lazyListMetrics(state: LazyListState, viewport: Float): SbMetrics? {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val count = info.totalItemsCount
    if (visible.isEmpty() || count == 0) return null
    val avg = visible.sumOf { it.size }.toFloat() / visible.size
    val contentHeight = avg * count
    if (contentHeight <= viewport) return null
    val first = visible.first()
    val scrolled = (first.index * avg) - first.offset
    return thumb(scrolled, viewport, contentHeight)
}

private fun lazyGridMetrics(state: LazyGridState, viewport: Float): SbMetrics? {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val count = info.totalItemsCount
    if (visible.isEmpty() || count == 0) return null
    val first = visible.first()
    val columns = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
    val rowHeight = first.size.height.toFloat().coerceAtLeast(1f)
    val totalRows = ceil(count / columns.toFloat())
    val contentHeight = totalRows * rowHeight
    if (contentHeight <= viewport) return null
    val currentRow = first.index / columns
    val scrolled = (currentRow * rowHeight) - first.offset.y
    return thumb(scrolled, viewport, contentHeight)
}

private fun scrollStateMetrics(state: ScrollState, viewport: Float): SbMetrics? {
    if (state.maxValue == 0 || state.maxValue == Int.MAX_VALUE) return null
    val contentHeight = viewport + state.maxValue
    val scrolled = state.value.toFloat()
    return thumb(scrolled, viewport, contentHeight)
}

private fun thumb(scrolled: Float, viewport: Float, contentHeight: Float): SbMetrics {
    val thumbHeight = (viewport / contentHeight * viewport).coerceIn(Sb.MIN_THUMB, viewport)
    val maxTravel = viewport - thumbHeight
    val scrollable = contentHeight - viewport
    val fraction = (scrolled / scrollable).coerceIn(0f, 1f)
    val dragScale = if (maxTravel > 0f) scrollable / maxTravel else 0f
    return SbMetrics(thumbTop = fraction * maxTravel, thumbHeight = thumbHeight, dragScale = dragScale)
}
