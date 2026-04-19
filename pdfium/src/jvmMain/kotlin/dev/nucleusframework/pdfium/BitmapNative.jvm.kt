package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap

/**
 * Closes the underlying Skia [org.jetbrains.skia.Bitmap], releasing its native pixel buffer
 * immediately instead of waiting for the Cleaner to run on a GC cycle that a small JVM heap
 * rarely triggers.
 */
internal actual fun ImageBitmap.freeNativePixels() {
    asSkiaBitmap().close()
}
