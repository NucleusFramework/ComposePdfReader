package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Refcounted wrapper around an [ImageBitmap] whose native pixel memory (Skia Bitmap on JVM/iOS,
 * android.graphics.Bitmap on Android) is freed deterministically when the last reference is
 * dropped — not when the JVM eventually decides to GC.
 *
 * Ownership convention: a freshly constructed handle starts with refs = 1 (one ref held by
 * the creator). Every consumer that wants to hold the bitmap beyond the immediate call must
 * call [retain]. Every ref holder must call [release] exactly once.
 *
 * When refs hits 0, [onRelease] runs and frees the native pixels. After that the [imageBitmap]
 * is unsafe to use — callers must drop their reference before [release].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CachedBitmap(
    val imageBitmap: ImageBitmap,
    val bytes: Long,
    private val onRelease: () -> Unit,
) {
    private val refs = AtomicInt(1)

    fun retain(): CachedBitmap {
        val next = refs.incrementAndFetch()
        check(next > 1) { "retain after release (refs reached $next)" }
        return this
    }

    fun release() {
        val next = refs.decrementAndFetch()
        check(next >= 0) { "double release (refs went to $next)" }
        if (next == 0) onRelease()
    }
}

/** Platform-specific: free the native pixel memory backing this bitmap. */
internal expect fun ImageBitmap.freeNativePixels()

/**
 * Wraps an [ImageBitmap] fresh from a PDFium render into a [CachedBitmap]. Bytes are
 * computed from the bitmap's dimensions assuming 4-byte RGBA (the pixel format both the
 * JVM/iOS Skia backend and the Android backend allocate for N32 bitmaps).
 */
internal fun ImageBitmap.wrapCached(): CachedBitmap {
    val bytes = width.toLong() * height * 4
    return CachedBitmap(this, bytes) { freeNativePixels() }
}
