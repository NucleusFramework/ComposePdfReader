package dev.nucleusframework.pdf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.VerticalScrollbar
import com.composables.core.rememberScrollAreaState
import com.composeunstyled.UnstyledButton as Button
import dev.nucleusframework.pdfium.PdfPage
import dev.nucleusframework.pdfium.PdfReaderState
import dev.nucleusframework.pdfium.rememberPdfReaderState
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
@Preview
fun App() {
    val reader = rememberPdfReaderState()
    val scope = rememberCoroutineScope()
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.Scroll) }

    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pdf")),
    ) { file: PlatformFile? ->
        if (file == null) return@rememberFilePickerLauncher
        pickedFileName = file.name
        scope.launch { reader.open(file.readBytes()) }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            HeaderBar(
                fileName = pickedFileName,
                pageCount = reader.pageCount,
                onOpen = { picker.launch() },
                viewMode = viewMode,
                onViewModeChange = { viewMode = it },
            )
            Spacer(Modifier.height(12.dp))
            MetadataPanel(reader)
            Spacer(Modifier.height(12.dp))
            ZoomBar(reader)

            reader.error?.let {
                Spacer(Modifier.height(8.dp))
                StyledText("Error: ${it.message}", type.body.copy(color = colors.error))
            }

            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, colors.border, shapes.medium),
            ) {
                when {
                    reader.isLoading -> Spinner(Modifier.align(Alignment.Center))
                    reader.pageCount == 0 -> EmptyState { picker.launch() }
                    viewMode == ViewMode.Scroll -> ScrollingReader(reader)
                    viewMode == ViewMode.Single -> SinglePageReader(reader)
                    viewMode == ViewMode.Text -> TextExtractionView(reader)
                }
            }
        }
    }
}

private enum class ViewMode(val label: String) {
    Scroll("Scroll"), Single("Single"), Text("Text")
}

// ----- Header -----

@Composable
private fun HeaderBar(
    fileName: String?,
    pageCount: Int,
    onOpen: () -> Unit,
    viewMode: ViewMode,
    onViewModeChange: (ViewMode) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(text = "Open PDF", onClick = onOpen)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            StyledText(
                fileName ?: "No file loaded",
                type.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pageCount > 0) {
                StyledText("$pageCount page${if (pageCount > 1) "s" else ""}", type.label)
            }
        }
        SegmentedBar(viewMode, onViewModeChange)
    }
}

@Composable
private fun SegmentedBar(viewMode: ViewMode, onChange: (ViewMode) -> Unit) {
    Row(
        Modifier
            .clip(shapes.small)
            .border(1.dp, colors.border, shapes.small)
            .background(colors.surface)
            .padding(3.dp),
    ) {
        ViewMode.values().forEach { mode ->
            Segment(label = mode.label, selected = mode == viewMode) { onChange(mode) }
        }
    }
}

// ----- Metadata -----

@Composable
private fun MetadataPanel(reader: PdfReaderState) {
    val m = reader.metadata
    val entries = listOf(
        "Title" to m.title, "Author" to m.author, "Subject" to m.subject,
        "Keywords" to m.keywords, "Creator" to m.creator, "Producer" to m.producer,
    ).filter { !it.second.isNullOrBlank() }
    if (entries.isEmpty()) return
    Panel {
        StyledText("Metadata", type.label)
        Spacer(Modifier.height(6.dp))
        entries.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                StyledText(k, type.body.copy(color = colors.muted), modifier = Modifier.width(96.dp))
                StyledText(v ?: "", type.body)
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shapes.medium)
            .background(colors.surface)
            .border(1.dp, colors.border, shapes.medium)
            .padding(12.dp),
    ) { content() }
}

// ----- Zoom -----

@Composable
private fun ZoomBar(reader: PdfReaderState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StyledText("Zoom", type.body)
        Spacer(Modifier.width(12.dp))
        MinimalSlider(
            value = reader.renderScale,
            onValueChange = { reader.renderScale = it },
            valueRange = 0.5f..4f,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .clip(shapes.small)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, shapes.small)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            StyledText("×${formatScale(reader.renderScale)}", type.body)
        }
    }
}

// ----- Empty state -----

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StyledText("No PDF loaded", type.subtitle)
        Spacer(Modifier.height(12.dp))
        GhostButton(text = "Pick a file", onClick = onOpen)
    }
}

// ----- Scrolling reader with proper scrollbar -----

@Composable
private fun ScrollingReader(reader: PdfReaderState) {
    val listState = rememberLazyListState()
    val scrollAreaState = rememberScrollAreaState(listState)
    val horizontalScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val pageWidth = (viewportWidth - 24.dp).coerceAtLeast(0.dp) * reader.renderScale
        val lazyWidth = if (pageWidth > viewportWidth) pageWidth + 24.dp else viewportWidth

        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScroll),
        ) {
            ScrollArea(
                state = scrollAreaState,
                modifier = Modifier.width(lazyWidth).fillMaxHeight(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items((0 until reader.pageCount).toList(), key = { it }) { pageIndex ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.CenterHorizontally),
                        ) {
                            StyledText(
                                "Page ${pageIndex + 1} / ${reader.pageCount}",
                                type.label,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            PdfPage(
                                state = reader,
                                pageIndex = pageIndex,
                                modifier = Modifier
                                    .width(pageWidth)
                                    .clip(shapes.small)
                                    .border(1.dp, colors.border, shapes.small),
                                background = Color.White,
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(10.dp)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
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

// ----- Single page reader -----

@Composable
private fun SinglePageReader(reader: PdfReaderState) {
    var current by remember { mutableStateOf(0) }
    LaunchedEffect(reader.pageCount) {
        current = current.coerceIn(0, (reader.pageCount - 1).coerceAtLeast(0))
    }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostButton(
                text = "◀",
                onClick = { if (current > 0) current-- },
                enabled = current > 0,
            )
            Spacer(Modifier.width(8.dp))
            StyledText(
                "Page ${current + 1} / ${reader.pageCount}",
                type.subtitle,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = "▶",
                onClick = { if (current < reader.pageCount - 1) current++ },
                enabled = current < reader.pageCount - 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = maxWidth * reader.renderScale
            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll),
                contentAlignment = Alignment.Center,
            ) {
                PdfPage(
                    state = reader,
                    pageIndex = current,
                    modifier = Modifier
                        .width(pageWidth)
                        .clip(shapes.small)
                        .border(1.dp, colors.border, shapes.small),
                    background = Color.White,
                )
            }
        }
    }
}

// ----- Text extraction -----

@Composable
private fun TextExtractionView(reader: PdfReaderState) {
    var extracted by remember(reader.pageCount) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val scrollAreaState = rememberScrollAreaState(scrollState)

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                text = if (working) "Extracting…" else "Extract all pages",
                enabled = !working && reader.pageCount > 0,
                onClick = {
                    working = true
                    scope.launch {
                        val b = StringBuilder()
                        for (i in 0 until reader.pageCount) {
                            b.append("── Page ").append(i + 1).append(" ──\n")
                            b.append(reader.pageText(i))
                            b.append("\n\n")
                        }
                        extracted = b.toString()
                        working = false
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            extracted?.let { StyledText("${it.length} chars", type.body.copy(color = colors.muted)) }
        }
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        val text = extracted
        if (text == null) {
            StyledText(
                "Extract Unicode text from every page with reader.pageText(i).",
                type.body.copy(color = colors.muted),
            )
        } else {
            ScrollArea(
                state = scrollAreaState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shapes.small)
                    .background(colors.surfaceRaised),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(10.dp),
                ) { StyledText(text, type.mono) }

                VerticalScrollbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(10.dp)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
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

// ---------- Atoms ----------

@Composable
private fun StyledText(
    text: String,
    style: TextStyle = type.body,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(text = text, style = style, modifier = modifier, maxLines = maxLines, overflow = overflow)
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .clip(shapes.small)
            .background(if (enabled) colors.accent else colors.border)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        StyledText(
            text,
            type.subtitle.copy(color = if (enabled) colors.accentContent else colors.muted),
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .clip(shapes.small)
            .border(1.dp, colors.border, shapes.small)
            .background(colors.surfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        StyledText(
            text,
            type.body.copy(color = if (enabled) colors.onBackground else colors.muted),
        )
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .clip(shapes.small)
            .background(if (selected) colors.accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        StyledText(
            label,
            type.subtitle.copy(
                color = if (selected) colors.accentContent else colors.muted,
            ),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
    )
    Box(
        modifier = modifier
            .size(28.dp)
            .rotate(angle)
            .border(2.dp, colors.accent, RoundedCornerShape(50))
            .padding(4.dp),
    )
}

// ---------- Slider ----------

@Composable
private fun MinimalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableStateOf(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(valueRange) {
                detectTapGestures { off ->
                    val next = (off.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + next * span)
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    val next = (change.position.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + next * span)
                }
            },
    ) {
        // Track
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .clip(shapes.small)
                .background(colors.border),
        )
        // Fill
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .clip(shapes.small)
                .background(colors.accent),
        )
        // Thumb
        val thumbOffsetPx = ((widthPx - density.run { 14.dp.roundToPx() }) * fraction).roundToInt()
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { androidx.compose.ui.unit.IntOffset(thumbOffsetPx, 0) }
                .size(14.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accent),
        )
    }
}

private fun formatScale(value: Float): String {
    val rounded = (value * 100f).toInt() / 100f
    return rounded.toString()
}
