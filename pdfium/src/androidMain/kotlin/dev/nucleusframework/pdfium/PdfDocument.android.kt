package dev.nucleusframework.pdfium

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.nucleusframework.pdfium.jvm.FPDF_ANNOT
import dev.nucleusframework.pdfium.jvm.Pdfium
import dev.nucleusframework.pdfium.jvm.PdfiumBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal actual class PdfDocument internal constructor(
    private val bufferAddr: Long,
    private val bufferSize: Int,
    private val handles: LongArray,
    // Per-document FPDF_FORMHANDLE (parallel to [handles]). May be 0 if PDFium refused;
    // render code falls back to no widget overlay in that case.
    private val formHandles: LongArray,
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
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            check(page != 0L) { "PDFium load page $pageIndex failed (err=${PdfiumBridge.nGetLastError()})" }
            try {
                val ok = PdfiumBridge.nRenderPageToBitmap(
                    page = page,
                    // PREVIEW skips form-fill to keep thumbnails cheap; FULL passes the form
                    // handle so signatures + interactive widgets render correctly.
                    form = if (quality == RenderQuality.FULL) formHandles[slot] else 0L,
                    bitmap = bitmap,
                    width = widthPx,
                    height = heightPx,
                    flags = quality.toFlags(),
                )
                check(ok) { "PDFium render failed (err=${PdfiumBridge.nGetLastError()})" }
            } finally {
                PdfiumBridge.nClosePage(page)
            }
            bitmap.asImageBitmap()
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

    actual suspend fun pageLinks(pageIndex: Int): PageLinks {
        val slot = pickSlot()
        return withContext(dispatchers[slot]) {
            val handle = handles[slot]
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            if (page == 0L) return@withContext PageLinks.Empty
            try {
                val size = PageSize(
                    widthPoints = PdfiumBridge.nGetPageWidth(page),
                    heightPoints = PdfiumBridge.nGetPageHeight(page),
                )
                val count = PdfiumBridge.nCountPageLinks(handle, page)
                if (count <= 0) return@withContext PageLinks(pageIndex, size, emptyList())
                val boxes = FloatArray(count * 4)
                val uris = arrayOfNulls<String>(count)
                val destPages = IntArray(count)
                val isWeb = BooleanArray(count)
                val written = PdfiumBridge.nExtractPageLinks(handle, page, boxes, uris, destPages, isWeb)
                pageLinksFromArrays(pageIndex, size, boxes, uris, destPages, isWeb, written)
            } finally {
                PdfiumBridge.nClosePage(page)
            }
        }
    }

    actual fun close() {
        runBlocking {
            for (i in handles.indices) {
                withContext(dispatchers[i]) {
                    // Form-fill env must be torn down BEFORE its underlying document.
                    PdfiumBridge.nCloseFormEnv(formHandles[i])
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

// FreeType's FT_Library is a non-thread-safe singleton shared by all FPDF_DOCUMENTs —
// parallel rendering corrupts its heap. POOL_SIZE stays at 1. See JVM actual for context.
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
                    for (j in 0 until i) PdfiumBridge.nCloseDocument(handles[j])
                    PdfiumBridge.nFreeBuffer(bufferAddr)
                    error("PDFium refused to open document (err=${PdfiumBridge.nGetLastError()})")
                }
                handles[i] = h
            }
            val pairs = Array(POOL_SIZE) { Pdfium.newDispatcher() }
            val dispatchers = Array(POOL_SIZE) { pairs[it].first }
            val executors = Array(POOL_SIZE) { pairs[it].second }
            val formHandles = LongArray(POOL_SIZE) { PdfiumBridge.nInitFormEnv(handles[it]) }
            PdfDocument(bufferAddr, bytes.size, handles, formHandles, dispatchers, executors)
        } catch (t: Throwable) {
            PdfiumBridge.nFreeBuffer(bufferAddr)
            throw t
        }
    }
