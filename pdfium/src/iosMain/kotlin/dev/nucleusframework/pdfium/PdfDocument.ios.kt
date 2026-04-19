package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.nucleusframework.pdfium.native.FPDFBitmap_BGRA
import dev.nucleusframework.pdfium.native.FPDFBitmap_CreateEx
import dev.nucleusframework.pdfium.native.FPDFBitmap_Destroy
import dev.nucleusframework.pdfium.native.FPDFBitmap_FillRect
import dev.nucleusframework.pdfium.native.FPDF_ANNOT
import dev.nucleusframework.pdfium.native.FPDF_CloseDocument
import dev.nucleusframework.pdfium.native.FPDF_ClosePage
import dev.nucleusframework.pdfium.native.FPDF_GetLastError
import dev.nucleusframework.pdfium.native.FPDF_GetMetaText
import dev.nucleusframework.pdfium.native.FPDF_GetPageCount
import dev.nucleusframework.pdfium.native.FPDF_GetPageHeightF
import dev.nucleusframework.pdfium.native.FPDF_GetPageWidthF
import dev.nucleusframework.pdfium.native.FPDF_InitLibraryWithConfig
import dev.nucleusframework.pdfium.native.FPDF_LCD_TEXT
import dev.nucleusframework.pdfium.native.FPDF_LIBRARY_CONFIG
import dev.nucleusframework.pdfium.native.FPDF_LoadMemDocument64
import dev.nucleusframework.pdfium.native.FPDF_LoadPage
import dev.nucleusframework.pdfium.native.FPDF_RenderPageBitmap
import dev.nucleusframework.pdfium.native.FPDFText_ClosePage
import dev.nucleusframework.pdfium.native.FPDFText_CountChars
import dev.nucleusframework.pdfium.native.FPDFText_GetText
import dev.nucleusframework.pdfium.native.FPDFText_LoadPage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// PDFium is not thread-safe. Serialize every call through a single-threaded dispatcher.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val pdfiumDispatcher = Dispatchers.Default.limitedParallelism(1)
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.concurrent.AtomicReference

@OptIn(ExperimentalForeignApi::class)
private object PdfiumInit {
    private val ready = AtomicReference(false)
    fun ensure() {
        if (ready.value) return
        memScoped {
            val config = alloc<FPDF_LIBRARY_CONFIG>().apply {
                version = 2
                m_pUserFontPaths = null
                m_pIsolate = null
                m_v8EmbedderSlot = 0u
            }
            FPDF_InitLibraryWithConfig(config.ptr)
        }
        ready.value = true
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual class PdfDocument(
    private val handle: COpaquePointer,
) {
    actual val pageCount: Int = FPDF_GetPageCount(handle)
    actual val metadata: PdfMetadata = PdfMetadata(
        title = readMetaTag(handle, "Title"),
        author = readMetaTag(handle, "Author"),
        subject = readMetaTag(handle, "Subject"),
        keywords = readMetaTag(handle, "Keywords"),
        creator = readMetaTag(handle, "Creator"),
        producer = readMetaTag(handle, "Producer"),
    )

    actual suspend fun pageSize(pageIndex: Int): PageSize = withContext(pdfiumDispatcher) {
        val page = FPDF_LoadPage(handle, pageIndex) ?: return@withContext PageSize(0f, 0f)
        try {
            PageSize(
                widthPoints = FPDF_GetPageWidthF(page),
                heightPoints = FPDF_GetPageHeightF(page),
            )
        } finally {
            FPDF_ClosePage(page)
        }
    }

    actual suspend fun renderPage(pageIndex: Int, widthPx: Int, heightPx: Int): ImageBitmap =
        withContext(pdfiumDispatcher) {
            // N32 on little-endian = BGRA → no byte-swap; PDFium writes BGRA natively.
            val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
            val rowBytes = widthPx * 4
            val bitmap = Bitmap().apply { allocPixels(info) }
            val pixmap = bitmap.peekPixels()
                ?: error("peekPixels returned null")
            // Pixmap.addr on Kotlin/Native is a NativePointer (COpaquePointer).
            val addr = pixmap.addr
            val page = FPDF_LoadPage(handle, pageIndex)
                ?: error("PDFium load page failed (err=${FPDF_GetLastError()})")
            try {
                val bmp = FPDFBitmap_CreateEx(widthPx, heightPx, FPDFBitmap_BGRA, addr, rowBytes)
                    ?: error("FPDFBitmap_CreateEx returned null")
                FPDFBitmap_FillRect(bmp, 0, 0, widthPx, heightPx, 0xFFFFFFFFu.toInt())
                val flags = FPDF_ANNOT or FPDF_LCD_TEXT
                FPDF_RenderPageBitmap(bmp, page, 0, 0, widthPx, heightPx, 0, flags)
                FPDFBitmap_Destroy(bmp)
            } finally {
                FPDF_ClosePage(page)
            }
            bitmap.asComposeImageBitmap()
        }

    actual suspend fun pageText(pageIndex: Int): String = withContext(pdfiumDispatcher) {
        val page = FPDF_LoadPage(handle, pageIndex) ?: return@withContext ""
        try {
            val textPage = FPDFText_LoadPage(page) ?: return@withContext ""
            try {
                val count = FPDFText_CountChars(textPage)
                if (count <= 0) return@withContext ""
                memScoped {
                    val buf = allocArray<kotlinx.cinterop.UShortVar>(count + 1)
                    FPDFText_GetText(textPage, 0, count, buf)
                    val chars = CharArray(count)
                    for (i in 0 until count) chars[i] = buf[i].toInt().toChar()
                    chars.concatToString().trim('\u0000')
                }
            } finally {
                FPDFText_ClosePage(textPage)
            }
        } finally {
            FPDF_ClosePage(page)
        }
    }

    actual fun close() {
        FPDF_CloseDocument(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readMetaTag(doc: COpaquePointer, tag: String): String? = memScoped {
    val size = FPDF_GetMetaText(doc, tag, null, 0u)
    if (size <= 2u) return@memScoped null
    val buf = allocArray<ByteVar>(size.toInt())
    FPDF_GetMetaText(doc, tag, buf, size)
    val bytes = ByteArray(size.toInt() - 2)
    for (i in bytes.indices) bytes[i] = buf[i]
    bytes.decodeUtf16LE().ifEmpty { null }
}

private fun ByteArray.decodeUtf16LE(): String {
    val sb = StringBuilder()
    var i = 0
    while (i + 1 < size) {
        val lo = this[i].toInt() and 0xFF
        val hi = this[i + 1].toInt() and 0xFF
        sb.append(((hi shl 8) or lo).toChar())
        i += 2
    }
    return sb.toString()
}

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument =
    withContext(pdfiumDispatcher) {
        PdfiumInit.ensure()
        val handle: COpaquePointer = bytes.usePinned { pinned ->
            FPDF_LoadMemDocument64(pinned.addressOf(0), bytes.size.convert(), password)
        } ?: error("PDFium refused to open document (err=${FPDF_GetLastError()})")
        PdfDocument(handle)
    }
