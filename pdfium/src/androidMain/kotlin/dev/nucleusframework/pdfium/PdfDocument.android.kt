package dev.nucleusframework.pdfium

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.nucleusframework.pdfium.jvm.Pdfium
import dev.nucleusframework.pdfium.jvm.PdfiumBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Android PDF document backed by PDFium.
 *
 * Rendering is **zero-copy**: PDFium writes its BGRA output directly into the
 * `android.graphics.Bitmap`'s native pixel memory via the NDK
 * `AndroidBitmap_lockPixels` / `_unlockPixels` pair. No intermediate ByteBuffer or
 * copy via `copyPixelsFromBuffer`.
 */
internal actual class PdfDocument(
    private val handle: Long,
) {
    actual val pageCount: Int = PdfiumBridge.nGetPageCount(handle)
    actual val metadata: PdfMetadata = PdfMetadata(
        title = PdfiumBridge.nGetMeta(handle, "Title"),
        author = PdfiumBridge.nGetMeta(handle, "Author"),
        subject = PdfiumBridge.nGetMeta(handle, "Subject"),
        keywords = PdfiumBridge.nGetMeta(handle, "Keywords"),
        creator = PdfiumBridge.nGetMeta(handle, "Creator"),
        producer = PdfiumBridge.nGetMeta(handle, "Producer"),
    )

    actual suspend fun pageSize(pageIndex: Int): PageSize = withContext(Pdfium.dispatcher) {
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
        withContext(Pdfium.dispatcher) {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val page = PdfiumBridge.nLoadPage(handle, pageIndex)
            check(page != 0L) { "PDFium failed to load page $pageIndex (err=${PdfiumBridge.nGetLastError()})" }
            try {
                val ok = PdfiumBridge.nRenderPageToBitmap(page, bitmap, widthPx, heightPx)
                check(ok) { "PDFium render failed (err=${PdfiumBridge.nGetLastError()})" }
            } finally {
                PdfiumBridge.nClosePage(page)
            }
            bitmap.asImageBitmap()
        }

    actual suspend fun pageText(pageIndex: Int): String = withContext(Pdfium.dispatcher) {
        val page = PdfiumBridge.nLoadPage(handle, pageIndex)
        if (page == 0L) return@withContext ""
        try {
            PdfiumBridge.nGetPageText(page).orEmpty()
        } finally {
            PdfiumBridge.nClosePage(page)
        }
    }

    actual fun close() {
        runBlocking(Pdfium.dispatcher) {
            PdfiumBridge.nCloseDocument(handle)
        }
    }
}

internal actual suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument =
    withContext(Pdfium.dispatcher) {
        val handle = PdfiumBridge.nOpenDocument(bytes, password)
        if (handle == 0L) {
            error("PDFium refused to open document (err=${PdfiumBridge.nGetLastError()})")
        }
        PdfDocument(handle)
    }
