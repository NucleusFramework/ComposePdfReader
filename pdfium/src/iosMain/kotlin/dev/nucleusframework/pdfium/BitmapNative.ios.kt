package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap

/** iOS uses the same Skia-backed ImageBitmap as JVM — close releases native pixels. */
internal actual fun ImageBitmap.freeNativePixels() {
    asSkiaBitmap().close()
}
