package dev.nucleusframework.pdfium

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PdfReader(
    state: PdfReaderState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    pageSpacing: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val listState = rememberLazyListState()
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
            )
        }
    }
}
