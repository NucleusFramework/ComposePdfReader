package dev.nucleusframework.pdfium

/**
 * Per-document LRU cache of rendered page bitmaps keyed by `(pageIndex, width)`. Height is
 * derivable from the page's aspect so it isn't part of the key. Widths are quantized to
 * 32-pixel buckets so near-identical zoom levels share cache entries instead of
 * fragmenting it.
 *
 * Holds one ref on every [CachedBitmap] it stores. Every eviction releases that ref so the
 * underlying native pixels are freed as soon as the last consumer drops its own ref.
 *
 * Not thread-safe — callers must serialize via a mutex. All cache touches in
 * [PdfReaderState] go through dedicated cache mutexes.
 */
internal class PdfRenderCache(private val maxBytes: Long) {

    private data class Key(val pageIndex: Int, val widthBucket: Int)

    // LinkedHashMap preserves insertion order. To simulate access-order LRU (available only on
    // the JVM stdlib), `get` removes and reinserts the entry to bump it to the tail. Eviction
    // iterates head-first, so the least-recently-used entry is dropped first.
    private val entries = LinkedHashMap<Key, CachedBitmap>()
    private var currentBytes = 0L

    fun get(pageIndex: Int, width: Int): CachedBitmap? {
        val key = Key(pageIndex, quantize(width))
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /** Caller transfers one ref to the cache. */
    fun put(pageIndex: Int, width: Int, handle: CachedBitmap) {
        val key = Key(pageIndex, quantize(width))
        val previous = entries.put(key, handle)
        if (previous != null) {
            currentBytes -= previous.bytes
            previous.release()
        }
        currentBytes += handle.bytes
        evictIfNeeded(protect = key)
    }

    fun clear() {
        for (entry in entries.values) entry.release()
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
            val handle = entry.value
            currentBytes -= handle.bytes
            iter.remove()
            handle.release()
        }
    }

    companion object {
        private const val BUCKET = 32
        private fun quantize(width: Int): Int = ((width + BUCKET - 1) / BUCKET) * BUCKET
    }
}
