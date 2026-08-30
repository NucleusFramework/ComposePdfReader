package dev.nucleusframework.pdfium

internal actual fun evalJs(source: String): Unit = js("eval(source)")
