package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Recycles the backing Android [android.graphics.Bitmap], freeing its pixel memory immediately. */
internal actual fun ImageBitmap.freeNativePixels() {
    val bmp = asAndroidBitmap()
    if (!bmp.isRecycled) bmp.recycle()
}
