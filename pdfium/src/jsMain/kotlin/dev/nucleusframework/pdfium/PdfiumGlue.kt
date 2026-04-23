@file:JsModule("./pdfium_glue.mjs")
@file:JsNonModule

package dev.nucleusframework.pdfium

import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * Kotlin/JS bindings to `pdfium_glue.mjs`. Mirrors the wasmJs bindings but with
 * JS-native typing: plain `Array<String>` for string vectors, concrete `Promise<T>`
 * (no `JsAny?` wrapper) and the `@JsNonModule` annotation the JS/IR backend requires
 * when accessing a module declaration under UMD output.
 */
internal external interface OpenResult {
    val doc: Int
    val pageCount: Int
    val title: String?
    val author: String?
    val subject: String?
    val keywords: String?
    val creator: String?
    val producer: String?
}

internal external interface PageSizeResult {
    val widthPoints: Float
    val heightPoints: Float
}

internal external interface RenderResult {
    val pixels: ArrayBuffer
}

internal external interface TextResult {
    val text: String
}

internal external interface TextLayoutResult {
    val widthPoints: Float
    val heightPoints: Float
    val rectBoxes: Float32Array
    val rectTexts: Array<String>
    val charCodepoints: Int32Array
    val charBoxes: Float32Array
}

internal external fun openDocument(buffer: ArrayBuffer, password: String?): Promise<OpenResult>
internal external fun closeDocument(doc: Int): Promise<Unit>
internal external fun pageSize(doc: Int, pageIndex: Int): Promise<PageSizeResult>
internal external fun renderPage(doc: Int, pageIndex: Int, w: Int, h: Int, flags: Int): Promise<RenderResult>
internal external fun pageText(doc: Int, pageIndex: Int): Promise<TextResult>
internal external fun pageTextLayout(doc: Int, pageIndex: Int): Promise<TextLayoutResult>
