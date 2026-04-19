package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.nucleusframework.pdfium.jvm.FPDF_ANNOT
import dev.nucleusframework.pdfium.jvm.Pdfium
import dev.nucleusframework.pdfium.jvm.PdfiumBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * JVM PDF document backed by a **pool** of PDFium document handles. All handles reference
 * the **same** native byte buffer (one allocation, shared across the pool) but each has its
 * own dispatcher/thread, so renders for different pages run in parallel across CPU cores.
 *
 * Rendering is still zero-copy: PDFium writes BGRA directly into each Skia Bitmap's pixel
 * memory (`peekPixels().addr`).
 */
internal actual class PdfDocument internal constructor(
    private val bufferAddr: Long,
    private val bufferSize: Int,
    private val handles: LongArray,
    private val dispatchers: Array<CoroutineDispatcher>,
    private val executors: Array<ExecutorService>,
) {
    actual val pageCount: Int = PdfiumBridge.nGetPageCount(handles[0])
    actual val metadata: PdfMetadata = PdfMetadata(
        title = PdfiumBridge.nGetMeta(handles[0], "Title"),
        author = PdfiumBridge.nGetMeta(handles[0], "Author"),
        subject = PdfiumBridge.nGetMeta(handles[0], "Subject"),
        keywords = PdfiumBridge.nGetMeta(handles[0], "Keywords"),
        creator = PdfiumBridge.nGetMeta(handles[0], "Creator"),
        producer = PdfiumBridge.nGetMeta(handles[0], "Producer"),
    )

    private val nextSlot = AtomicInteger(0)
    private fun pickSlot(): Int = (nextSlot.getAndIncrement() and Int.MAX_VALUE) % handles.size

    actual suspend fun pageSize(pageIndex: Int): PageSize {
        val slot = pickSlot()
        return withContext(dispatchers[slot]) {
            val handle = handles[slot]
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            if (page == 0L) return@withContext PageSize(0f, 0f)
            try {
                PageSize(
                    widthPoints = PdfiumBridge.nGetPageWidth(page),
                    heightPoints = PdfiumBridge.nGetPageHeight(page),
                )
            } finally {
                PdfiumBridge.nClosePage(page)
            }
        }
    }

    actual suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): ImageBitmap {
        val slot = pickSlot()
        return withContext(dispatchers[slot]) {
            val handle = handles[slot]
            val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
            val bitmap = Bitmap().apply { allocPixels(info) }
            val pixmap = bitmap.peekPixels()
                ?: error("Skia peekPixels returned null")
            val addr = pixmap.addr
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            check(page != 0L) { "PDFium load page $pageIndex failed (err=${PdfiumBridge.nGetLastError()})" }
            try {
                val ok = PdfiumBridge.nRenderPageToAddress(
                    page = page,
                    address = addr,
                    width = widthPx,
                    height = heightPx,
                    flags = quality.toFlags(),
                )
                check(ok) { "PDFium render failed (err=${PdfiumBridge.nGetLastError()})" }
            } finally {
                PdfiumBridge.nClosePage(page)
            }
            bitmap.asComposeImageBitmap()
        }
    }

    actual suspend fun pageText(pageIndex: Int): String {
        val slot = pickSlot()
        return withContext(dispatchers[slot]) {
            val handle = handles[slot]
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            if (page == 0L) return@withContext ""
            try {
                PdfiumBridge.nGetPageText(page).orEmpty()
            } finally {
                PdfiumBridge.nClosePage(page)
            }
        }
    }

    actual suspend fun pageTextLayout(pageIndex: Int): PageTextLayout {
        val slot = pickSlot()
        return withContext(dispatchers[slot]) {
            val handle = handles[slot]
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            if (page == 0L) return@withContext PageTextLayout.Empty
            try {
                val size = PageSize(
                    widthPoints = PdfiumBridge.nGetPageWidth(page),
                    heightPoints = PdfiumBridge.nGetPageHeight(page),
                )
                // Rect level (line runs + bounded text).
                val rectCount = PdfiumBridge.nCountTextRects(page)
                val rectBoxes: FloatArray
                val rectTexts: Array<String>
                if (rectCount > 0) {
                    val boxes = FloatArray(rectCount * 4)
                    val texts = arrayOfNulls<String>(rectCount)
                    val written = PdfiumBridge.nExtractTextRects(page, boxes, texts)
                    rectTexts = Array(written) { texts[it].orEmpty() }
                    rectBoxes = if (written == rectCount) boxes else boxes.copyOf(written * 4)
                } else {
                    rectBoxes = FloatArray(0)
                    rectTexts = emptyArray()
                }
                // Char level (per-glyph codepoint + box).
                val charCount = PdfiumBridge.nCountPageChars(page)
                val charCodepoints: IntArray
                val charBoxes: FloatArray
                if (charCount > 0) {
                    val cps = IntArray(charCount)
                    val cbs = FloatArray(charCount * 4)
                    val written = PdfiumBridge.nExtractCharBoxes(page, cps, cbs)
                    charCodepoints = if (written == charCount) cps else cps.copyOf(written)
                    charBoxes = if (written == charCount) cbs else cbs.copyOf(written * 4)
                } else {
                    charCodepoints = IntArray(0)
                    charBoxes = FloatArray(0)
                }
                PageTextLayout(pageIndex, size, rectBoxes, rectTexts, charCodepoints, charBoxes)
            } finally {
                PdfiumBridge.nClosePage(page)
            }
        }
    }

    actual fun close() {
        runBlocking {
            for (i in handles.indices) {
                withContext(dispatchers[i]) {
                    PdfiumBridge.nCloseDocument(handles[i])
                }
            }
        }
        PdfiumBridge.nFreeBuffer(bufferAddr)
        executors.forEach { it.shutdown() }
    }

    private fun RenderQuality.toFlags(): Int = when (this) {
        RenderQuality.PREVIEW -> 0
        RenderQuality.FULL -> FPDF_ANNOT
    }
}

// PDFium internally uses FreeType's `FT_Library`, a global singleton which FreeType explicitly
// documents as "not thread-safe". Two threads rendering any page that touches fonts will
// corrupt FreeType's heap → SIGSEGV in libfreetype. So even with distinct FPDF_DOCUMENT
// handles, we cannot parallelize. POOL_SIZE stays at 1; Chromium works around this by
// running PDFium in a separate process, which we can't do from the JVM without major work.
private const val POOL_SIZE: Int = 1

internal actual suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument =
    withContext(Pdfium.sharedDispatcher) {
        val bufferAddr = PdfiumBridge.nAllocBuffer(bytes)
        if (bufferAddr == 0L) error("Failed to allocate native PDF buffer")
        try {
            val handles = LongArray(POOL_SIZE)
            for (i in 0 until POOL_SIZE) {
                val h = PdfiumBridge.nOpenDocumentFromMemory(bufferAddr, bytes.size.toLong(), password)
                if (h == 0L) {
                    // Cleanup previously opened handles + buffer on failure.
                    for (j in 0 until i) PdfiumBridge.nCloseDocument(handles[j])
                    PdfiumBridge.nFreeBuffer(bufferAddr)
                    error("PDFium refused to open document (err=${PdfiumBridge.nGetLastError()})")
                }
                handles[i] = h
            }
            val pairs = Array(POOL_SIZE) { Pdfium.newDispatcher() }
            val dispatchers = Array(POOL_SIZE) { pairs[it].first }
            val executors = Array(POOL_SIZE) { pairs[it].second }
            PdfDocument(bufferAddr, bytes.size, handles, dispatchers, executors)
        } catch (t: Throwable) {
            PdfiumBridge.nFreeBuffer(bufferAddr)
            throw t
        }
    }
