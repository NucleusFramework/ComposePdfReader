package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.nucleusframework.pdf.design.GhostButton
import dev.nucleusframework.pdf.design.HDivider
import dev.nucleusframework.pdf.design.PrimaryButton
import dev.nucleusframework.pdf.design.Spinner
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdfium.PdfReaderState

/**
 * Modal overlay showing the extracted text of a single page in a [SelectionContainer] so
 * users can select sub-ranges and copy via keyboard shortcuts, plus a "Copy all" button.
 * Dismissing happens by clicking outside the panel (scrim) or the close button.
 */
@Composable
internal fun TextSelectionDialog(
    reader: PdfReaderState,
    pageIndex: Int,
    onDismiss: () -> Unit,
    onCopyAll: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(pageIndex) { mutableStateOf<String?>(null) }
    LaunchedEffect(pageIndex) {
        text = reader.pageText(pageIndex)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DialogPanel(
            pageIndex = pageIndex,
            text = text,
            onDismiss = onDismiss,
            onCopyAll = onCopyAll,
        )
    }
}

@Composable
private fun DialogPanel(
    pageIndex: Int,
    text: String?,
    onDismiss: () -> Unit,
    onCopyAll: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val scrollAreaState = rememberScrollAreaState(scrollState)
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .heightIn(max = 560.dp)
            .fillMaxWidth(0.9f)
            .clip(shapes.medium)
            .background(colors.surface)
            .border(1.dp, colors.border, shapes.medium)
            // Swallow clicks on the panel so the scrim's onClick doesn't fire.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("Page ${pageIndex + 1} text", type.title, modifier = Modifier.weight(1f))
            GhostButton(text = "Close", onClick = onDismiss)
        }
        HDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScrollArea(state = scrollAreaState, modifier = Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                ) {
                    when {
                        text == null -> Spinner(Modifier.align(Alignment.Center))
                        text.isBlank() -> AppText(
                            "No extractable text on this page.",
                            type.body.copy(color = colors.muted),
                        )
                        else -> SelectionContainer {
                            BasicText(
                                text = text,
                                style = type.mono.copy(color = colors.onBackground),
                            )
                        }
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
        HDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                if (text == null) "" else "${text.length} chars",
                type.body.copy(color = colors.muted),
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Copy all",
                enabled = !text.isNullOrBlank(),
                onClick = { text?.let(onCopyAll) },
            )
        }
    }
}
