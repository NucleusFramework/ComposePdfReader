package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.await
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.khronos.webgl.toFloatArray
import org.khronos.webgl.toInt8Array
import org.khronos.webgl.toIntArray

/**
 * wasmJs PDF document. pdfium.wasm runs inside a dedicated Web Worker (spawned by
 * `pdfium_glue.mjs`) so the browser's main thread stays free to paint frames while a
 * render is in flight. Everything here is just a thin [await] bridge from Kotlin
 * suspension onto the RPC promises the worker returns.
 *
 * The only unavoidable main-thread copy happens inside [renderPage]: the worker's
 * pixel `ArrayBuffer` arrives transferred (zero-copy), then we bulk-copy once into a
 * Kotlin [ByteArray] for Skiko's `installPixels` (Skia has its own wasm heap and
 * exposes no ArrayBuffer-direct install on this Skiko version).
 */
internal actual class PdfDocument internal constructor(
    private val docPtr: Int,
    actual val pageCount: Int,
    actual val metadata: PdfMetadata,
) {
    actual suspend fun pageSize(pageIndex: Int): PageSize {
        val r = pageSize(docPtr, pageIndex).await<PageSizeResult>()
        return PageSize(r.widthPoints, r.heightPoints)
    }

    actual suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): ImageBitmap {
        val r = renderPage(docPtr, pageIndex, widthPx, heightPx, quality.toFlags()).await<RenderResult>()
        // pdfium writes BGRA, matching Skia's native N32 colour type on wasm — no swizzle.
        val pixels = Int8Array(r.pixels).toByteArray()
        val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
        val bitmap = Bitmap()
        val installed = bitmap.installPixels(info, pixels, widthPx * 4)
        check(installed) { "Skia installPixels returned false" }
        return bitmap.asComposeImageBitmap()
    }

    actual suspend fun pageText(pageIndex: Int): String =
        pageText(docPtr, pageIndex).await<TextResult>().text

    actual suspend fun pageTextLayout(pageIndex: Int): PageTextLayout {
        val r = pageTextLayout(docPtr, pageIndex).await<TextLayoutResult>()
        val size = PageSize(r.widthPoints, r.heightPoints)
        val rectBoxes = r.rectBoxes.toFloatArray()
        val rectTexts = Array(r.rectTexts.length) { i -> r.rectTexts[i]?.toString().orEmpty() }
        val charCodepoints = r.charCodepoints.toIntArray()
        val charBoxes = r.charBoxes.toFloatArray()
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
    // Move the PDF bytes into a JS ArrayBuffer (bulk copy) and transfer ownership to
    // the worker — the main thread has no further use for them.
    val buffer = bytes.toInt8Array().buffer
    val r = openDocument(buffer, password).await<OpenResult>()
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
