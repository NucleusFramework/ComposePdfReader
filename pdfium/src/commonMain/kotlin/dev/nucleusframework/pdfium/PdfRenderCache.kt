package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Per-document LRU cache of rendered page bitmaps keyed by `(pageIndex, width)`. Height is
 * derivable from the page's aspect so it isn't part of the key. Widths are quantized to
 * 32-pixel buckets so near-identical zoom levels share cache entries instead of
 * fragmenting it.
 *
 * Not thread-safe — callers must serialize via a mutex. All cache touches in
 * [PdfReaderState] go through `cacheMutex`.
 */
internal class PdfRenderCache(private val maxBytes: Long) {

    private data class Key(val pageIndex: Int, val widthBucket: Int)

    private val entries = LinkedHashMap<Key, ImageBitmap>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0L

    fun get(pageIndex: Int, width: Int): ImageBitmap? =
        entries[Key(pageIndex, quantize(width))]

    fun put(pageIndex: Int, width: Int, bitmap: ImageBitmap) {
        val key = Key(pageIndex, quantize(width))
        val previous = entries.put(key, bitmap)
        if (previous != null) currentBytes -= bytesOf(previous)
        currentBytes += bytesOf(bitmap)
        evictIfNeeded(protect = key)
    }

    fun clear() {
        entries.clear()
        currentBytes = 0L
    }

    private fun evictIfNeeded(protect: Key) {
        val iter = entries.entries.iterator()
        while (currentBytes > maxBytes && iter.hasNext()) {
            val entry = iter.next()
            if (entry.key == protect) continue
            iter.remove()
            currentBytes -= bytesOf(entry.value)
        }
    }

    private fun bytesOf(bitmap: ImageBitmap): Long =
        bitmap.width.toLong() * bitmap.height * 4

    companion object {
        private const val BUCKET = 32
        private fun quantize(width: Int): Int = ((width + BUCKET - 1) / BUCKET) * BUCKET
    }
}
