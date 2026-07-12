@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.js.ExperimentalWasmJsInterop
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Shared web (js + wasmJs) `PdfDocument` actual. pdfium.wasm runs inside a dedicated
 * Web Worker (spawned by `pdfium_glue.mjs`) so the browser's main thread stays free to
 * paint frames while a render is in flight. Everything here is just a thin
 * [awaitTyped] bridge from Kotlin suspension onto the RPC promises the worker returns.
 *
 * Rendered pixel buffers arrive as transferred [org.khronos.webgl.ArrayBuffer]s and are
 * written straight into Skia's wasm linear memory via [passToSkiko] — no intermediate
 * Kotlin [ByteArray]. Platform-specific typed-array bridges live in
 * [WebTypedArrayBridge].
 */
internal actual class PdfDocument internal constructor(
    private val docPtr: Int,
    actual val pageCount: Int,
    actual val metadata: PdfMetadata,
) {
    actual suspend fun pageSize(pageIndex: Int): PageSize {
        val r = pageSize(docPtr, pageIndex).awaitTyped<PageSizeResult>()
        return PageSize(r.widthPoints, r.heightPoints)
    }

    actual suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        quality: RenderQuality,
    ): ImageBitmap {
        val r = renderPage(docPtr, pageIndex, widthPx, heightPx, quality.toFlags()).awaitTyped<RenderResult>()
        // pdfium writes BGRA, matching Skia's native N32 colour type on wasm — no swizzle.
        val skikoData = r.pixels.passToSkiko()
        val info = ImageInfo.makeN32(widthPx, heightPx, ColorAlphaType.PREMUL)
        val image = Image.makeRaster(info, skikoData, widthPx * 4)
        return Bitmap.makeFromImage(image).asComposeImageBitmap()
    }

    actual suspend fun pageText(pageIndex: Int): String =
        pageText(docPtr, pageIndex).awaitTyped<TextResult>().text

    actual suspend fun pageTextLayout(pageIndex: Int): PageTextLayout {
        val r = pageTextLayout(docPtr, pageIndex).awaitTyped<TextLayoutResult>()
        val size = PageSize(r.widthPoints, r.heightPoints)
        val rectBoxes = r.rectBoxes.toSharedFloatArray()
        val rectTexts = Array(r.rectTexts.length) { i -> r.rectTexts[i]?.toString().orEmpty() }
        val charCodepoints = r.charCodepoints.toSharedIntArray()
        val charBoxes = r.charBoxes.toSharedFloatArray()
        return PageTextLayout(pageIndex, size, rectBoxes, rectTexts, charCodepoints, charBoxes)
    }

    actual suspend fun pageLinks(pageIndex: Int): PageLinks {
        val r = pageLinks(docPtr, pageIndex).awaitTyped<PageLinksResult>()
        val size = PageSize(r.widthPoints, r.heightPoints)
        val boxes = r.boxes.toSharedFloatArray()
        val destPages = r.destPages.toSharedIntArray()
        val annotationLinks = ArrayList<PdfLink>(r.annotCount)
        val webLinks = ArrayList<PdfLink>()
        for (i in 0 until destPages.size) {
            val link = PdfLink(
                left = boxes[i * 4],
                bottom = boxes[i * 4 + 1],
                right = boxes[i * 4 + 2],
                top = boxes[i * 4 + 3],
                uri = r.uris[i]?.toString(),
                destPageIndex = destPages[i],
            )
            if (i < r.annotCount) annotationLinks.add(link) else webLinks.add(link)
        }
        return buildPageLinks(pageIndex, size, annotationLinks, webLinks)
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
    // On wasmJs this is a bulk copy into a JS ArrayBuffer, on jsMain it's a zero-copy
    // reinterpretation (ByteArray IS Int8Array). Either way we transfer ownership to
    // the worker — the main thread has no further use for the bytes.
    val buffer = bytes.toJsArrayBuffer()
    val r = openDocument(buffer, password).awaitTyped<OpenResult>()
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
