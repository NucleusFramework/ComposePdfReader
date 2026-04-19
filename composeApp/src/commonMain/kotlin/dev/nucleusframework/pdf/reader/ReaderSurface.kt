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
 * Main reading area: continuous vertical scroll of pages + horizontal scroll when zoomed in,
 * with a sidebar scrollbar. Prefetches visible ±2 pages on scroll settle.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ReaderSurface(
    reader: PdfReaderState,
    listState: LazyListState,
    onOpenClick: () -> Unit,
    onViewportChange: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .onSizeChanged(onViewportChange),
    ) {
        when {
            reader.isLoading && reader.pageCount == 0 -> Spinner(Modifier.align(Alignment.Center))
            reader.pageCount == 0 -> ReaderEmptyState(onOpenClick = onOpenClick)
            else -> ContinuousReader(reader = reader, listState = listState)
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ContinuousReader(
    reader: PdfReaderState,
    listState: LazyListState,
) {
    val scrollAreaState = rememberScrollAreaState(listState)
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val pageWidth = (viewportWidth - 48.dp).coerceAtLeast(100.dp) * reader.renderScale
        val lazyWidth = if (pageWidth + 48.dp > viewportWidth) pageWidth + 48.dp else viewportWidth
        val pageWidthPx = with(density) { pageWidth.roundToPx() }

        PrefetchOnScroll(
            listState = listState,
            reader = reader,
            pageWidthPx = pageWidthPx,
        )

        Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
            ScrollArea(
                state = scrollAreaState,
                modifier = Modifier.width(lazyWidth).fillMaxHeight(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 20.dp, horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(
                        items = (0 until reader.pageCount).toList(),
                        key = { it },
                    ) { pageIndex ->
                        PageCard(
                            reader = reader,
                            pageIndex = pageIndex,
                            width = pageWidth,
                        )
                    }
                }
                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(10.dp)
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                ) {
                    Thumb(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.border),
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
    pageWidthPx: Int,
) {
    LaunchedEffect(listState, reader.pageCount, pageWidthPx) {
        if (reader.pageCount == 0 || pageWidthPx == 0) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            (info.firstOrNull()?.index ?: 0) to (info.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .debounce(150)
            .collect { (first, last) ->
                val range = (first - 2).coerceAtLeast(0)..(last + 2).coerceAtMost(reader.pageCount - 1)
                for (i in range) {
                    if (i in first..last) continue // visible pages render themselves
                    reader.prefetch(i, pageWidthPx.coerceAtMost(MAX_RENDER_WIDTH))
                }
            }
    }
}

@Composable
private fun PageCard(
    reader: PdfReaderState,
    pageIndex: Int,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
        AppText(
            "Page ${pageIndex + 1}",
            type.label,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            Modifier
                .width(width)
                .clip(shapes.small)
                .border(1.dp, colors.border, shapes.small)
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
}

private const val MAX_RENDER_WIDTH = 4096
