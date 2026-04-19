package dev.nucleusframework.pdfium.jvm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PDFium is not thread-safe. Every call into the C API — open, load page, render, text
 * extraction, close — MUST happen on the same thread (or at least be mutually exclusive).
 * A single-threaded dispatcher gives us both: serialization and a stable carrier thread.
 */
internal object Pdfium {
    private val counter = AtomicInteger()
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-worker-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()
}
