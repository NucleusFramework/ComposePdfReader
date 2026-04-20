package dev.nucleusframework.pdfium

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Render a PDF page with a progressive two-tier strategy (preview → full) and an optional
 * pixel-precise text-selection overlay.
 *
 * When [selectableText] is true, an invisible selection layer is laid on top of the rendered
 * bitmap. Unlike Compose's built-in `SelectionContainer` (which uses its own font metrics),
 * selection here is driven directly by PDFium's per-character bounding boxes
 * (`FPDFText_GetCharBox`), so the hit region of every glyph matches the rendered pixels
 * pixel-for-pixel — the same approach Chrome and PDF.js use.
 */
@OptIn(FlowPreview::class)
@Composable
fun PdfPage(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    background: Color = Color.White,
    selectableText: Boolean = false,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember(pageIndex, state.pageCount) { mutableStateOf<PageSize?>(null) }
    var bitmap by remember(pageIndex) { mutableStateOf<ImageBitmap?>(null) }
    var textLayout by remember(pageIndex, selectableText, state.pageCount) {
        mutableStateOf<PageTextLayout?>(null)
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        pageSize = state.pageSize(pageIndex)
    }

    if (selectableText) {
        LaunchedEffect(pageIndex, state.pageCount) {
            textLayout = state.pageTextLayout(pageIndex)
        }
    }

    LaunchedEffect(pageIndex, state.pageCount) {
        snapshotFlow { size }
            .filter { it.width > 0 && it.height > 0 }
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MS)
            .collectLatest { currentSize ->
                val ps = pageSize ?: state.pageSize(pageIndex)?.also { pageSize = it } ?: return@collectLatest
                val fullWidth = currentSize.width.coerceIn(1, MAX_RENDER_WIDTH)
                val fullHeight = max(1, (fullWidth / ps.aspectRatio).roundToInt())
                if (bitmap == null) {
                    val previewWidth = (fullWidth / 4).coerceAtLeast(120)
                    val previewHeight = max(1, (previewWidth / ps.aspectRatio).roundToInt())
                    val preview = state.renderPage(
                        pageIndex = pageIndex,
                        widthPx = previewWidth,
                        heightPx = previewHeight,
                        quality = RenderQuality.PREVIEW,
                    )
                    if (preview != null) bitmap = preview
                }
                val full = state.renderPage(
                    pageIndex = pageIndex,
                    widthPx = fullWidth,
                    heightPx = fullHeight,
                    quality = RenderQuality.FULL,
                )
                if (full != null) bitmap = full
            }
    }

    val aspect = pageSize?.aspectRatio ?: DEFAULT_ASPECT
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .background(background)
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
        val pl = textLayout
        if (selectableText && pl != null && pl.charCount > 0) {
            TextSelectionLayer(layout = pl, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Pixel-precise text-selection overlay with multi-column and RTL support.
 *
 * Custom pointer handling replaces Compose's [androidx.compose.foundation.text.selection.SelectionContainer]:
 * the built-in container would route hit-testing through Compose's text layout, whose font
 * metrics cannot match the PDF's (different fonts, kerning, glyph widths).
 *
 * Pipeline per page:
 *  - [buildReadingOrder] sorts raw PDFium chars by position (y-band → column gap → x within
 *    column → RTL-reversed when the line is Hebrew/Arabic-dominant). PDFium's own char order
 *    is **not** trusted — 2-column PDFs and some Unicode-reordered PDFs emit chars in
 *    arbitrary order, so we re-derive reading order from geometry.
 *  - Selection anchor/cursor are positions in the **reading-order** array (not PDFium
 *    indices), so selecting a range visually corresponds to a contiguous reading range.
 *  - Hit-test matches the closest line by y, then the closest char by x inside that line.
 *  - Highlight is drawn as translucent rects over each selected char's exact PDFium box.
 */
@Composable
private fun TextSelectionLayer(layout: PageTextLayout, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember(layout) { FocusRequester() }
    var anchorPos by remember(layout) { mutableStateOf(-1) }
    var cursorPos by remember(layout) { mutableStateOf(-1) }

    val order = remember(layout) { buildReadingOrder(layout) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.roundToPx() }.toFloat()
        val containerH = with(density) { maxHeight.roundToPx() }.toFloat()
        val pageW = layout.pageSize.widthPoints
        val pageH = layout.pageSize.heightPoints
        if (pageW <= 0f || pageH <= 0f || containerW <= 0f || containerH <= 0f) return@BoxWithConstraints
        val scaleX = containerW / pageW
        val scaleY = containerH / pageH

        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val withModifier = event.isCtrlPressed || event.isMetaPressed
                    when {
                        withModifier && event.key == Key.C -> {
                            val a = anchorPos
                            val b = cursorPos
                            if (a >= 0 && b >= 0) {
                                val text = buildSelectedText(layout, order, min(a, b), max(a, b))
                                if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                            }
                            true
                        }
                        withModifier && event.key == Key.A -> {
                            if (order.size > 0) {
                                anchorPos = 0
                                cursorPos = order.size - 1
                            }
                            true
                        }
                        event.key == Key.Escape -> {
                            anchorPos = -1
                            cursorPos = -1
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(layout, scaleX, scaleY, containerH) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Mouse only: on touch, let LazyColumn scroll win.
                        if (down.type != PointerType.Mouse) return@awaitEachGesture

                        runCatching { focusRequester.requestFocus() }

                        val startPos = hitTestReadingPos(down.position, layout, order, scaleX, scaleY, containerH)
                        if (startPos >= 0) {
                            anchorPos = startPos
                            cursorPos = startPos
                        } else {
                            anchorPos = -1
                            cursorPos = -1
                        }
                        down.consume()

                        drag(down.id) { change ->
                            val pos = hitTestReadingPos(change.position, layout, order, scaleX, scaleY, containerH)
                            if (pos >= 0) cursorPos = pos
                            change.consume()
                        }
                    }
                }
                .drawBehind {
                    val a = anchorPos
                    val b = cursorPos
                    if (a < 0 || b < 0 || order.size == 0) return@drawBehind
                    val lo = min(a, b)
                    val hi = max(a, b)
                    // Use per-line expanded vertical bounds so the highlight stays visible
                    // even when PDFium reports near-zero char heights (Hebrew iText PDFs).
                    var currentLineIdx = order.lineOf(lo)
                    var currentLine = if (currentLineIdx >= 0) order.lines[currentLineIdx] else null
                    for (pos in lo..hi) {
                        if (pos < 0 || pos >= order.size) continue
                        val ln = order.lineOf(pos)
                        if (ln != currentLineIdx) {
                            currentLineIdx = ln
                            currentLine = if (ln >= 0) order.lines[ln] else null
                        }
                        val line = currentLine ?: continue
                        val pdfIdx = order.pdfiumOf(pos)
                        val leftPx = layout.charLeft(pdfIdx) * scaleX
                        val rightPx = layout.charRight(pdfIdx) * scaleX
                        val topPx = containerH - line.hitTop * scaleY
                        val bottomPx = containerH - line.hitBottom * scaleY
                        if (rightPx <= leftPx || bottomPx <= topPx) continue
                        drawRect(
                            color = SELECTION_COLOR,
                            topLeft = Offset(leftPx, topPx),
                            size = Size(rightPx - leftPx, bottomPx - topPx),
                        )
                    }
                },
        )
    }
}

// --- Reading-order construction -----------------------------------------------------------

/**
 * Canonical reading order: a contiguous position array mapping to PDFium char indices,
 * plus per-line slices so hit-testing can bisect by y then by x within a line.
 *
 * Building it in three passes:
 *  1. Y-band grouping (vertical overlap >50% of smaller glyph height).
 *  2. Column-gap split within each y-band (horizontal gap > 3× avg char width).
 *  3. Column-major reordering when the page looks like a 2-col layout.
 *
 * RTL support: within each line we count Hebrew/Arabic codepoints; if majority RTL, chars
 * are sorted descending x instead of ascending — so selecting a Hebrew word left-to-right
 * on screen yields chars in logical (memory) order, matching what text editors expect.
 */
@Immutable
private class ReadingOrder(
    private val sortedToPdfium: IntArray,
    val lines: Array<LineRange>,
) {
    val size: Int get() = sortedToPdfium.size
    fun pdfiumOf(pos: Int): Int = sortedToPdfium[pos]
    fun lineOf(pos: Int): Int {
        // Lines are in reading order; sortedRange is contiguous per line so binary search by start.
        var lo = 0
        var hi = lines.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val l = lines[mid]
            if (pos < l.start) hi = mid - 1
            else if (pos >= l.endExclusive) lo = mid + 1
            else return mid
        }
        return -1
    }
}

@Immutable
private class LineRange(
    val start: Int,
    val endExclusive: Int,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    // Expanded vertical bounds used for hit-testing and highlight drawing. Some PDFs
    // report char boxes with essentially zero height (Hebrew iText PDFs give ~0.008pt)
    // so we widen each line to fill half the gap to its spatial neighbors — Voronoi-
    // style — which gives a realistic clickable/paintable rectangle over every glyph.
    val hitTop: Float,
    val hitBottom: Float,
)

private class CharEntry(
    val pdfIdx: Int,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val cp: Int,
) {
    val cx: Float get() = (left + right) * 0.5f
    val cy: Float get() = (top + bottom) * 0.5f
    val width: Float get() = right - left
    val height: Float get() = top - bottom
}

private fun buildReadingOrder(layout: PageTextLayout): ReadingOrder {
    val n = layout.charCount
    if (n == 0) return ReadingOrder(IntArray(0), emptyArray())

    // Collect valid chars. Some PDFs (notably Hebrew PDFs produced by iText) report char
    // boxes with essentially zero height (~0.008pt) even though the rendered glyph is tall.
    // We keep those chars and expand per-line hit bounds below, otherwise the selection
    // surface would be invisible slivers and clicks on a glyph would miss every time.
    val chars = ArrayList<CharEntry>(n)
    for (i in 0 until n) {
        val cp = layout.codepoint(i)
        if (cp <= 0) continue
        val l = layout.charLeft(i)
        val r = layout.charRight(i)
        val b = layout.charBottom(i)
        val t = layout.charTop(i)
        if (r <= l || t < b) continue
        chars.add(CharEntry(i, l, r, t, b, cp))
    }
    if (chars.isEmpty()) return ReadingOrder(IntArray(0), emptyArray())

    // Step 1: sort top-to-bottom then left-to-right as a seed ordering.
    chars.sortWith(compareByDescending<CharEntry> { it.cy }.thenBy { it.left })

    // Step 2: group into y-bands by vertical overlap (>50%).
    val yBands = ArrayList<ArrayList<CharEntry>>()
    var band = arrayListOf(chars[0])
    var bandTop = chars[0].top
    var bandBot = chars[0].bottom
    for (k in 1 until chars.size) {
        val c = chars[k]
        val overlap = min(bandTop, c.top) - max(bandBot, c.bottom)
        val minH = min(bandTop - bandBot, c.height)
        if (minH > 0f && overlap > minH * 0.5f) {
            band.add(c)
            bandTop = max(bandTop, c.top)
            bandBot = min(bandBot, c.bottom)
        } else {
            yBands.add(band)
            band = arrayListOf(c)
            bandTop = c.top
            bandBot = c.bottom
        }
    }
    yBands.add(band)

    // Step 3: within each y-band, split on horizontal gaps and sort within each sub-line
    //         (column). Detect RTL majority per sub-line and flip x ordering if so.
    val subLines = ArrayList<ArrayList<CharEntry>>()
    for (b in yBands) {
        b.sortBy { it.left }
        val avgWidth = (b.sumOf { it.width.toDouble() } / b.size).toFloat().coerceAtLeast(1f)
        val gapThreshold = avgWidth * COLUMN_GAP_MULT
        var cur = arrayListOf(b[0])
        var prevRight = b[0].right
        for (k in 1 until b.size) {
            val c = b[k]
            if (c.left - prevRight > gapThreshold) {
                subLines.add(cur)
                cur = arrayListOf(c)
            } else {
                cur.add(c)
            }
            prevRight = max(prevRight, c.right)
        }
        subLines.add(cur)
    }

    // Step 4: detect a dominant page gutter → 2-column layout. If found, partition sub-lines
    //         by column membership and lay out column 1 entirely before column 2.
    val gutter = detectGutter(subLines, layout.pageSize.widthPoints)

    val orderedLines: List<ArrayList<CharEntry>> = if (gutter != null) {
        val left = ArrayList<ArrayList<CharEntry>>()
        val right = ArrayList<ArrayList<CharEntry>>()
        for (sl in subLines) {
            val cx = sl.sumOf { it.cx.toDouble() } / sl.size
            if (cx < gutter) left.add(sl) else right.add(sl)
        }
        // Each column: top-to-bottom then left-to-right (in case a column has side-notes).
        val byReading = compareByDescending<ArrayList<CharEntry>> { it.first().cy }
            .thenBy { it.first().left }
        left.sortWith(byReading)
        right.sortWith(byReading)
        left + right
    } else {
        val byReading = compareByDescending<ArrayList<CharEntry>> { it.first().cy }
            .thenBy { it.first().left }
        subLines.sortedWith(byReading)
    }

    // Step 5: flatten — RTL-aware ordering within each line.
    val sortedToPdfium = IntArray(chars.size)
    val lineRanges = ArrayList<LineRange>(orderedLines.size)
    var pos = 0
    for (line in orderedLines) {
        val rtlCount = line.count { isRtlCodepoint(it.cp) }
        val isRtl = rtlCount * 2 > line.size
        val inLineOrder = if (isRtl) line.sortedByDescending { it.left } else line.sortedBy { it.left }
        val start = pos
        var top = Float.NEGATIVE_INFINITY
        var bottom = Float.POSITIVE_INFINITY
        var lft = Float.POSITIVE_INFINITY
        var rgt = Float.NEGATIVE_INFINITY
        for (c in inLineOrder) {
            sortedToPdfium[pos++] = c.pdfIdx
            if (c.top > top) top = c.top
            if (c.bottom < bottom) bottom = c.bottom
            if (c.left < lft) lft = c.left
            if (c.right > rgt) rgt = c.right
        }
        // hitTop/hitBottom patched in a second pass below once we know neighbors.
        lineRanges.add(LineRange(start, pos, top, bottom, lft, rgt, top, bottom))
    }

    val expandedLines = expandLineHitBounds(lineRanges)

    return ReadingOrder(
        sortedToPdfium = if (pos == sortedToPdfium.size) sortedToPdfium else sortedToPdfium.copyOf(pos),
        lines = expandedLines,
    )
}

/**
 * Widen each line's vertical bounds so clicks anywhere on the rendered glyph land on the
 * right line, even when PDFium reports near-zero char heights. For each line we look at
 * its spatially nearest neighbour above and below (by vertical center, restricted to
 * lines that share x-overlap so different columns don't interfere) and push hitTop /
 * hitBottom halfway into the gap. Isolated lines fall back to a conservative
 * [DEFAULT_HIT_EXPAND].
 */
private fun expandLineHitBounds(lines: List<LineRange>): Array<LineRange> {
    if (lines.isEmpty()) return emptyArray()
    val result = arrayOfNulls<LineRange>(lines.size)
    for (i in lines.indices) {
        val self = lines[i]
        val selfCy = (self.top + self.bottom) * 0.5f
        var aboveBottom = Float.POSITIVE_INFINITY   // smallest bottom strictly above selfCy
        var belowTop = Float.NEGATIVE_INFINITY      // largest top strictly below selfCy
        for (j in lines.indices) {
            if (j == i) continue
            val other = lines[j]
            // Same-column restriction: require horizontal overlap so lines in other
            // columns don't eat our vertical gap.
            val overlap = min(self.right, other.right) - max(self.left, other.left)
            if (overlap <= 0f) continue
            val otherCy = (other.top + other.bottom) * 0.5f
            if (otherCy > selfCy) {
                // 'other' is above. Its bottom is the closest edge to us.
                if (other.bottom < aboveBottom) aboveBottom = other.bottom
            } else if (otherCy < selfCy) {
                if (other.top > belowTop) belowTop = other.top
            }
        }
        val gapAbove = if (aboveBottom.isFinite()) (aboveBottom - self.top).coerceAtLeast(0f) else DEFAULT_HIT_EXPAND
        val gapBelow = if (belowTop.isFinite()) (self.bottom - belowTop).coerceAtLeast(0f) else DEFAULT_HIT_EXPAND
        val hitTop = self.top + gapAbove * 0.5f
        val hitBottom = self.bottom - gapBelow * 0.5f
        result[i] = LineRange(
            start = self.start,
            endExclusive = self.endExclusive,
            top = self.top,
            bottom = self.bottom,
            left = self.left,
            right = self.right,
            hitTop = hitTop,
            hitBottom = hitBottom,
        )
    }
    @Suppress("UNCHECKED_CAST")
    return result as Array<LineRange>
}

/**
 * Guess the page gutter x-coordinate when the page is a 2-column layout.
 *
 * Heuristic: treat as 2-col iff (a) a majority of y-bands contribute ≥2 sub-lines, and
 * (b) the "gap midpoint" between the rightmost sub-line ending on the left and the
 * leftmost sub-line starting on the right clusters tightly around a single x. When the
 * page is single-column, returns null and the caller falls back to pure y-desc ordering.
 */
private fun detectGutter(
    subLines: List<List<CharEntry>>,
    pageWidthPts: Float,
): Float? {
    if (pageWidthPts <= 0f) return null

    // Collect candidate gutters: for each y-band that has ≥2 sub-lines, record the gap
    // midpoint between the leftmost "right-group" left edge and the rightmost "left-group"
    // right edge. Since we only tracked sub-lines (not original y-bands) we approximate
    // by pairing consecutive sub-lines that share a y-overlap.
    val midpoints = ArrayList<Float>()
    var pairs = 0
    var i = 0
    while (i < subLines.size) {
        val a = subLines[i]
        val aTop = a.maxOf { it.top }
        val aBot = a.minOf { it.bottom }
        var j = i + 1
        var bestGapMid = Float.NaN
        var bestGap = 0f
        while (j < subLines.size) {
            val b = subLines[j]
            val bTop = b.maxOf { it.top }
            val bBot = b.minOf { it.bottom }
            val overlap = min(aTop, bTop) - max(aBot, bBot)
            val minH = min(aTop - aBot, bTop - bBot)
            if (minH <= 0f || overlap <= minH * 0.5f) break
            val aRight = a.maxOf { it.right }
            val bLeft = b.minOf { it.left }
            if (bLeft > aRight) {
                val gap = bLeft - aRight
                if (gap > bestGap) {
                    bestGap = gap
                    bestGapMid = (aRight + bLeft) * 0.5f
                }
            }
            j++
        }
        if (!bestGapMid.isNaN()) {
            midpoints.add(bestGapMid)
            pairs++
        }
        i = if (j > i + 1) j - 1 else i + 1
    }

    if (pairs < 3) return null

    // Cluster midpoints: find the densest bucket at 2% page-width granularity.
    val bucketSize = (pageWidthPts * 0.02f).coerceAtLeast(1f)
    val buckets = HashMap<Int, Int>()
    for (m in midpoints) {
        val k = (m / bucketSize).toInt()
        buckets[k] = (buckets[k] ?: 0) + 1
    }
    val best = buckets.entries.maxByOrNull { it.value } ?: return null
    val ratio = best.value.toFloat() / midpoints.size.toFloat()
    // Require a strong cluster (>=60%) AND the gutter to sit near mid-page, to avoid
    // misfiring on a page with a single marginal note.
    if (ratio < 0.6f) return null
    val gutterX = (best.key + 0.5f) * bucketSize
    if (gutterX < pageWidthPts * 0.25f || gutterX > pageWidthPts * 0.75f) return null
    return gutterX
}

private fun isRtlCodepoint(cp: Int): Boolean = when (cp) {
    in 0x0590..0x05FF -> true                // Hebrew
    in 0x0600..0x06FF -> true                // Arabic
    in 0x0700..0x074F -> true                // Syriac
    in 0x0750..0x077F -> true                // Arabic Supplement
    in 0x0780..0x07BF -> true                // Thaana
    in 0x07C0..0x07FF -> true                // NKo
    in 0x0800..0x083F -> true                // Samaritan
    in 0x0840..0x085F -> true                // Mandaic
    in 0xFB1D..0xFB4F -> true                // Hebrew Presentation Forms
    in 0xFB50..0xFDFF -> true                // Arabic Presentation Forms-A
    in 0xFE70..0xFEFF -> true                // Arabic Presentation Forms-B
    else -> false
}

// --- Hit testing / text building ----------------------------------------------------------

private fun hitTestReadingPos(
    pointer: Offset,
    layout: PageTextLayout,
    order: ReadingOrder,
    scaleX: Float,
    scaleY: Float,
    containerH: Float,
): Int {
    if (order.size == 0 || order.lines.isEmpty()) return -1
    val pdfX = pointer.x / scaleX
    val pdfY = (containerH - pointer.y) / scaleY

    // Find the closest line by vertical distance. On a tie (pdfY between two lines of
    // identical distance) prefer the line whose horizontal extent contains pdfX — helps
    // on multi-column pages where two lines can share the same y-band across columns.
    var bestLine = 0
    var bestVDist = Float.MAX_VALUE
    var bestHContains = false
    for (li in order.lines.indices) {
        val line = order.lines[li]
        val v = when {
            pdfY in line.hitBottom..line.hitTop -> 0f
            pdfY > line.hitTop -> pdfY - line.hitTop
            else -> line.hitBottom - pdfY
        }
        val hContains = pdfX in line.left..line.right
        val better = v < bestVDist || (v == bestVDist && hContains && !bestHContains)
        if (better) {
            bestVDist = v
            bestLine = li
            bestHContains = hContains
            if (v == 0f && hContains) break
        }
    }
    val line = order.lines[bestLine]
    if (line.endExclusive <= line.start) return -1

    // Within the line, find the char whose box is horizontally closest.
    var bestPos = line.start
    var bestHDist = Float.MAX_VALUE
    for (pos in line.start until line.endExclusive) {
        val pdfIdx = order.pdfiumOf(pos)
        val l = layout.charLeft(pdfIdx)
        val r = layout.charRight(pdfIdx)
        val d = when {
            pdfX in l..r -> 0f
            pdfX > r -> pdfX - r
            else -> l - pdfX
        }
        if (d < bestHDist) {
            bestHDist = d
            bestPos = pos
            if (d == 0f) break
        }
    }
    return bestPos
}

private fun buildSelectedText(
    layout: PageTextLayout,
    order: ReadingOrder,
    lo: Int,
    hi: Int,
): String {
    if (lo > hi || order.size == 0) return ""
    val sb = StringBuilder()
    var prevLine = order.lineOf(lo)
    for (pos in lo..hi) {
        if (pos < 0 || pos >= order.size) continue
        val ln = order.lineOf(pos)
        if (ln >= 0 && ln != prevLine) {
            sb.append('\n')
            prevLine = ln
        }
        val cp = layout.codepoint(order.pdfiumOf(pos))
        if (cp <= 0) continue
        appendCodepoint(sb, cp)
    }
    return sb.toString()
}

private fun appendCodepoint(sb: StringBuilder, cp: Int) {
    if (cp <= 0xFFFF) {
        sb.append(cp.toChar())
    } else {
        val adjusted = cp - 0x10000
        sb.append((0xD800 or (adjusted shr 10)).toChar())
        sb.append((0xDC00 or (adjusted and 0x3FF)).toChar())
    }
}

private val SELECTION_COLOR = Color(0x664A7CFF)

// Column-gap threshold: a horizontal gap larger than COLUMN_GAP_MULT × average char width
// within a y-band is treated as a column boundary rather than a wide inter-word space.
// 3× is conservative enough that normal space-spaced words ("lorem    ipsum") don't split
// while true gutters (two text blocks) do.
private const val COLUMN_GAP_MULT = 3f

// Fallback half-height (in PDF points) when a line has no spatial neighbour in the same
// column to compute a real gap from — e.g. the only line on a title page. Typical body
// text is ~10-14pt tall, so 6pt each side gives a comfortable click target.
private const val DEFAULT_HIT_EXPAND = 6f

private const val DEBOUNCE_MS = 100L
// 2048 px is enough for HiDPI displays (a 4K monitor renders a half-width page at ~1920 px).
// Dropping from 4096 quarters per-page bitmap bytes (bytes scale with width², since height is
// derived from aspect) and keeps the native-memory growth bounded when the user zooms in.
private const val MAX_RENDER_WIDTH = 2048
private const val DEFAULT_ASPECT = 595f / 842f // A4 portrait
