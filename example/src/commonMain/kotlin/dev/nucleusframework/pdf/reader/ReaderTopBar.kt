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
import dev.nucleusframework.pdf.design.SegmentedControl
import dev.nucleusframework.pdf.design.Spinner
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdfium.PdfReaderState
import kotlin.math.roundToInt

/**
 * File-header row: open button, document name + page counter, loading spinner. Used on
 * mobile; desktop hosts this content inside a Nucleus [MaterialTitleBar] instead.
 */
@Composable
fun ReaderFileHeader(
    reader: PdfReaderState,
    fileName: String?,
    currentPage: Int,
    onOpenClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryButton(text = "Open PDF", onClick = onOpenClick)
        Spacer(Modifier.width(14.dp))
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
}

/**
 * Full-width controls bar — zoom slider, single/spread toggle, fit actions. Rendered on
 * both desktop and mobile above the reader surface. Spans the whole app width so its
 * bottom divider aligns with the sidebar's top edge.
 */
@Composable
fun ReaderControlsBar(
    reader: PdfReaderState,
    spreadMode: SpreadMode,
    onToggleSpread: () -> Unit,
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ControlsRow(
            scale = reader.renderScale,
            onScaleChange = { reader.renderScale = it },
            spreadMode = spreadMode,
            onToggleSpread = onToggleSpread,
            onFitWidth = onFitWidth,
            onFitHeight = onFitHeight,
            onFitPage = onFitPage,
            compact = compact,
        )
        reader.error?.let {
            Spacer(Modifier.height(8.dp))
            AppText("Error: ${it.message}", type.body.copy(color = colors.error))
        }
    }
}

@Composable
private fun ControlsRow(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    spreadMode: SpreadMode,
    onToggleSpread: () -> Unit,
    onFitWidth: () -> Unit,
    onFitHeight: () -> Unit,
    onFitPage: () -> Unit,
    compact: Boolean,
) {
    if (compact) {
        Column {
            ZoomRow(scale, onScaleChange)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpreadToggle(spreadMode, onToggleSpread)
                Spacer(Modifier.width(10.dp))
                FitRow(onFitWidth, onFitHeight, onFitPage)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ZoomRow(scale, onScaleChange) }
            Spacer(Modifier.width(16.dp))
            SpreadToggle(spreadMode, onToggleSpread)
            Spacer(Modifier.width(14.dp))
            FitRow(onFitWidth, onFitHeight, onFitPage)
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
        Spacer(Modifier.width(8.dp))
        MinimalSlider(
            value = scale,
            onValueChange = onScaleChange,
            valueRange = ZOOM_MIN..ZOOM_MAX,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        GhostButton(text = "+", onClick = { onScaleChange((scale + 0.25f).coerceAtMost(ZOOM_MAX)) })
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .clip(shapes.small)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, shapes.small)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AppText("${(scale * 100).roundToInt()}%", type.body)
        }
    }
}

@Composable
private fun SpreadToggle(
    mode: SpreadMode,
    onToggle: () -> Unit,
) {
    SegmentedControl(
        options = listOf("Single" to SpreadMode.Single, "Spread" to SpreadMode.Double),
        selected = mode,
        onSelect = { if (it != mode) onToggle() },
    )
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
