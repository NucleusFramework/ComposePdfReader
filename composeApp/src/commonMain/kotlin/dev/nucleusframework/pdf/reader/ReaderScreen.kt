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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.pdf.design.HDivider
import dev.nucleusframework.pdf.design.ToastOverlay
import dev.nucleusframework.pdf.design.VDivider
import dev.nucleusframework.pdf.design.colors

/**
 * Top-level reader screen. Picks a wide or narrow layout based on the parent width,
 * renders the text-selection overlay and the transient toast. All state (file name,
 * current page, dialog, toast) lives on [state]; this composable is stateless beyond
 * what it forwards to the layout helpers.
 *
 * Layout stacks top-to-bottom: optional file header (mobile only) → controls bar (when
 * a document is loaded) → sidebar + reader row. The controls bar spans the full width so
 * the sidebar and the main reading area share a single top edge.
 */
@Composable
fun ReaderScreen(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFileHeader: Boolean = true,
    showControlsBar: Boolean = true,
) {
    LaunchedEffect(state.currentPage, state.reader.pageCount) {
        val visible = state.thumbListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in visible && state.reader.pageCount > 0) {
            // scrollToItem avoids composing every thumbnail between the old and new
            // positions (long jumps could render hundreds of thumbnails otherwise).
            state.thumbListState.scrollToItem(state.currentPage)
        }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isWide = maxWidth >= WIDE_BREAKPOINT
            val hasDocument = state.reader.pageCount > 0

            Column(Modifier.fillMaxSize()) {
                if (showFileHeader) {
                    ReaderFileHeader(
                        reader = state.reader,
                        fileName = state.fileName,
                        currentPage = state.currentPage,
                        onOpenClick = onOpenClick,
                    )
                    HDivider()
                }
                if (hasDocument && showControlsBar) {
                    ReaderControlsBar(
                        reader = state.reader,
                        spreadMode = state.spreadMode,
                        onToggleSpread = state::toggleSpreadMode,
                        onFitWidth = state::fitWidth,
                        onFitHeight = state::fitHeight,
                        onFitPage = state::fitPage,
                        compact = !isWide,
                    )
                    HDivider()
                }
                if (isWide) {
                    WideBody(state = state, onOpenClick = onOpenClick, showSidebar = hasDocument)
                } else {
                    NarrowBody(state = state, onOpenClick = onOpenClick, showThumbStrip = hasDocument)
                }
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
private fun WideBody(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
    showSidebar: Boolean,
) {
    Row(Modifier.fillMaxSize()) {
        if (showSidebar) {
            Box(
                Modifier
                    .width(SIDEBAR_WIDTH)
                    .fillMaxHeight()
                    .background(colors.surface),
            ) {
                ThumbnailPanel(
                    reader = state.reader,
                    listState = state.thumbListState,
                    currentPage = state.currentPage,
                    orientation = ThumbOrientation.Vertical,
                    onPageClick = state::jumpToPage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            VDivider()
        }
        Box(Modifier.fillMaxSize()) {
            ReaderSurface(
                state = state,
                onOpenClick = onOpenClick,
                onViewportChange = state::updateViewport,
            )
        }
    }
}

@Composable
private fun NarrowBody(
    state: ReaderScreenState,
    onOpenClick: () -> Unit,
    showThumbStrip: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ReaderSurface(
                state = state,
                onOpenClick = onOpenClick,
                onViewportChange = state::updateViewport,
            )
        }
        if (showThumbStrip) {
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
