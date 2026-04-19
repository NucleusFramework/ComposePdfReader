package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.nucleusframework.pdfium.native.FPDFBitmap_BGRA
import dev.nucleusframework.pdfium.native.FPDFBitmap_CreateEx
import dev.nucleusframework.pdfium.native.FPDFBitmap_Destroy
import dev.nucleusframework.pdfium.native.FPDFBitmap_FillRect
import dev.nucleusframework.pdfium.native.FPDFText_ClosePage
import dev.nucleusframework.pdfium.native.FPDFText_CountChars
import dev.nucleusframework.pdfium.native.FPDFText_CountRects
import dev.nucleusframework.pdfium.native.FPDFText_GetBoundedText
import dev.nucleusframework.pdfium.native.FPDFText_GetCharBox
import dev.nucleusframework.pdfium.native.FPDFText_GetRect
import dev.nucleusframework.pdfium.native.FPDFText_GetText
import dev.nucleusframework.pdfium.native.FPDFText_GetUnicode
import dev.nucleusframework.pdfium.native.FPDFText_LoadPage
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.value
import dev.nucleusframework.pdfium.native.FPDF_ANNOT
import dev.nucleusframework.pdfium.native.FPDF_CloseDocument
import dev.nucleusframework.pdfium.native.FPDF_ClosePage
import dev.nucleusframework.pdfium.native.FPDF_GetLastError
import dev.nucleusframework.pdfium.native.FPDF_GetMetaText
import dev.nucleusframework.pdfium.native.FPDF_GetPageCount
import dev.nucleusframework.pdfium.native.FPDF_GetPageHeightF
import dev.nucleusframework.pdfium.native.FPDF_GetPageWidthF
import dev.nucleusframework.pdfium.native.FPDF_InitLibraryWithConfig
import dev.nucleusframework.pdfium.native.FPDF_LIBRARY_CONFIG
import dev.nucleusframework.pdfium.native.FPDF_LoadMemDocument64
import dev.nucleusframework.pdfium.native.FPDF_LoadPage
import dev.nucleusframework.pdfium.native.FPDF_RenderPageBitmap
import cnames.structs.fpdf_document_t__
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

// PDFium is not thread-safe. Serialize every call through a single-threaded dispatcher.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val pdfiumDispatcher = Dispatchers.Default.limitedParallelism(1)

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
    private val handle: CPointer<fpdf_document_t__>,
    // PDFium keeps a borrowed reference to the source byte buffer for the document's lifetime.
    // The pin must stay alive until close() or PDFium will dereference freed memory on the
    // next FPDF_LoadPage / FPDF_GetMetaText call.
    private val pinnedBuffer: Pinned<ByteArray>,
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

    actual suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): ImageBitmap = withContext(pdfiumDispatcher) {
        val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
        val rowBytes = widthPx * 4
        val bitmap = Bitmap().apply { allocPixels(info) }
        val pixmap = bitmap.peekPixels() ?: error("peekPixels returned null")
        val addr = interpretCPointer<ByteVar>(pixmap.addr)
            ?: error("Pixmap address is null")
        val page = FPDF_LoadPage(handle, pageIndex)
            ?: error("PDFium load page failed (err=${FPDF_GetLastError()})")
        try {
            val bmp = FPDFBitmap_CreateEx(widthPx, heightPx, FPDFBitmap_BGRA, addr, rowBytes)
                ?: error("FPDFBitmap_CreateEx returned null")
            FPDFBitmap_FillRect(bmp, 0, 0, widthPx, heightPx, 0xFFFFFFFFu.toULong())
            val flags = when (quality) {
                RenderQuality.PREVIEW -> 0
                RenderQuality.FULL -> FPDF_ANNOT
            }
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
                    val buf = allocArray<UShortVar>(count + 1)
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

    actual suspend fun pageTextLayout(pageIndex: Int): PageTextLayout = withContext(pdfiumDispatcher) {
        val page = FPDF_LoadPage(handle, pageIndex) ?: return@withContext PageTextLayout.Empty
        try {
            val size = PageSize(
                widthPoints = FPDF_GetPageWidthF(page),
                heightPoints = FPDF_GetPageHeightF(page),
            )
            val textPage = FPDFText_LoadPage(page) ?: return@withContext PageTextLayout(
                pageIndex, size, FloatArray(0), emptyArray(), IntArray(0), FloatArray(0),
            )
            try {
                // Rect level.
                val rectTotal = FPDFText_CountRects(textPage, 0, -1)
                val rectBoxes: FloatArray
                val rectTexts: Array<String>
                if (rectTotal <= 0) {
                    rectBoxes = FloatArray(0)
                    rectTexts = emptyArray()
                } else {
                    rectBoxes = FloatArray(rectTotal * 4)
                    rectTexts = Array(rectTotal) { "" }
                    memScoped {
                        val l = alloc<DoubleVar>()
                        val t = alloc<DoubleVar>()
                        val r = alloc<DoubleVar>()
                        val b = alloc<DoubleVar>()
                        for (i in 0 until rectTotal) {
                            FPDFText_GetRect(textPage, i, l.ptr, t.ptr, r.ptr, b.ptr)
                            rectBoxes[i * 4 + 0] = l.value.toFloat()
                            rectBoxes[i * 4 + 1] = b.value.toFloat()
                            rectBoxes[i * 4 + 2] = r.value.toFloat()
                            rectBoxes[i * 4 + 3] = t.value.toFloat()
                            val needed = FPDFText_GetBoundedText(
                                textPage, l.value, t.value, r.value, b.value, null, 0,
                            )
                            if (needed > 1) {
                                val buf = allocArray<UShortVar>(needed)
                                FPDFText_GetBoundedText(
                                    textPage, l.value, t.value, r.value, b.value, buf, needed,
                                )
                                val chars = CharArray(needed - 1)
                                for (j in 0 until needed - 1) chars[j] = buf[j].toInt().toChar()
                                rectTexts[i] = chars.concatToString().trim('\u0000')
                            }
                        }
                    }
                }
                // Char level.
                val charTotal = FPDFText_CountChars(textPage)
                val charCodepoints: IntArray
                val charBoxes: FloatArray
                if (charTotal <= 0) {
                    charCodepoints = IntArray(0)
                    charBoxes = FloatArray(0)
                } else {
                    charCodepoints = IntArray(charTotal)
                    charBoxes = FloatArray(charTotal * 4)
                    memScoped {
                        val l = alloc<DoubleVar>()
                        val r = alloc<DoubleVar>()
                        val b = alloc<DoubleVar>()
                        val t = alloc<DoubleVar>()
                        for (i in 0 until charTotal) {
                            charCodepoints[i] = FPDFText_GetUnicode(textPage, i).toInt()
                            FPDFText_GetCharBox(textPage, i, l.ptr, r.ptr, b.ptr, t.ptr)
                            charBoxes[i * 4 + 0] = l.value.toFloat()
                            charBoxes[i * 4 + 1] = b.value.toFloat()
                            charBoxes[i * 4 + 2] = r.value.toFloat()
                            charBoxes[i * 4 + 3] = t.value.toFloat()
                        }
                    }
                }
                PageTextLayout(pageIndex, size, rectBoxes, rectTexts, charCodepoints, charBoxes)
            } finally {
                FPDFText_ClosePage(textPage)
            }
        } finally {
            FPDF_ClosePage(page)
        }
    }

    actual fun close() {
        FPDF_CloseDocument(handle)
        pinnedBuffer.unpin()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readMetaTag(doc: CPointer<fpdf_document_t__>, tag: String): String? = memScoped {
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
        val pinned = bytes.pin()
        val handle = FPDF_LoadMemDocument64(pinned.addressOf(0), bytes.size.convert(), password)
        if (handle == null) {
            val err = FPDF_GetLastError()
            pinned.unpin()
            error("PDFium refused to open document (err=$err)")
        }
        PdfDocument(handle, pinned)
    }
