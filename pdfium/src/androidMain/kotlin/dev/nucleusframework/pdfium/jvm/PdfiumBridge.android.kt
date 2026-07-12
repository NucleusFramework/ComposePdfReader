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
    /**
     * Android-only zero-copy render: locks the Bitmap's pixels and writes directly.
     * [form] is an optional FPDF_FORMHANDLE from [nInitFormEnv] — pass 0 to skip widget overlay;
     * non-zero enables FPDF_FFLDraw rendering of form fields and signature appearances.
     */
    @JvmStatic external fun nRenderPageToBitmap(
        page: Long,
        form: Long,
        bitmap: android.graphics.Bitmap,
        width: Int,
        height: Int,
        flags: Int,
    ): Boolean
    /** Init form-fill environment for [doc]. Returns 0 on failure. */
    @JvmStatic external fun nInitFormEnv(doc: Long): Long
    /** Tear down a form handle. Must run before the underlying document is closed. */
    @JvmStatic external fun nCloseFormEnv(form: Long)
    @JvmStatic external fun nGetPageText(page: Long): String?
    @JvmStatic external fun nCountTextRects(page: Long): Int
    @JvmStatic external fun nExtractTextRects(
        page: Long,
        outBoxes: FloatArray,
        outTexts: Array<String?>,
    ): Int
    @JvmStatic external fun nCountPageChars(page: Long): Int
    @JvmStatic external fun nExtractCharBoxes(
        page: Long,
        outCodepoints: IntArray,
        outBoxes: FloatArray,
    ): Int
    /** Count of clickable links (annotations + text-detected web-link rects) on the page. */
    @JvmStatic external fun nCountPageLinks(doc: Long, page: Long): Int
    /**
     * Fill pre-sized arrays with link data: [outBoxes] holds 4 floats per link (left, bottom,
     * right, top in points), [outUris] the target URI or null, [outDestPages] the 0-based
     * GoTo target page or -1, [outIsWeb] whether the link was text-detected.
     */
    @JvmStatic external fun nExtractPageLinks(
        doc: Long,
        page: Long,
        outBoxes: FloatArray,
        outUris: Array<String?>,
        outDestPages: IntArray,
        outIsWeb: BooleanArray,
    ): Int

    @JvmStatic external fun nAllocBuffer(data: ByteArray): Long
    @JvmStatic external fun nFreeBuffer(address: Long)
    @JvmStatic external fun nOpenDocumentFromMemory(address: Long, size: Long, password: String?): Long
}

internal const val FPDF_ANNOT: Int = 0x01
internal const val FPDF_LCD_TEXT: Int = 0x02
internal const val FPDF_REVERSE_BYTE_ORDER: Int = 0x10
