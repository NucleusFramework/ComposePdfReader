package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.VerticalScrollbar
import com.composables.core.rememberScrollAreaState
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdfium.PdfReaderState
import dev.nucleusframework.pdfium.PdfThumbnail

/** Layout orientation for [ThumbnailPanel]. */
internal enum class ThumbOrientation { Vertical, Horizontal }

/** List of thumbnail cells. Vertical → sidebar on large screens; Horizontal → bottom strip. */
@Composable
internal fun ThumbnailPanel(
    reader: PdfReaderState,
    listState: LazyListState,
    currentPage: Int,
    orientation: ThumbOrientation,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = (0 until reader.pageCount).toList()
    when (orientation) {
        ThumbOrientation.Vertical -> VerticalThumbnailPanel(
            pages = pages,
            reader = reader,
            listState = listState,
            currentPage = currentPage,
            onPageClick = onPageClick,
            modifier = modifier,
        )
        ThumbOrientation.Horizontal -> HorizontalThumbnailPanel(
            pages = pages,
            reader = reader,
            listState = listState,
            currentPage = currentPage,
            onPageClick = onPageClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun VerticalThumbnailPanel(
    pages: List<Int>,
    reader: PdfReaderState,
    listState: LazyListState,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    modifier: Modifier,
) {
    val scrollAreaState = rememberScrollAreaState(listState)
    ScrollArea(state = scrollAreaState, modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pages, key = { it }) { pageIndex ->
                ThumbnailCell(
                    reader = reader,
                    pageIndex = pageIndex,
                    selected = pageIndex == currentPage,
                    orientation = ThumbOrientation.Vertical,
                    onClick = { onPageClick(pageIndex) },
                )
            }
        }
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(8.dp)
                .padding(vertical = 6.dp, horizontal = 2.dp),
        ) {
            Thumb(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.border),
            )
        }
    }
}

@Composable
private fun HorizontalThumbnailPanel(
    pages: List<Int>,
    reader: PdfReaderState,
    listState: LazyListState,
    currentPage: Int,
    onPageClick: (Int) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(pages, key = { it }) { pageIndex ->
            ThumbnailCell(
                reader = reader,
                pageIndex = pageIndex,
                selected = pageIndex == currentPage,
                orientation = ThumbOrientation.Horizontal,
                onClick = { onPageClick(pageIndex) },
            )
        }
    }
}

@Composable
private fun ThumbnailCell(
    reader: PdfReaderState,
    pageIndex: Int,
    selected: Boolean,
    orientation: ThumbOrientation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbWidth = when (orientation) {
        ThumbOrientation.Vertical -> 140.dp
        ThumbOrientation.Horizontal -> 72.dp
    }
    val borderStroke = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) colors.accent else colors.border

    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(thumbWidth)
                .clip(shapes.small)
                .background(Color.White)
                .border(borderStroke, borderColor, shapes.small),
        ) {
            PdfThumbnail(reader, pageIndex)
        }
        Spacer(Modifier.height(4.dp))
        AppText(
            "${pageIndex + 1}",
            type.label.copy(color = if (selected) colors.onBackground else colors.muted),
        )
    }
}
