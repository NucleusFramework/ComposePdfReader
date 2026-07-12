package dev.nucleusframework.pdfium.jvm

import dev.nucleusframework.pdfium.RenderQuality
import dev.nucleusframework.pdfium.openPdfDocument
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) = runBlocking {
    val path = args.firstOrNull() ?: "/usr/share/cups/data/classified.pdf"
    val bytes = File(path).readBytes()

    // 1) Raw bridge smoke
    val rawDoc = PdfiumBridge.nOpenDocument(bytes, null)
    require(rawDoc != 0L) { "open failed err=${PdfiumBridge.nGetLastError()}" }
    val rawPage = PdfiumBridge.nLoadPage(rawDoc, 0)
    require(rawPage != 0L) { "load page failed err=${PdfiumBridge.nGetLastError()}" }
    val w = PdfiumBridge.nGetPageWidth(rawPage)
    val h = PdfiumBridge.nGetPageHeight(rawPage)
    println("[bridge] page0 size=${w}x${h}pts")
    val buf = ByteBuffer.allocateDirect(400 * (400f / (w / h)).toInt().coerceAtLeast(1) * 4)
        .order(ByteOrder.nativeOrder())
    val ok = PdfiumBridge.nRenderPage(rawPage, buf, 400, (400f / (w / h)).toInt().coerceAtLeast(1), true)
    require(ok) { "render failed" }
    println("[bridge] render ok")
    PdfiumBridge.nClosePage(rawPage)
    PdfiumBridge.nCloseDocument(rawDoc)

    // 2) Kotlin expect/actual + coroutine dispatchers
    val doc = openPdfDocument(bytes, null)
    println("[kotlin] pages=${doc.pageCount} meta=${doc.metadata}")
    val size = doc.pageSize(0)
    println("[kotlin] page0=${size.widthPoints}x${size.heightPoints}pts")
    val bmp = doc.renderPage(0, 600, (600 / size.aspectRatio).toInt(), RenderQuality.FULL)
    println("[kotlin] ImageBitmap ${bmp.width}x${bmp.height}")
    val text = doc.pageText(0)
    println("[kotlin] page0 text length=${text.length} preview=${text.take(60).replace("\n", "⏎")}")
    val links = doc.pageLinks(0)
    println("[kotlin] page0 links=${links.links.size}")
    for (link in links.links) {
        println(
            "  uri=${link.uri} dest=${link.destPageIndex} " +
                "rect=(${link.left}, ${link.bottom}, ${link.right}, ${link.top})",
        )
    }

    // Concurrency stress: fan out N parallel render+text+size calls, mirroring the sample app.
    println("[stress] launching 64 parallel render+text+size calls…")
    coroutineScope {
        repeat(64) {
            launch { doc.renderPage(0, 120, (120 / size.aspectRatio).toInt(), RenderQuality.PREVIEW) }
            launch { doc.pageSize(0) }
            launch { doc.pageText(0) }
        }
    }
    println("[stress] OK — no crash under concurrent access")

    doc.close()
    println("OK")
}
