package dev.nucleusframework.pdfium.jvm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal object Pdfium {
    val sharedDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-shared").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
