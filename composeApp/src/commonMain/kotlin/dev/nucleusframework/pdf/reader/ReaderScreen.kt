package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.HDivider
import dev.nucleusframework.pdf.design.ToastOverlay
import dev.nucleusframework.pdf.design.VDivider
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.type

/**
 * Top-level reader screen. Picks a wide or narrow layout based on the parent width,
 * renders the text-selection overlay and the transient toast. All state (file name,
 * current page, dialog, toast) lives on [state]; this composable is stateless beyond
 * what it forwards to the layout helpers.
 */
@Composable
fun ReaderScreen(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the thumbnail list scrolled to the current page.
    LaunchedEffect(state.currentPage, state.reader.pageCount) {
        val visible = state.thumbListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in visible && state.reader.pageCount > 0) {
            state.thumbListState.animateScrollToItem(state.currentPage)
        }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= WIDE_BREAKPOINT) {
                WideReaderLayout(state = state, onOpenClick = onOpenClick)
            } else {
                NarrowReaderLayout(state = state, onOpenClick = onOpenClick)
            }
        }

        state.textDialogPage?.let { idx ->
            TextSelectionDialog(
                reader = state.reader,
                pageIndex = idx,
                onDismiss = state::dismissTextDialog,
                onCopyAll = state::copyAndDismissText,
            )
        }

        ToastOverlay(message = state.toast, onExpire = state::clearToast)
    }
}

private val WIDE_BREAKPOINT: Dp = 760.dp
private val SIDEBAR_WIDTH: Dp = 176.dp
private val THUMB_STRIP_HEIGHT: Dp = 112.dp

@Composable
private fun WideReaderLayout(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(SIDEBAR_WIDTH)
                .fillMaxHeight()
                .background(colors.surface),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                AppText("Pages", type.label)
            }
            HDivider()
            ThumbnailPanel(
                reader = state.reader,
                listState = state.thumbListState,
                currentPage = state.currentPage,
                orientation = ThumbOrientation.Vertical,
                onPageClick = state::jumpToPage,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        VDivider()
        Column(Modifier.fillMaxHeight().weight(1f)) {
            ReaderTopBar(
                reader = state.reader,
                fileName = state.fileName,
                currentPage = state.currentPage,
                onOpenClick = onOpenClick,
                onFitWidth = state::fitWidth,
                onFitHeight = state::fitHeight,
                onFitPage = state::fitPage,
                compact = false,
            )
            HDivider()
            ReaderSurface(
                reader = state.reader,
                listState = state.mainListState,
                onOpenClick = onOpenClick,
                onViewportChange = state::updateViewport,
            )
        }
    }
}

@Composable
private fun NarrowReaderLayout(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ReaderTopBar(
            reader = state.reader,
            fileName = state.fileName,
            currentPage = state.currentPage,
            onOpenClick = onOpenClick,
            onFitWidth = state::fitWidth,
            onFitHeight = state::fitHeight,
            onFitPage = state::fitPage,
            compact = true,
        )
        HDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ReaderSurface(
                reader = state.reader,
                listState = state.mainListState,
                onOpenClick = onOpenClick,
                onViewportChange = state::updateViewport,
            )
        }
        if (state.reader.pageCount > 0) {
            HDivider()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(THUMB_STRIP_HEIGHT)
                    .background(colors.surface),
            ) {
                ThumbnailPanel(
                    reader = state.reader,
                    listState = state.thumbListState,
                    currentPage = state.currentPage,
                    orientation = ThumbOrientation.Horizontal,
                    onPageClick = state::jumpToPage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
