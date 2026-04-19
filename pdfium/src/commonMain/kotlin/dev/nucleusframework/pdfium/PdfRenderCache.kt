package dev.nucleusframework.pdfium

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Per-document LRU cache of rendered page bitmaps keyed by `(pageIndex, width)`. Height is
 * derivable from the page's aspect so it isn't part of the key. Widths are quantized to
 * 32-pixel buckets so near-identical zoom levels share cache entries instead of
 * fragmenting it.
 *
 * Eviction drops the cache's reference to the [ImageBitmap]; native pixel memory (Skia or
 * Android Bitmap) is reclaimed later when the JVM GCs the wrapper. Explicit native close is
 * **not** safe: Compose retains bitmaps via draw-command/layer caches we can't track, and
 * closing mid-draw segfaults. Sizing the cache conservatively (see [PdfReaderState] defaults)
 * keeps the working set small enough that GC frees pixels on time.
 *
 * Not thread-safe — callers must serialize via a mutex.
 */
internal class PdfRenderCache(private val maxBytes: Long) {

    private data class Key(val pageIndex: Int, val widthBucket: Int)

    // LinkedHashMap preserves insertion order. To simulate access-order LRU (available only on
    // the JVM stdlib), `get` removes and reinserts the entry to bump it to the tail. Eviction
    // iterates head-first, so the least-recently-used entry is dropped first.
    private val entries = LinkedHashMap<Key, ImageBitmap>()
    private var currentBytes = 0L

    fun get(pageIndex: Int, width: Int): ImageBitmap? {
        val key = Key(pageIndex, quantize(width))
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

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
            // Read the value before iter.remove(): Kotlin/Native's HashMap EntryRef throws
            // ConcurrentModificationException if .value is touched after the backing map changed.
            val evicted = bytesOf(entry.value)
            iter.remove()
            currentBytes -= evicted
        }
    }

    private fun bytesOf(bitmap: ImageBitmap): Long =
        bitmap.width.toLong() * bitmap.height * 4

    companion object {
        private const val BUCKET = 32
        private fun quantize(width: Int): Int = ((width + BUCKET - 1) / BUCKET) * BUCKET
    }
}
