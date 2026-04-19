package dev.nucleusframework.pdfium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
class PdfReaderState internal constructor(
    cacheBytes: Long = DEFAULT_CACHE_BYTES,
    thumbnailCacheBytes: Long = DEFAULT_THUMBNAIL_CACHE_BYTES,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val openMutex = Mutex()
    private val cacheMutex = Mutex()
    private val thumbCacheMutex = Mutex()
    // Reader-page cache: full-quality renders at viewport width. Sized for a handful of
    // big bitmaps (±2 prefetch around the visible page).
    private val cache = PdfRenderCache(cacheBytes)
    // Thumbnail cache: small preview renders for the sidebar/strip. Kept separate so that
    // scrolling the thumbnail list doesn't evict reader-page bitmaps (and vice-versa).
    private val thumbCache = PdfRenderCache(thumbnailCacheBytes)
    private var document: PdfDocument? = null

    var pageCount: Int by mutableStateOf(0)
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var error: PdfError? by mutableStateOf(null)
        private set
    var metadata: PdfMetadata by mutableStateOf(PdfMetadata())
        private set

    /** Render scale multiplier applied to point-sized dimensions (1f = 72dpi). */
    var renderScale: Float by mutableStateOf(1f)

    suspend fun open(bytes: ByteArray, password: String? = null) = openMutex.withLock {
        isLoading = true
        error = null
        try {
            document?.close()
            document = null
            cacheMutex.withLock { cache.clear() }
            thumbCacheMutex.withLock { thumbCache.clear() }
            val doc = openPdfDocument(bytes, password)
            document = doc
            pageCount = doc.pageCount
            metadata = doc.metadata
        } catch (t: Throwable) {
            error = PdfError.NativeFailure(t.message ?: "Unable to open PDF", t)
            pageCount = 0
            metadata = PdfMetadata()
        } finally {
            isLoading = false
        }
    }

    /** Page dimensions in points, or null if the document isn't open or the index is out of range. */
    suspend fun pageSize(pageIndex: Int): PageSize? =
        document?.pageSize(pageIndex)

    internal suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality = RenderQuality.FULL,
    ): ImageBitmap? {
        val doc = document ?: return null
        if (widthPx <= 0 || heightPx <= 0 || pageIndex !in 0 until pageCount) return null
        // Only FULL renders are cached — PREVIEW is a transient low-res placeholder used to
        // bootstrap the page while the full render is in flight, and is replaced on the next
        // frame. Caching it would just double the footprint per page for no reuse.
        if (quality == RenderQuality.FULL) {
            cacheMutex.withLock { cache.get(pageIndex, widthPx) }?.let { return it }
        }
        val rendered = doc.renderPage(pageIndex, widthPx, heightPx, quality)
        if (quality == RenderQuality.FULL) {
            cacheMutex.withLock { cache.put(pageIndex, widthPx, rendered) }
        }
        return rendered
    }

    /**
     * Render a page into the thumbnail cache (kept separate so sidebar scrolling doesn't
     * evict reader-page bitmaps). Always uses [RenderQuality.PREVIEW].
     */
    internal suspend fun renderThumbnail(pageIndex: Int, widthPx: Int, heightPx: Int): ImageBitmap? {
        val doc = document ?: return null
        if (widthPx <= 0 || heightPx <= 0 || pageIndex !in 0 until pageCount) return null
        thumbCacheMutex.withLock { thumbCache.get(pageIndex, widthPx) }?.let { return it }
        val rendered = doc.renderPage(pageIndex, widthPx, heightPx, RenderQuality.PREVIEW)
        thumbCacheMutex.withLock { thumbCache.put(pageIndex, widthPx, rendered) }
        return rendered
    }

    /** Fire-and-forget: ensure a page is in cache at the given width. Best-effort, swallows errors. */
    fun prefetch(pageIndex: Int, widthPx: Int, quality: RenderQuality = RenderQuality.FULL) {
        if (widthPx <= 0) return
        scope.launch {
            val doc = document ?: return@launch
            if (pageIndex !in 0 until pageCount) return@launch
            cacheMutex.withLock { cache.get(pageIndex, widthPx) }?.let { return@launch }
            try {
                val ps = doc.pageSize(pageIndex)
                val heightPx = (widthPx / ps.aspectRatio).toInt().coerceAtLeast(1)
                val rendered = doc.renderPage(pageIndex, widthPx, heightPx, quality)
                if (quality == RenderQuality.FULL) {
                    cacheMutex.withLock { cache.put(pageIndex, widthPx, rendered) }
                }
            } catch (_: Throwable) {
                // Prefetch is opportunistic — drop failures.
            }
        }
    }

    suspend fun pageText(pageIndex: Int): String {
        val doc = document ?: return ""
        if (pageIndex !in 0 until pageCount) return ""
        return doc.pageText(pageIndex)
    }

    suspend fun pageTextLayout(pageIndex: Int): PageTextLayout? {
        val doc = document ?: return null
        if (pageIndex !in 0 until pageCount) return null
        return doc.pageTextLayout(pageIndex)
    }

    fun dispose() {
        scope.launch {
            openMutex.withLock {
                document?.close()
                document = null
                cacheMutex.withLock { cache.clear() }
                thumbCacheMutex.withLock { thumbCache.clear() }
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    internal fun launchOnScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    companion object {
        /**
         * 64 MB default budget for full-quality reader renders. At 2048 px max width this fits
         * 3-4 pages — enough for the visible page + ±2 prefetch on a desktop viewport.
         * Native pixels live outside the JVM heap, so oversizing this directly inflates the
         * process RSS (Skia Cleaner only runs on GC pressure, which a tiny heap doesn't trigger).
         */
        const val DEFAULT_CACHE_BYTES: Long = 64L * 1024 * 1024

        /**
         * 12 MB for sidebar/strip thumbnails. At 240 px wide each thumbnail is ~280 KB, so this
         * caches ~40 thumbs — plenty to cover a visible strip plus scroll momentum.
         */
        const val DEFAULT_THUMBNAIL_CACHE_BYTES: Long = 12L * 1024 * 1024
    }
}

@Composable
fun rememberPdfReaderState(
    cacheBytes: Long = PdfReaderState.DEFAULT_CACHE_BYTES,
    thumbnailCacheBytes: Long = PdfReaderState.DEFAULT_THUMBNAIL_CACHE_BYTES,
): PdfReaderState {
    val state = remember(cacheBytes, thumbnailCacheBytes) {
        PdfReaderState(cacheBytes, thumbnailCacheBytes)
    }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}
