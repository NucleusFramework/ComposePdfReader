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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a PDF page. The composable sizes itself via the [modifier] (pass a width constraint
 * to control how big the page is on screen) and renders a bitmap at roughly 2× its pixel size
 * for crisp output at the current zoom level. Changing [PdfReaderState.renderScale] by itself
 * does NOT resize the page — the caller is expected to multiply the modifier's width by scale
 * if a visual zoom effect is desired.
 */
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

    LaunchedEffect(pageIndex, size, state.pageCount) {
        val ps = pageSize ?: state.pageSize(pageIndex)?.also { pageSize = it } ?: return@LaunchedEffect
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val renderWidth = (size.width * DPI_MULTIPLIER).roundToInt().coerceIn(1, MAX_RENDER_WIDTH)
        val renderHeight = max(1, (renderWidth / ps.aspectRatio).roundToInt())
        val rendered = state.renderPage(pageIndex, renderWidth, renderHeight)
        if (rendered != null) bitmap = rendered
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

private const val DPI_MULTIPLIER = 1.5f
private const val MAX_RENDER_WIDTH = 4096
private const val DEFAULT_ASPECT = 595f / 842f // A4 portrait
