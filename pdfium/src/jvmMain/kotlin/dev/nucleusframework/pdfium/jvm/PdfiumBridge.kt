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
    /** Zero-copy render: writes directly at [address]. Used with `Bitmap.peekPixels().addr`. */
    @JvmStatic external fun nRenderPageToAddress(
        page: Long,
        address: Long,
        width: Int,
        height: Int,
        swapRedBlue: Boolean,
    ): Boolean
    @JvmStatic external fun nGetPageText(page: Long): String?
}
