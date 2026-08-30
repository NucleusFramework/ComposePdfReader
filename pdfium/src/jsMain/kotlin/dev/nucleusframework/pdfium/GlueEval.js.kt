package dev.nucleusframework.pdfium

internal actual fun evalJs(source: String) {
    val src = source
    js("eval(src)")
}
