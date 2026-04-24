@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array

/**
 * Platform bridges the `webMain` code leans on to stay identical across jsMain and
 * wasmJsMain.
 *
 *  * The typed-array conversions are a zero-cost `unsafeCast` on Kotlin/JS (IR), where
 *    `FloatArray`/`IntArray`/`ByteArray` already ARE the matching typed arrays at
 *    runtime. On Kotlin/Wasm the two live in different linear-memory heaps and a bulk
 *    copy is unavoidable.
 *  * [awaitTyped] papers over the incompatible `kotlinx.coroutines.await` signatures
 *    between jsMain (class-level generic `Promise<T>.await(): T`) and wasmJsMain
 *    (method-level generic `Promise<JsAny?>.await<T>(): T`).
 */
internal expect fun ByteArray.toJsArrayBuffer(): ArrayBuffer

internal expect fun Float32Array.toSharedFloatArray(): FloatArray

internal expect fun Int32Array.toSharedIntArray(): IntArray

internal expect suspend fun <T : JsAny> Promise<JsAny?>.awaitTyped(): T
