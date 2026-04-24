package dev.nucleusframework.pdfium

import androidx.compose.ui.platform.ClipEntry

actual fun textClipEntry(text: String): ClipEntry =
    ClipEntry.withPlainText(text)
