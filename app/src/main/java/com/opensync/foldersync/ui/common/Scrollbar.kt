package com.opensync.foldersync.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/*
 * Lightweight scrollbars. Compose ships none, so these draw a thumb on the trailing edge that
 * fades in while scrolling and fades out when idle — matching native Android scrollbars. One
 * overload per scrollable state type (LazyColumn, LazyVerticalGrid, verticalScroll Column).
 */

private const val FADE_IN_MS = 90
private const val FADE_OUT_MS = 500

private object Sb {
    val WIDTH = 4.dp
    const val MIN_THUMB = 24f  // px, so the thumb stays grabbable on very long lists
}

/** Scrollbar for a LazyColumn (uses the average visible item size to estimate content height). */
fun Modifier.verticalScrollbar(
    state: LazyListState,
    width: Dp = Sb.WIDTH,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val barColor = if (color.isSpecified) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val target = if (state.isScrollInProgress) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target == 1f) FADE_IN_MS else FADE_OUT_MS),
        label = "scrollbarAlpha"
    )
    drawWithContent {
        drawContent()
        if (alpha <= 0.01f) return@drawWithContent
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        val count = info.totalItemsCount
        if (visible.isEmpty() || count == 0) return@drawWithContent

        val avg = visible.sumOf { it.size }.toFloat() / visible.size
        val viewport = info.viewportSize.height.toFloat()
        val contentHeight = avg * count
        if (contentHeight <= viewport) return@drawWithContent

        val first = visible.first()
        val scrolled = (first.index * avg) - first.offset
        drawThumb(scrolled, viewport, contentHeight, width.toPx(), barColor, alpha)
    }
}

/** Scrollbar for a LazyVerticalGrid (estimates rows from the visible cells). */
fun Modifier.verticalScrollbar(
    state: LazyGridState,
    width: Dp = Sb.WIDTH,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val barColor = if (color.isSpecified) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val target = if (state.isScrollInProgress) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target == 1f) FADE_IN_MS else FADE_OUT_MS),
        label = "scrollbarAlpha"
    )
    drawWithContent {
        drawContent()
        if (alpha <= 0.01f) return@drawWithContent
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        val count = info.totalItemsCount
        if (visible.isEmpty() || count == 0) return@drawWithContent

        val first = visible.first()
        // Columns = number of visible cells sharing the top row's y offset.
        val columns = visible.count { it.offset.y == first.offset.y }.coerceAtLeast(1)
        val rowHeight = visible.first().size.height.toFloat().coerceAtLeast(1f)
        val viewport = info.viewportSize.height.toFloat()
        val totalRows = ceil(count / columns.toFloat())
        val contentHeight = totalRows * rowHeight
        if (contentHeight <= viewport) return@drawWithContent

        val currentRow = first.index / columns
        val scrolled = (currentRow * rowHeight) - first.offset.y
        drawThumb(scrolled, viewport, contentHeight, width.toPx(), barColor, alpha)
    }
}

/** Scrollbar for a Column/Row using verticalScroll (exact — ScrollState knows content extent). */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    width: Dp = Sb.WIDTH,
    color: Color = Color.Unspecified,
): Modifier = composed {
    val barColor = if (color.isSpecified) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val target = if (state.isScrollInProgress) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target == 1f) FADE_IN_MS else FADE_OUT_MS),
        label = "scrollbarAlpha"
    )
    drawWithContent {
        drawContent()
        if (alpha <= 0.01f || state.maxValue == 0 || state.maxValue == Int.MAX_VALUE) return@drawWithContent
        val viewport = size.height
        val contentHeight = viewport + state.maxValue
        val progress = state.value.toFloat() / state.maxValue
        // Convert progress (0..1 of scroll range) into scrolled pixels of content.
        val scrolled = progress * (contentHeight - viewport)
        drawThumb(scrolled, viewport, contentHeight, width.toPx(), barColor, alpha)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(
    scrolled: Float,
    viewport: Float,
    contentHeight: Float,
    widthPx: Float,
    color: Color,
    alpha: Float,
) {
    val thumbHeight = (viewport / contentHeight * viewport).coerceAtLeast(Sb.MIN_THUMB).coerceAtMost(viewport)
    val maxTravel = viewport - thumbHeight
    val fraction = (scrolled / (contentHeight - viewport)).coerceIn(0f, 1f)
    val top = fraction * maxTravel
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(size.width - widthPx, top),
        size = Size(widthPx, thumbHeight),
        cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
    )
}
