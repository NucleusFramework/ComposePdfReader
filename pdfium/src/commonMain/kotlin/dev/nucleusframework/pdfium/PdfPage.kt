package dev.nucleusframework.pdfium

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Render a PDF page with a progressive two-tier strategy (preview → full) and an optional
 * selectable-text overlay.
 *
 * When [selectableText] is true, a transparent text layer is laid on top of the rendered
 * bitmap — one [BasicText] per character, sized and positioned at the exact bounding box
 * reported by PDFium (`FPDFText_GetCharBox`). `SelectionContainer` wraps the overlay so
 * drag-selection hits the correct character pixel-for-pixel.
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfPage(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    background: Color = Color.White,
    selectableText: Boolean = false,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember(pageIndex, state.pageCount) { mutableStateOf<PageSize?>(null) }
    var bitmap by remember(pageIndex) { mutableStateOf<ImageBitmap?>(null) }
    var textLayout by remember(pageIndex, selectableText, state.pageCount) {
        mutableStateOf<PageTextLayout?>(null)
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        pageSize = state.pageSize(pageIndex)
    }

    if (selectableText) {
        LaunchedEffect(pageIndex, state.pageCount) {
            textLayout = state.pageTextLayout(pageIndex)
        }
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        snapshotFlow { size }
            .filter { it.width > 0 && it.height > 0 }
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MS)
            .collectLatest { currentSize ->
                val ps = pageSize ?: state.pageSize(pageIndex)?.also { pageSize = it } ?: return@collectLatest
                val fullWidth = currentSize.width.coerceIn(1, MAX_RENDER_WIDTH)
                val fullHeight = max(1, (fullWidth / ps.aspectRatio).roundToInt())
                if (bitmap == null) {
                    val previewWidth = (fullWidth / 4).coerceAtLeast(120)
                    val previewHeight = max(1, (previewWidth / ps.aspectRatio).roundToInt())
                    val preview = state.renderPage(
                        pageIndex = pageIndex,
                        widthPx = previewWidth,
                        heightPx = previewHeight,
                        quality = RenderQuality.PREVIEW,
                    )
                    if (preview != null) bitmap = preview
                }
                val full = state.renderPage(
                    pageIndex = pageIndex,
                    widthPx = fullWidth,
                    heightPx = fullHeight,
                    quality = RenderQuality.FULL,
                )
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
        val pl = textLayout
        if (selectableText && pl != null && pl.rectCount > 0) {
            SelectionContainer(Modifier.fillMaxSize()) {
                TextSelectionLayer(layout = pl, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Places one invisible [BasicText] per PDFium character, sized and stretched to that
 * character's exact bounding box (`FPDFText_GetCharBox`). This mirrors Chrome/PDF.js's
 * precision: the hit-test bounds of each char match the rendered glyph pixel-for-pixel,
 * so pointer position always maps to the exact character underneath.
 *
 * Cost: N composables per page where N = char count (typically 500-3000 for a text page).
 * Compose handles this fine for the 5-10 visible pages of a LazyColumn; for very dense
 * documents consumers can disable [PdfPage.selectableText] to opt out.
 */
/**
 * Places one [BasicText] per PDFium text rectangle (line-level run), sized to match the
 * rect's pixel bounds on screen. Hit-area = rect bounds → drag-selection always begins on
 * a line as long as the pointer is anywhere on that line. Character-level precision within
 * a line falls back to Compose's font metrics (imperfect) — the trade-off Chrome makes too.
 */
@Composable
private fun TextSelectionLayer(layout: PageTextLayout, modifier: Modifier = Modifier) {
    val style = remember {
        TextStyle(color = Color.Black.copy(alpha = 0.01f), fontSize = 12.sp)
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.roundToPx() }
        val containerH = with(density) { maxHeight.roundToPx() }
        val pageW = layout.pageSize.widthPoints
        val pageH = layout.pageSize.heightPoints
        if (pageW <= 0f || pageH <= 0f || containerW <= 0 || containerH <= 0) return@BoxWithConstraints
        val scaleX = containerW / pageW
        val scaleY = containerH / pageH
        for (i in 0 until layout.rectCount) {
            val text = layout.text(i)
            if (text.isEmpty()) continue
            val leftPts = layout.left(i)
            val rightPts = layout.right(i)
            val bottomPts = layout.bottom(i)
            val topPts = layout.top(i)
            val rectW = ((rightPts - leftPts) * scaleX).roundToInt().coerceAtLeast(1)
            val rectH = ((topPts - bottomPts) * scaleY).roundToInt().coerceAtLeast(1)
            val xPx = (leftPts * scaleX).roundToInt()
            val yPx = (containerH - topPts * scaleY).roundToInt().coerceAtLeast(0)
            val rectWDp = with(density) { rectW.toDp() }
            val rectHDp = with(density) { rectH.toDp() }
            BasicText(
                text = text,
                style = style,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(xPx, yPx) }
                    .size(rectWDp, rectHDp),
            )
        }
    }
}

private const val DEBOUNCE_MS = 100L
// 2048 px is enough for HiDPI displays (a 4K monitor renders a half-width page at ~1920 px).
// Dropping from 4096 quarters per-page bitmap bytes (bytes scale with width², since height is
// derived from aspect) and keeps the native-memory growth bounded when the user zooms in.
private const val MAX_RENDER_WIDTH = 2048
private const val DEFAULT_ASPECT = 595f / 842f // A4 portrait
