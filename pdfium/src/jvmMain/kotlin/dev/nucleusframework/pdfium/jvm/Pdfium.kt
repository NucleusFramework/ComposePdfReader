package dev.nucleusframework.pdfium.jvm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PDFium is not thread-safe. A single-threaded dispatcher per document gives us:
 *  - per-call serialization for a given document (no concurrent clobber of PDFium state)
 *  - independent docs render in parallel (no global lock)
 *  - stable carrier thread (useful for any thread-affine PDFium state)
 */
internal object Pdfium {
    private val counter = AtomicInteger()

    /**
     * Shared dispatcher for library-level operations (init, open, close). A shared single
     * thread prevents races during PDFium's global state setup. Per-document dispatchers
     * (from [newDispatcher]) are used for everything else so documents render in parallel.
     */
    val sharedDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-shared").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Create a fresh single-threaded dispatcher + the underlying executor. Caller owns shutdown. */
    fun newDispatcher(): Pair<CoroutineDispatcher, ExecutorService> {
        val id = counter.incrementAndGet()
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pdfium-worker-$id").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
        return exec.asCoroutineDispatcher() to exec
    }
}
