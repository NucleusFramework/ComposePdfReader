package dev.nucleusframework.pdfium

import androidx.compose.ui.platform.ClipEntry

/**
 * Builds a plain-text [ClipEntry] for the current platform. Compose 1.10's common API only
 * exposes `Clipboard.setClipEntry(ClipEntry?)`; the helpers that wrap a raw `String`
 * (`ClipData.newPlainText`, `ClipEntry.withPlainText`, `StringSelection`, …) live in
 * platform sources, so we bridge them via this expect. Tracked upstream as CMP-7624.
 */
expect fun textClipEntry(text: String): ClipEntry
