@file:JsModule("./pdfium_glue.mjs")

package dev.nucleusframework.pdfium

import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * External bindings to `pdfium_glue.mjs`. Every call goes over a Web Worker boundary —
 * the JS side returns a [Promise] that resolves once the worker posts back its result.
 * Typed arrays (`Float32Array`, `Int32Array`) and the pixel `ArrayBuffer` are moved via
 * `postMessage` transferables, so they reach the main thread without a structured-clone
 * copy. Handles (document / page pointers) are plain [Int]s into the worker's pdfium
 * heap; the main thread never dereferences them.
 *
 * The `Promise<JsAny?>` return type matches the shape expected by
 * `kotlinx.coroutines.await`'s signature on wasmJs (which is declared as
 * `Promise<JsAny?>.await(): T`); the actual fulfilment values implement the typed
 * result interfaces below and are cast at call sites.
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

internal external fun openDocument(buffer: ArrayBuffer, password: String?): Promise<JsAny?>
internal external fun closeDocument(doc: Int): Promise<JsAny?>
internal external fun pageSize(doc: Int, pageIndex: Int): Promise<JsAny?>
internal external fun renderPage(doc: Int, pageIndex: Int, w: Int, h: Int, flags: Int): Promise<JsAny?>
internal external fun pageText(doc: Int, pageIndex: Int): Promise<JsAny?>
internal external fun pageTextLayout(doc: Int, pageIndex: Int): Promise<JsAny?>
