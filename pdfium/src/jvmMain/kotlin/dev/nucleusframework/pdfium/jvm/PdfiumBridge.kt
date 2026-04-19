package dev.nucleusframework.pdfium.jvm

import java.nio.ByteBuffer

/**
 * Low-level JNI surface. Methods are `external`; their native implementations live in
 * `src/jvmMain/native/pdfium_jni.cpp` (desktop) and the same source compiled via Android CMake.
 *
 * Handles are opaque `Long`s — **never** dereference them from Kotlin. Always null-check
 * returns: a `0` handle means PDFium refused the request (inspect [nGetLastError]).
 */
internal object PdfiumBridge {
    init { PdfiumLibraryLoader.ensureLoaded() }

    @JvmStatic external fun nOpenDocument(data: ByteArray, password: String?): Long
    @JvmStatic external fun nGetLastError(): Int
    @JvmStatic external fun nGetPageCount(doc: Long): Int
    @JvmStatic external fun nGetMeta(doc: Long, tag: String): String?
    @JvmStatic external fun nLoadPage(doc: Long, index: Int): Long
    @JvmStatic external fun nGetPageWidth(page: Long): Float
    @JvmStatic external fun nGetPageHeight(page: Long): Float
    @JvmStatic external fun nClosePage(page: Long)
    @JvmStatic external fun nCloseDocument(doc: Long)
    @JvmStatic external fun nRenderPage(
        page: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        swapRedBlue: Boolean,
    ): Boolean
    /** Zero-copy render: writes directly at [address]. Flags = `FPDF_ANNOT | FPDF_LCD_TEXT | …`. */
    @JvmStatic external fun nRenderPageToAddress(
        page: Long,
        address: Long,
        width: Int,
        height: Int,
        flags: Int,
    ): Boolean
    @JvmStatic external fun nGetPageText(page: Long): String?
    /** Count of line-level text rectangles on the given page. */
    @JvmStatic external fun nCountTextRects(page: Long): Int
    /** Fill pre-sized arrays with rect coords (left, bottom, right, top in points) + UTF-8 text. */
    @JvmStatic external fun nExtractTextRects(
        page: Long,
        outBoxes: FloatArray,
        outTexts: Array<String?>,
    ): Int
    /** Count of per-glyph characters (includes spaces and generated chars). */
    @JvmStatic external fun nCountPageChars(page: Long): Int
    /**
     * Fill [outCodepoints] and [outBoxes] with per-character data. [outBoxes] holds 4 floats
     * per char: left, bottom, right, top in PDF page points.
     */
    @JvmStatic external fun nExtractCharBoxes(
        page: Long,
        outCodepoints: IntArray,
        outBoxes: FloatArray,
    ): Int

    // Shared-buffer document pool support:
    /** Allocate a native buffer holding [data]. Returns a raw pointer; free with [nFreeBuffer]. */
    @JvmStatic external fun nAllocBuffer(data: ByteArray): Long
    @JvmStatic external fun nFreeBuffer(address: Long)
    /** Open a document from a pre-allocated buffer (no internal copy). */
    @JvmStatic external fun nOpenDocumentFromMemory(address: Long, size: Long, password: String?): Long
}

// Render flag constants — match fpdfview.h.
internal const val FPDF_ANNOT: Int = 0x01
internal const val FPDF_LCD_TEXT: Int = 0x02
internal const val FPDF_REVERSE_BYTE_ORDER: Int = 0x10
