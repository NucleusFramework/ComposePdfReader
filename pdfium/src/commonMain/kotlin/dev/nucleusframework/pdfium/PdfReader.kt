package dev.nucleusframework.pdfium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Vertical list of pages. Links are clickable by default: external URIs open through the
 * platform handler, internal GoTo links scroll to the destination page. [onLinkClick] runs
 * first for every activated link — return `true` to consume it and skip default handling.
 */
@Composable
fun PdfReader(
    state: PdfReaderState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    pageSpacing: androidx.compose.ui.unit.Dp = 16.dp,
    linksEnabled: Boolean = true,
    onLinkClick: ((PdfLink) -> Boolean)? = null,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pages = (0 until state.pageCount).toList()
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(pageSpacing),
    ) {
        items(pages, key = { it }) { pageIndex ->
            PdfPage(
                state = state,
                pageIndex = pageIndex,
                modifier = Modifier.padding(horizontal = 0.dp),
                linksEnabled = linksEnabled,
                onLinkClick = { link ->
                    when {
                        onLinkClick?.invoke(link) == true -> true
                        link.destPageIndex in 0 until state.pageCount -> {
                            // scrollToItem teleports; animateScrollToItem would render every
                            // intermediate page on the way (slow for large jumps).
                            scope.launch { listState.scrollToItem(link.destPageIndex) }
                            true
                        }
                        else -> false
                    }
                },
            )
        }
    }
}
