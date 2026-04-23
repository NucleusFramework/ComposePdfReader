package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.pdfium.PdfReaderState
import dev.nucleusframework.pdfium.rememberPdfReaderState
import dev.nucleusframework.pdfium.textClipEntry
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Layout of pages in the main reader surface: one per row, or two side-by-side. */
enum class SpreadMode { Single, Double }

/** One visible row in the reader: a single page, or a pair of facing pages. */
data class SpreadEntry(val first: Int, val second: Int?)

/**
 * Plain state holder for the reader screen. Keeps all UI-level mutable state (dialog,
 * toast, file name, list states, spread mode) + derived values (current page, spread
 * entries). Exposes intents as methods; the composable tree calls these and never runs
 * coroutines directly.
 *
 * Note: this class is framework-agnostic (no ViewModel base class), tested as any plain
 * Kotlin class. Lifecycle is tied to the composition via [rememberReaderScreenState].
 */
@Stable
class ReaderScreenState internal constructor(
    val reader: PdfReaderState,
    val mainListState: LazyListState,
    val thumbListState: LazyListState,
    private val scope: CoroutineScope,
    private val clipboard: Clipboard,
) {
    var fileName: String? by mutableStateOf(null)
        private set
    var textDialogPage: Int? by mutableStateOf(null)
        private set
    var toast: String? by mutableStateOf(null)
        private set
    var spreadMode: SpreadMode by mutableStateOf(SpreadMode.Single)
        private set

    /**
     * Size in device pixels of the reader's viewport (the region where pages scroll).
     * The reader surface reports its own dimensions here so the state holder can compute
     * "Fit Width" / "Fit Height" / "Fit Page" scales without peeking into composables.
     */
    var contentViewportPx: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** Rows rendered in the main list — one per page in Single mode, one per pair in Double mode. */
    val spreadEntries: List<SpreadEntry> by derivedStateOf {
        val count = reader.pageCount
        when (spreadMode) {
            SpreadMode.Single -> List(count) { SpreadEntry(it, null) }
            SpreadMode.Double -> buildList {
                var i = 0
                while (i < count) {
                    add(SpreadEntry(i, (i + 1).takeIf { it < count }))
                    i += 2
                }
            }
        }
    }

    /** Top-most fully visible page index, clamped to the current document. */
    val currentPage: Int by derivedStateOf {
        val entries = spreadEntries
        val itemIdx = mainListState.firstVisibleItemIndex
        val pageIdx = entries.getOrNull(itemIdx)?.first ?: 0
        pageIdx.coerceIn(0, max(0, reader.pageCount - 1))
    }

    /**
     * Load a new document. The state holder owns the launch so call sites don't need their
     * own scope. [displayName] is shown in the top bar; [loader] returns the raw bytes
     * (typically `platformFile.readBytes()`).
     */
    fun openDocument(displayName: String?, loader: suspend () -> ByteArray) {
        scope.launch {
            fileName = displayName
            val bytes = loader()
            reader.open(bytes)
        }
    }

    fun jumpToPage(index: Int) {
        scope.launch {
            val entries = spreadEntries
            val row = entries.indexOfFirst { it.first == index || it.second == index }
                .coerceAtLeast(0)
            // scrollToItem teleports to the target; animateScrollToItem would walk through
            // every intermediate item, rendering each page on the way (slow for large jumps).
            mainListState.scrollToItem(row)
        }
    }

    fun toggleSpreadMode() {
        val next = if (spreadMode == SpreadMode.Single) SpreadMode.Double else SpreadMode.Single
        val pinned = currentPage
        spreadMode = next
        // Snap the scroll position so the previously-visible page stays in view under the new layout.
        scope.launch {
            val entries = spreadEntries
            val row = entries.indexOfFirst { it.first == pinned || it.second == pinned }
                .coerceAtLeast(0)
            mainListState.scrollToItem(row)
        }
    }

    fun copyPageText(index: Int) {
        scope.launch {
            val text = reader.pageText(index).trim()
            toast = if (text.isEmpty()) {
                "Page ${index + 1} has no extractable text"
            } else {
                clipboard.setClipEntry(textClipEntry(text))
                "Page ${index + 1} text copied"
            }
        }
    }

    fun showTextDialog(index: Int) { textDialogPage = index }
    fun dismissTextDialog() { textDialogPage = null }

    fun updateViewport(size: IntSize) {
        if (size != contentViewportPx) contentViewportPx = size
    }

    // ---- Fit actions ----
    //
    // Maths: at scale = 1.0, a single page fills the viewport width. Page height = width / aspect.
    //  - Fit Width   → scale = 1.0 (trivial; page width ≡ viewport width).
    //  - Fit Height  → scale so that page height == viewport height:
    //                  pageHeight = (viewportWidth × scale) / aspect = viewportHeight
    //                  ⇒ scale = viewportHeight × aspect / viewportWidth
    //  - Fit Page    → page fits entirely: min(Fit-Width, Fit-Height) — i.e. min(1.0, heightScale).
    //
    // In Double mode, each rendered page is half-width, so "Fit Height" scales up by 2 to keep
    // the same on-screen page height — handled here rather than in the rendering code.

    fun fitWidth() {
        reader.renderScale = 1f.coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    fun fitHeight() {
        scope.launch {
            val scale = computeFitHeightScale() ?: return@launch
            reader.renderScale = scale.coerceIn(ZOOM_MIN, ZOOM_MAX)
        }
    }

    fun fitPage() {
        scope.launch {
            val heightScale = computeFitHeightScale() ?: return@launch
            val scale = minOf(1f, heightScale).coerceIn(ZOOM_MIN, ZOOM_MAX)
            reader.renderScale = scale
        }
    }

    private suspend fun computeFitHeightScale(): Float? {
        val vw = contentViewportPx.width.toFloat()
        val vh = contentViewportPx.height.toFloat()
        if (vw <= 0f || vh <= 0f || reader.pageCount == 0) return null
        val aspect = reader.pageSize(0)?.aspectRatio?.takeIf { it > 0f } ?: return null
        val spreadFactor = if (spreadMode == SpreadMode.Double) 2f else 1f
        return (vh * aspect * spreadFactor) / vw
    }

    fun copyAndDismissText(text: String) {
        scope.launch { clipboard.setClipEntry(textClipEntry(text)) }
        textDialogPage = null
        toast = "Text copied"
    }

    fun clearToast() { toast = null }
}

internal const val ZOOM_MIN = 0.1f
internal const val ZOOM_MAX = 4f

@Composable
fun rememberReaderScreenState(): ReaderScreenState {
    val reader = rememberPdfReaderState()
    val mainListState = rememberLazyListState()
    val thumbListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    return remember(reader, scope, clipboard) {
        ReaderScreenState(reader, mainListState, thumbListState, scope, clipboard)
    }
}
