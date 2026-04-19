package dev.nucleusframework.pdfium

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Render a PDF page with a progressive two-tier strategy:
 *   1. On first composition, a low-resolution "preview" bitmap renders quickly and shows.
 *   2. A full-resolution bitmap renders in the background and replaces the preview.
 *
 * Size changes (zoom, container resize) are debounced to avoid thrashing during drag.
 * `collectLatest` cancels an in-flight full-quality render when the target size changes.
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfPage(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    background: Color = Color.White,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember(pageIndex, state.pageCount) { mutableStateOf<PageSize?>(null) }
    var bitmap by remember(pageIndex) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(pageIndex, state.pageCount) {
        pageSize = state.pageSize(pageIndex)
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        snapshotFlow { size }
            .filter { it.width > 0 && it.height > 0 }
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MS)
            .collectLatest { currentSize ->
                val ps = pageSize ?: state.pageSize(pageIndex)?.also { pageSize = it } ?: return@collectLatest
                // IntSize is in physical pixels — 1:1 with device pixels on Desktop/Android/iOS.
                val fullWidth = currentSize.width.coerceIn(1, MAX_RENDER_WIDTH)
                val fullHeight = max(1, (fullWidth / ps.aspectRatio).roundToInt())

                // Preview tier: only on first bitmap for this page. Subsequent zoom keeps the
                // old bitmap visible (stretched) until the full render lands — smoother UX.
                if (bitmap == null) {
                    val previewWidth = (fullWidth / 4).coerceAtLeast(120)
                    val previewHeight = max(1, (previewWidth / ps.aspectRatio).roundToInt())
                    val preview = state.renderPage(pageIndex, previewWidth, previewHeight)
                    if (preview != null) bitmap = preview
                }

                // Full tier: crisp final version.
                val full = state.renderPage(pageIndex, fullWidth, fullHeight)
                if (full != null) bitmap = full
            }
    }

    val aspect = pageSize?.aspectRatio ?: DEFAULT_ASPECT
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .background(background)
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private const val DEBOUNCE_MS = 100L
private const val MAX_RENDER_WIDTH = 4096
private const val DEFAULT_ASPECT = 595f / 842f // A4 portrait
