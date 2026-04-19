package dev.nucleusframework.pdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.nucleusframework.pdf.reader.ReaderScreen
import dev.nucleusframework.pdf.reader.ReaderScreenState
import dev.nucleusframework.pdf.reader.rememberReaderScreenState
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

/**
 * Wires the FileKit picker to [ReaderScreenState.openDocument]. Returns a launch lambda so
 * callers can trigger the picker from different surfaces — e.g. the in-app toolbar (mobile)
 * or a title-bar button (Nucleus desktop).
 */
@Composable
fun rememberOpenDocumentAction(state: ReaderScreenState): () -> Unit {
    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pdf")),
    ) { file ->
        if (file != null) state.openDocument(displayName = file.name) { file.readBytes() }
    }
    return { picker.launch() }
}

/**
 * Mobile entry point — renders the reader with its full in-app toolbar (no native title bar
 * on Android/iOS). Desktop uses the Nucleus `MaterialTitleBar` and wires its own layout.
 */
@Composable
@Preview
fun App() {
    val state = rememberReaderScreenState()
    val onOpen = rememberOpenDocumentAction(state)
    ReaderScreen(state = state, onOpenClick = onOpen)
}
