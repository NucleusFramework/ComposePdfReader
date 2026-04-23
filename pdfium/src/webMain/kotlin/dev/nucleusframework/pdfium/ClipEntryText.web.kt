package dev.nucleusframework.pdfium

import androidx.compose.ui.platform.ClipEntry

// `ClipEntry.withPlainText` ships identically on Kotlin/JS and Kotlin/WasmJS (see
// Compose 1.10's `PlatformClipboard.{js,wasm}.kt`) so a single webMain actual fits both.
actual fun textClipEntry(text: String): ClipEntry =
    ClipEntry.withPlainText(text)
