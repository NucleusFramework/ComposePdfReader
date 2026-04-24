@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.nucleusframework.pdfium

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import org.jetbrains.skia.Data
import org.jetbrains.skia.impl.NativePointer
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * Copy the contents of a (typically worker-transferred) [ArrayBuffer] straight into
 * Skia's wasm linear memory. Pattern lifted from coil3.decode.WebWorker:
 *  1. allocate uninitialised storage inside Skia's heap via [Data.makeUninitialized],
 *  2. fetch Skia's wasm memory buffer,
 *  3. `Int8Array.set` the source bytes at the offset of the freshly allocated [Data].
 *
 * The returned [Data] can be handed to [org.jetbrains.skia.Image.makeRaster] without
 * any further copy — skipping the usual `ByteArray` + `installPixels` round-trip.
 */
internal suspend fun ArrayBuffer.passToSkiko(): Data {
    val data = Data.makeUninitialized(byteLength)
    val skikoMemory = getSkikoMemory(awaitSkiko())
    skikoMemory.set(this, data.writableData())
    return data
}

internal expect suspend fun awaitSkiko(): JsAny

private fun getSkikoMemory(skikoWasm: JsAny): ArrayBuffer =
    js("skikoWasm.wasmExports.memory.buffer")

private fun ArrayBuffer.set(data: ArrayBuffer, offset: NativePointer) {
    Int8Array(this).set(Int8Array(data), offset)
}
