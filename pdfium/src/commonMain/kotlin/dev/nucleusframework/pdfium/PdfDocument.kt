package dev.nucleusframework.pdfium

/**
 * Opaque handle to a native PDFium document. Owns per-platform resources that MUST be freed
 * with [close]. Operations are internally serialized on a background dispatcher — callers may
 * invoke them from any context.
 */
internal expect class PdfDocument {
    val pageCount: Int
    val metadata: PdfMetadata

    suspend fun pageSize(pageIndex: Int): PageSize

    /**
     * Render [pageIndex] into an RGBA bitmap sized [widthPx] × [heightPx] and return a Compose
     * [androidx.compose.ui.graphics.ImageBitmap]. [quality] controls PDFium's rendering flags.
     */
    suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): androidx.compose.ui.graphics.ImageBitmap

    /** Extract the Unicode text content of [pageIndex]. Returns empty string if the page has no text. */
    suspend fun pageText(pageIndex: Int): String

    fun close()
}

internal expect suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument

data class PageSize(val widthPoints: Float, val heightPoints: Float) {
    val aspectRatio: Float get() = if (heightPoints <= 0f) 1f else widthPoints / heightPoints
}
