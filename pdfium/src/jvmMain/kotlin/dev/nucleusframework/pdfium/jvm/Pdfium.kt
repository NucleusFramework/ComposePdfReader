package dev.nucleusframework.pdfium.jvm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * PDFium is not thread-safe, including across documents (FreeType's `FT_Library` is a
 * process-wide singleton). Every PDFium call runs on [sharedDispatcher], which gives us:
 *  - global serialization (no concurrent clobber of PDFium or FreeType state)
 *  - stable carrier thread (useful for any thread-affine PDFium state)
 */
internal object Pdfium {
    val sharedDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-shared").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
