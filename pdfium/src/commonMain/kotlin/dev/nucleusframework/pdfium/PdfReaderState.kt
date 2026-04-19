package dev.nucleusframework.pdfium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
class PdfReaderState internal constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var document: PdfDocument? = null

    var pageCount: Int by mutableStateOf(0)
        private set
    var isLoading: Boolean by mutableStateOf(false)
        private set
    var error: PdfError? by mutableStateOf(null)
        private set
    var metadata: PdfMetadata by mutableStateOf(PdfMetadata())
        private set

    /** Render scale multiplier applied to point-sized dimensions (1f = 72dpi). */
    var renderScale: Float by mutableStateOf(2f)

    suspend fun open(bytes: ByteArray, password: String? = null) = mutex.withLock {
        isLoading = true
        error = null
        try {
            document?.close()
            document = null
            val doc = openPdfDocument(bytes, password)
            document = doc
            pageCount = doc.pageCount
            metadata = doc.metadata
        } catch (t: Throwable) {
            error = PdfError.NativeFailure(t.message ?: "Unable to open PDF", t)
            pageCount = 0
            metadata = PdfMetadata()
        } finally {
            isLoading = false
        }
    }

    internal suspend fun pageSize(pageIndex: Int): PageSize? =
        document?.pageSize(pageIndex)

    internal suspend fun renderPage(pageIndex: Int, widthPx: Int, heightPx: Int): ImageBitmap? {
        val doc = document ?: return null
        if (widthPx <= 0 || heightPx <= 0 || pageIndex !in 0 until pageCount) return null
        return doc.renderPage(pageIndex, widthPx, heightPx)
    }

    suspend fun pageText(pageIndex: Int): String {
        val doc = document ?: return ""
        if (pageIndex !in 0 until pageCount) return ""
        return doc.pageText(pageIndex)
    }

    fun dispose() {
        scope.launch {
            mutex.withLock {
                document?.close()
                document = null
            }
        }.invokeOnCompletion { scope.cancel() }
    }

    internal fun launchOnScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}

@Composable
fun rememberPdfReaderState(): PdfReaderState {
    val state = remember { PdfReaderState() }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}
