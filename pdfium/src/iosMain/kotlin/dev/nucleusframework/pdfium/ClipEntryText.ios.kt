package dev.nucleusframework.pdfium

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
actual fun textClipEntry(text: String): ClipEntry =
    ClipEntry.withPlainText(text)
