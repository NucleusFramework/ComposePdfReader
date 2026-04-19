package dev.nucleusframework.pdfium.jvm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object Pdfium {
    private val counter = AtomicInteger()

    val sharedDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pdfium-shared").apply { isDaemon = true }
    }.asCoroutineDispatcher()

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
