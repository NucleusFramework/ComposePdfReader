package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.await
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Kotlin/JS PdfDocument actual. Delegates to the same worker-based `pdfium_glue.mjs`
 * RPC as the wasmJs actual, differing only in the typed-array ⇄ Kotlin array bridges
 * (Kotlin/JS's primitive arrays are JS typed arrays under the hood, so `unsafeCast`
 * yields a zero-copy reinterpretation; Kotlin/Wasm owns separate linear memory and
 * needs an explicit copy).
 */
internal actual class PdfDocument internal constructor(
    private val docPtr: Int,
    actual val pageCount: Int,
    actual val metadata: PdfMetadata,
) {
    actual suspend fun pageSize(pageIndex: Int): PageSize {
        val r = pageSize(docPtr, pageIndex).await()
        return PageSize(r.widthPoints, r.heightPoints)
    }

    actual suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): ImageBitmap {
        val r = renderPage(docPtr, pageIndex, widthPx, heightPx, quality.toFlags()).await()
        // Kotlin/JS's ByteArray runtime type IS Int8Array — reinterpret directly.
        val pixels = Int8Array(r.pixels).unsafeCast<ByteArray>()
        val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
        val bitmap = Bitmap()
        val installed = bitmap.installPixels(info, pixels, widthPx * 4)
        check(installed) { "Skia installPixels returned false" }
        return bitmap.asComposeImageBitmap()
    }

    actual suspend fun pageText(pageIndex: Int): String =
        pageText(docPtr, pageIndex).await().text

    actual suspend fun pageTextLayout(pageIndex: Int): PageTextLayout {
        val r = pageTextLayout(docPtr, pageIndex).await()
        val size = PageSize(r.widthPoints, r.heightPoints)
        // FloatArray ⇄ Float32Array and IntArray ⇄ Int32Array share representation on JS/IR.
        val rectBoxes = r.rectBoxes.unsafeCast<FloatArray>()
        val rectTexts = r.rectTexts
        val charCodepoints = r.charCodepoints.unsafeCast<IntArray>()
        val charBoxes = r.charBoxes.unsafeCast<FloatArray>()
        return PageTextLayout(pageIndex, size, rectBoxes, rectTexts, charCodepoints, charBoxes)
    }

    actual fun close() {
        // Fire-and-forget; the worker frees both the doc handle and the buffer ptr.
        closeDocument(docPtr)
    }

    private fun RenderQuality.toFlags(): Int = when (this) {
        RenderQuality.PREVIEW -> 0
        RenderQuality.FULL -> FPDF_ANNOT
    }

    companion object {
        private const val FPDF_ANNOT: Int = 0x01
    }
}

internal actual suspend fun openPdfDocument(bytes: ByteArray, password: String?): PdfDocument {
    // ByteArray IS Int8Array on Kotlin/JS, so we read its `.buffer` directly — no copy.
    val buffer = bytes.unsafeCast<Int8Array>().buffer
    val r = openDocument(buffer, password).await()
    return PdfDocument(
        docPtr = r.doc,
        pageCount = r.pageCount,
        metadata = PdfMetadata(
            title = r.title,
            author = r.author,
            subject = r.subject,
            keywords = r.keywords,
            creator = r.creator,
            producer = r.producer,
        ),
    )
}
