@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Int8Array

// FloatArray ⇄ Float32Array, IntArray ⇄ Int32Array and ByteArray ⇄ Int8Array share
// runtime representation on Kotlin/JS (IR) — reinterpret directly, no copy.

internal actual fun ByteArray.toJsArrayBuffer(): ArrayBuffer =
    this.unsafeCast<Int8Array>().buffer

internal actual fun Float32Array.toSharedFloatArray(): FloatArray =
    this.unsafeCast<FloatArray>()

internal actual fun Int32Array.toSharedIntArray(): IntArray =
    this.unsafeCast<IntArray>()

internal actual suspend fun <T : JsAny> Promise<JsAny?>.awaitTyped(): T =
    this.await().unsafeCast<T>()

internal actual suspend fun awaitSkiko(): JsAny =
    org.jetbrains.skiko.wasm.awaitSkiko.await()
