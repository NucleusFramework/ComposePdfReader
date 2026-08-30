@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * Bindings to the pdfium Web Worker RPC. Shared between jsMain and wasmJsMain via
 * `webMain`. The glue script is eval'd into globalThis (see [evalJs] / [PDFIUM_GLUE_JS])
 * instead of `@JsModule("./pdfium_glue.mjs")`, because that relative import is resolved
 * by webpack against the consumer's kotlin package dir and is invisible when the file
 * only exists as a klib resource (issue #11).
 *
 * The RPC functions uniformly return `Promise<JsAny?>` because
 * `kotlinx.coroutines.await` on wasmJs is declared on that receiver type; call sites go
 * through [awaitTyped] to recover the typed fulfilment value.
 */
internal external interface OpenResult : JsAny {
    val doc: Int
    val pageCount: Int
    val title: String?
    val author: String?
    val subject: String?
    val keywords: String?
    val creator: String?
    val producer: String?
}

internal external interface PageSizeResult : JsAny {
    val widthPoints: Float
    val heightPoints: Float
}

internal external interface RenderResult : JsAny {
    val pixels: ArrayBuffer
}

internal external interface TextResult : JsAny {
    val text: String
}

internal external interface TextLayoutResult : JsAny {
    val widthPoints: Float
    val heightPoints: Float
    val rectBoxes: Float32Array
    val rectTexts: JsArray<JsString>
    val charCodepoints: Int32Array
    val charBoxes: Float32Array
}

internal external interface PageLinksResult : JsAny {
    val widthPoints: Float
    val heightPoints: Float
    /** Entries below this index are link annotations; the rest are text-detected web links. */
    val annotCount: Int
    val boxes: Float32Array
    val uris: JsArray<JsString>
    val destPages: Int32Array
}

internal expect fun evalJs(source: String)

private val pdfiumGlueLoaded: Unit by lazy { evalJs(PDFIUM_GLUE_JS) }

internal fun openDocument(buffer: ArrayBuffer, password: String?): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumOpenDocument(buffer, password)
}

internal fun closeDocument(doc: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumCloseDocument(doc)
}

internal fun pageSize(doc: Int, pageIndex: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumPageSize(doc, pageIndex)
}

internal fun renderPage(doc: Int, pageIndex: Int, w: Int, h: Int, flags: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumRenderPage(doc, pageIndex, w, h, flags)
}

internal fun pageText(doc: Int, pageIndex: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumPageText(doc, pageIndex)
}

internal fun pageTextLayout(doc: Int, pageIndex: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumPageTextLayout(doc, pageIndex)
}

internal fun pageLinks(doc: Int, pageIndex: Int): Promise<JsAny?> {
    pdfiumGlueLoaded
    return pdfiumPageLinks(doc, pageIndex)
}

@JsName("pdfiumOpenDocument")
internal external fun pdfiumOpenDocument(buffer: ArrayBuffer, password: String?): Promise<JsAny?>

@JsName("pdfiumCloseDocument")
internal external fun pdfiumCloseDocument(doc: Int): Promise<JsAny?>

@JsName("pdfiumPageSize")
internal external fun pdfiumPageSize(doc: Int, pageIndex: Int): Promise<JsAny?>

@JsName("pdfiumRenderPage")
internal external fun pdfiumRenderPage(doc: Int, pageIndex: Int, w: Int, h: Int, flags: Int): Promise<JsAny?>

@JsName("pdfiumPageText")
internal external fun pdfiumPageText(doc: Int, pageIndex: Int): Promise<JsAny?>

@JsName("pdfiumPageTextLayout")
internal external fun pdfiumPageTextLayout(doc: Int, pageIndex: Int): Promise<JsAny?>

@JsName("pdfiumPageLinks")
internal external fun pdfiumPageLinks(doc: Int, pageIndex: Int): Promise<JsAny?>
