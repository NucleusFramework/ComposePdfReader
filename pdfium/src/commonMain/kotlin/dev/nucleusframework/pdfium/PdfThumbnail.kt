package dev.nucleusframework.pdfium

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Low-resolution preview of a single page, sized to fill the incoming width. Renders at
 * [RenderQuality.PREVIEW] (no annotations, no LCD text) so the PDFium pass is cheap; the
 * bitmap is clamped between 60 and [MAX_THUMBNAIL_WIDTH] pixels to keep cache entries small.
 *
 * Clients typically wrap this in a clickable aspect-ratio container that serves as the
 * thumbnail in a sidebar/strip.
 */
@Composable
fun PdfThumbnail(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
) {
    var pageSize by remember(pageIndex, state.pageCount) { mutableStateOf<PageSize?>(null) }
    val handleState = remember(pageIndex) { mutableStateOf<CachedBitmap?>(null) }
    val handle = handleState.value
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(handleState) {
        onDispose {
            handleState.value?.release()
            handleState.value = null
        }
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        pageSize = state.pageSize(pageIndex)
    }

    LaunchedEffect(pageIndex, state.pageCount, containerSize) {
        if (containerSize.width == 0) return@LaunchedEffect
        val ps = pageSize ?: state.pageSize(pageIndex)?.also { pageSize = it } ?: return@LaunchedEffect
        val renderW = containerSize.width.coerceIn(60, MAX_THUMBNAIL_WIDTH)
        val renderH = max(1, (renderW / ps.aspectRatio).roundToInt())
        val next = state.renderThumbnail(pageIndex, renderW, renderH)
        if (next != null) {
            val old = handleState.value
            handleState.value = next
            old?.release()
        }
    }

    val aspect = pageSize?.aspectRatio ?: DEFAULT_ASPECT
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(background)
            .onSizeChanged { containerSize = it },
    ) {
        val bmp = handle?.imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private const val MAX_THUMBNAIL_WIDTH = 240
private const val DEFAULT_ASPECT = 595f / 842f
