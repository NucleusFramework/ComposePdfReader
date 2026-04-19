package dev.nucleusframework.pdfium.jvm

import java.nio.ByteBuffer

/**
 * Android build of the bridge — same signatures and FQN as the desktop JVM object. Android
 * resolves the .so from APK jniLibs automatically; no resource extraction.
 */
internal object PdfiumBridge {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("pdfiumjni")
    }

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
    /** Android-only zero-copy render: locks the Bitmap's pixels and writes directly. */
    @JvmStatic external fun nRenderPageToBitmap(
        page: Long,
        bitmap: android.graphics.Bitmap,
        width: Int,
        height: Int,
    ): Boolean
    @JvmStatic external fun nGetPageText(page: Long): String?
}
