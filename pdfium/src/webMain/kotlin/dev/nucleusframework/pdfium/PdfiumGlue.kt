@file:JsModule("./pdfium_glue.mjs")
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * External bindings to `pdfium_glue.mjs`. Shared between jsMain and wasmJsMain via the
 * `webMain` source set — all interfaces extend [JsAny] so the declarations are valid on
 * both platforms. The RPC functions uniformly return `Promise<JsAny?>` because
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

internal external fun openDocument(buffer: ArrayBuffer, password: String?): Promise<JsAny?>
internal external fun closeDocument(doc: Int): Promise<JsAny?>
internal external fun pageSize(doc: Int, pageIndex: Int): Promise<JsAny?>
internal external fun renderPage(doc: Int, pageIndex: Int, w: Int, h: Int, flags: Int): Promise<JsAny?>
internal external fun pageText(doc: Int, pageIndex: Int): Promise<JsAny?>
internal external fun pageTextLayout(doc: Int, pageIndex: Int): Promise<JsAny?>
internal external fun pageLinks(doc: Int, pageIndex: Int): Promise<JsAny?>
