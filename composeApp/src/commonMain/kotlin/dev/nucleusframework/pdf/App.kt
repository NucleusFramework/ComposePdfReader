package dev.nucleusframework.pdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.nucleusframework.pdf.reader.ReaderScreen
import dev.nucleusframework.pdf.reader.rememberReaderScreenState
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

/**
 * App root — wires the FileKit picker to the reader screen's state holder. The screen
 * itself doesn't know about file pickers; it just gets byte loads through a callback.
 */
@Composable
@Preview
fun App() {
    val state = rememberReaderScreenState()
    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pdf")),
    ) { file ->
        if (file != null) state.openDocument(displayName = file.name) { file.readBytes() }
    }
    ReaderScreen(state = state, onOpenClick = { picker.launch() })
}
