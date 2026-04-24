package dev.nucleusframework.pdfium

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int32Array
import org.khronos.webgl.toFloatArray
import org.khronos.webgl.toInt8Array
import org.khronos.webgl.toIntArray

// Kotlin/Wasm owns its own linear memory, separate from the JS engine heap where typed
// arrays live — every conversion here is an unavoidable bulk copy.

internal actual fun ByteArray.toJsArrayBuffer(): ArrayBuffer =
    this.toInt8Array().buffer

internal actual fun Float32Array.toSharedFloatArray(): FloatArray =
    this.toFloatArray()

internal actual fun Int32Array.toSharedIntArray(): IntArray =
    this.toIntArray()

internal actual suspend fun <T : JsAny> Promise<JsAny?>.awaitTyped(): T =
    this.await()

@Suppress("INVISIBLE_REFERENCE")
internal actual suspend fun awaitSkiko(): JsAny =
    org.jetbrains.skiko.wasm.awaitSkiko.await()
