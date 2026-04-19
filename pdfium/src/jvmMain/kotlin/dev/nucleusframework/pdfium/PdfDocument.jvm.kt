package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.nucleusframework.pdfium.jvm.Pdfium
import dev.nucleusframework.pdfium.jvm.PdfiumBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/**
 * JVM PDF document backed by PDFium + Skia.
 *
 * Rendering is **zero-copy**: PDFium writes its BGRA output directly into the Skia Bitmap's
 * native pixel memory via a raw pointer obtained from `Bitmap.peekPixels().addr`. No
 * intermediate ByteBuffer, no ByteArray, no `Bitmap.installPixels(ByteArray)` copy.
 *
 * Bitmap's pixel memory is Skia-managed — its lifetime is tied to the Kotlin `Bitmap`
 * instance, so when Compose drops the resulting ImageBitmap, GC eventually reclaims the
 * bitmap and Skia frees the pixels. Peak RAM is therefore bounded by the number of
 * bitmaps Compose keeps alive (typically just the visible pages).
 */
internal actual class PdfDocument(
    private val handle: Long,
) {
    // Per-document single-threaded dispatcher: docs render in parallel, ops within a doc are serialized.
    private val dispatcherPair = Pdfium.newDispatcher()
    private val dispatcher = dispatcherPair.first
    private val executor = dispatcherPair.second

    actual val pageCount: Int = PdfiumBridge.nGetPageCount(handle)
    actual val metadata: PdfMetadata = PdfMetadata(
        title = PdfiumBridge.nGetMeta(handle, "Title"),
        author = PdfiumBridge.nGetMeta(handle, "Author"),
        subject = PdfiumBridge.nGetMeta(handle, "Subject"),
        keywords = PdfiumBridge.nGetMeta(handle, "Keywords"),
        creator = PdfiumBridge.nGetMeta(handle, "Creator"),
        producer = PdfiumBridge.nGetMeta(handle, "Producer"),
    )

    actual suspend fun pageSize(pageIndex: Int): PageSize = withContext(dispatcher) {
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

    actual suspend fun renderPage(pageIndex: Int, widthPx: Int, heightPx: Int): ImageBitmap =
        withContext(dispatcher) {
            // N32 on little-endian = BGRA — PDFium writes BGRA natively, no byte-swap needed.
            val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
            val bitmap = Bitmap().apply { allocPixels(info) }
            val pixmap = bitmap.peekPixels()
                ?: error("Skia peekPixels returned null (bitmap not backed by contiguous memory?)")
            val pixelsAddr = pixmap.addr
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            check(page != 0L) { "PDFium failed to load page $pageIndex (err=${PdfiumBridge.nGetLastError()})" }
            try {
                val ok = PdfiumBridge.nRenderPageToAddress(
                    page = page,
                    address = pixelsAddr,
                    width = widthPx,
                    height = heightPx,
                    swapRedBlue = false,
                )
                check(ok) { "PDFium render failed (err=${PdfiumBridge.nGetLastError()})" }
            } finally {
                PdfiumBridge.nClosePage(page)
            }
            bitmap.asComposeImageBitmap()
        }

    actual suspend fun pageText(pageIndex: Int): String = withContext(dispatcher) {
        val page = PdfiumBridge.nLoadPage(handle, pageIndex)
        if (page == 0L) return@withContext ""
        try {
            PdfiumBridge.nGetPageText(page).orEmpty()
        } finally {
            PdfiumBridge.nClosePage(page)
        }
    }

    actual fun close() {
        runBlocking(dispatcher) {
            PdfiumBridge.nCloseDocument(handle)
        }
        executor.shutdown()
    }
}

internal actual suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument =
    withContext(Pdfium.sharedDispatcher) {
        // Opening runs on the shared dispatcher to keep PDFium's library init + doc load
        // race-free. Subsequent ops flow through the document's own dispatcher so each
        // doc renders independently.
        val handle = PdfiumBridge.nOpenDocument(bytes, password)
        if (handle == 0L) {
            error("PDFium refused to open document (err=${PdfiumBridge.nGetLastError()})")
        }
        PdfDocument(handle)
    }
