package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.VerticalScrollbar
import com.composables.core.rememberScrollAreaState
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.Spinner
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdfium.PdfPage
import dev.nucleusframework.pdfium.PdfReaderState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Main reading area: continuous vertical scroll of pages (one per row in Single mode, two
 * side-by-side in Double mode) + horizontal scroll when zoomed in, with a sidebar scrollbar.
 * Prefetches visible ±2 pages on scroll settle.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ReaderSurface(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
    onViewportChange: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(colors.pageBackdrop)
            .onSizeChanged(onViewportChange),
    ) {
        when {
            state.reader.isLoading && state.reader.pageCount == 0 -> Spinner(Modifier.align(Alignment.Center))
            state.reader.pageCount == 0 -> ReaderEmptyState(onOpenClick = onOpenClick)
            else -> ContinuousReader(state = state)
        }
    }
}

private val PAGE_GAP = 16.dp
private val CONTENT_HPADDING = 24.dp
private val CONTENT_VPADDING = 20.dp

@OptIn(FlowPreview::class)
@Composable
private fun ContinuousReader(state: ReaderScreenState) {
    val reader = state.reader
    val entries = state.spreadEntries
    val isDouble = state.spreadMode == SpreadMode.Double
    val scrollAreaState = rememberScrollAreaState(state.mainListState)
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        // At scale=1 a single page fills the viewport (minus side padding). In double mode we
        // fit two pages + one gap in the same space, so each page is half-width.
        val contentSlot = (viewportWidth - CONTENT_HPADDING * 2).coerceAtLeast(120.dp)
        val pageWidth = if (isDouble) {
            ((contentSlot - PAGE_GAP) / 2 * reader.renderScale).coerceAtLeast(60.dp)
        } else {
            (contentSlot * reader.renderScale).coerceAtLeast(80.dp)
        }
        val rowWidth = if (isDouble) pageWidth * 2 + PAGE_GAP else pageWidth
        val lazyWidth = if (rowWidth + CONTENT_HPADDING * 2 > viewportWidth) {
            rowWidth + CONTENT_HPADDING * 2
        } else {
            viewportWidth
        }
        val pageWidthPx = with(density) { pageWidth.roundToPx() }

        PrefetchOnScroll(
            listState = state.mainListState,
            reader = reader,
            entries = entries,
            pageWidthPx = pageWidthPx,
        )

        Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
            ScrollArea(
                state = scrollAreaState,
                modifier = Modifier.width(lazyWidth).fillMaxHeight(),
            ) {
                LazyColumn(
                    state = state.mainListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = CONTENT_VPADDING, horizontal = CONTENT_HPADDING),
                    verticalArrangement = Arrangement.spacedBy(PAGE_GAP),
                ) {
                    items(
                        items = entries,
                        key = { it.first },
                    ) { entry ->
                        SpreadRow(
                            reader = reader,
                            entry = entry,
                            pageWidth = pageWidth,
                            isDouble = isDouble,
                        )
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        // Pull the bar ~8dp away from the window edge so Linux/Windows
                        // invisible resize borders don't eat the hover (turns the cursor
                        // into the resize arrow and swallows clicks on the thumb).
                        .padding(top = 8.dp, bottom = 8.dp, end = 8.dp)
                        .width(10.dp),
                ) {
                    Thumb(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.muted.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun PrefetchOnScroll(
    listState: LazyListState,
    reader: PdfReaderState,
    entries: List<SpreadEntry>,
    pageWidthPx: Int,
) {
    LaunchedEffect(listState, reader.pageCount, pageWidthPx, entries.size) {
        if (entries.isEmpty() || pageWidthPx == 0) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            (info.firstOrNull()?.index ?: 0) to (info.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .debounce(150)
            .collect { (firstRow, lastRow) ->
                val prefetchStart = (firstRow - 2).coerceAtLeast(0)
                val prefetchEnd = (lastRow + 2).coerceAtMost(entries.lastIndex)
                for (row in prefetchStart..prefetchEnd) {
                    if (row in firstRow..lastRow) continue // visible rows render themselves
                    val entry = entries[row]
                    reader.prefetch(entry.first, pageWidthPx.coerceAtMost(MAX_RENDER_WIDTH))
                    entry.second?.let { reader.prefetch(it, pageWidthPx.coerceAtMost(MAX_RENDER_WIDTH)) }
                }
            }
    }
}

@Composable
private fun SpreadRow(
    reader: PdfReaderState,
    entry: SpreadEntry,
    pageWidth: Dp,
    isDouble: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isDouble) {
        Column(modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
            PageLabel(entry.first)
            PageCard(reader = reader, pageIndex = entry.first, width = pageWidth)
        }
        return
    }
    Column(modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PAGE_GAP)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PageLabel(entry.first)
                PageCard(reader = reader, pageIndex = entry.first, width = pageWidth)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (entry.second != null) {
                    PageLabel(entry.second)
                    PageCard(reader = reader, pageIndex = entry.second, width = pageWidth)
                } else {
                    // Phantom slot for odd last-page: keeps the row width stable.
                    Box(Modifier.width(pageWidth))
                }
            }
        }
    }
}

@Composable
private fun PageLabel(pageIndex: Int) {
    AppText(
        "Page ${pageIndex + 1}",
        type.label,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun PageCard(
    reader: PdfReaderState,
    pageIndex: Int,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .clip(shapes.medium)
            .border(1.dp, colors.border, shapes.medium)
            .background(Color.White),
    ) {
        PdfPage(
            state = reader,
            pageIndex = pageIndex,
            modifier = Modifier.fillMaxWidth(),
            background = Color.White,
            selectableText = true,
        )
    }
}

// Must match PdfPage.kt so the prefetch hits the same quantized width bucket the visible
// render uses. Bumping one without the other would fragment the cache.
private const val MAX_RENDER_WIDTH = 2048
