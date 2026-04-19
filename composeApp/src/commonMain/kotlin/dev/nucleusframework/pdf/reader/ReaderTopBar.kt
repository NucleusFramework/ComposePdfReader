package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.GhostButton
import dev.nucleusframework.pdf.design.MinimalSlider
import dev.nucleusframework.pdf.design.PrimaryButton
import dev.nucleusframework.pdf.design.Spinner
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdfium.PdfReaderState
import kotlin.math.roundToInt

@Composable
internal fun ReaderTopBar(
    reader: PdfReaderState,
    fileName: String?,
    currentPage: Int,
    onOpenClick: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onFitPage: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(text = "Open PDF", onClick = onOpenClick)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AppText(
                    fileName ?: "No document",
                    type.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (reader.pageCount > 0) {
                    AppText("Page ${currentPage + 1} / ${reader.pageCount}", type.label)
                }
            }
            if (reader.isLoading) Spinner(Modifier.size(20.dp))
        }
        if (reader.pageCount > 0) {
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            ZoomRow(
                scale = reader.renderScale,
                onScaleChange = { reader.renderScale = it },
            )
            Spacer(Modifier.height(6.dp))
            FitRow(
                onFitWidth = onFitWidth,
                onFitHeight = onFitHeight,
                onFitPage = onFitPage,
            )
        }
        reader.error?.let {
            Spacer(Modifier.height(6.dp))
            AppText("Error: ${it.message}", type.body.copy(color = colors.error))
        }
    }
}

@Composable
private fun ZoomRow(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GhostButton(text = "−", onClick = { onScaleChange((scale - 0.25f).coerceAtLeast(ZOOM_MIN)) })
        Spacer(Modifier.width(6.dp))
        MinimalSlider(
            value = scale,
            onValueChange = onScaleChange,
            valueRange = ZOOM_MIN..ZOOM_MAX,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        GhostButton(text = "+", onClick = { onScaleChange((scale + 0.25f).coerceAtMost(ZOOM_MAX)) })
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(shapes.small)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, shapes.small)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            AppText("${(scale * 100).roundToInt()}%", type.body)
        }
    }
}

@Composable
private fun FitRow(
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onFitPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppText("Fit:", type.body.copy(color = colors.muted))
        Spacer(Modifier.width(8.dp))
        GhostButton(text = "Width", onClick = onFitWidth)
        Spacer(Modifier.width(6.dp))
        GhostButton(text = "Height", onClick = onFitHeight)
        Spacer(Modifier.width(6.dp))
        GhostButton(text = "Page", onClick = onFitPage)
    }
}
