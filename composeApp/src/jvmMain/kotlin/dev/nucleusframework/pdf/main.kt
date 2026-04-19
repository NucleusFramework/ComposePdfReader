package dev.nucleusframework.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.AppThemeProvider
import dev.nucleusframework.pdf.design.Spinner
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.shapes
import dev.nucleusframework.pdf.design.type
import dev.nucleusframework.pdf.reader.ReaderScreen
import dev.nucleusframework.pdf.reader.ReaderScreenState
import dev.nucleusframework.pdf.reader.SpreadMode
import dev.nucleusframework.pdf.reader.ZOOM_MAX
import dev.nucleusframework.pdf.reader.ZOOM_MIN
import dev.nucleusframework.pdf.reader.rememberReaderScreenState
import kotlin.math.roundToInt
import io.github.kdroidfilter.nucleus.core.runtime.DeepLinkHandler
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import io.github.kdroidfilter.nucleus.window.TitleBarScope
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.material.MaterialDecoratedWindow
import io.github.kdroidfilter.nucleus.window.material.MaterialTitleBar
import io.github.kdroidfilter.nucleus.window.newFullscreenControls
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val BrandAccent = Color(0xFF4464E5)

fun main(args: Array<String>) {
    GraalVmInitializer.initialize()

    application {
        val isDark = isSystemInDarkMode()
        val colorScheme = remember(isDark) {
            if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF7C9CFF),
                    surface = Color(0xFF131519),
                    background = Color(0xFF0B0C0F),
                    onSurface = Color(0xFFECEFF3),
                )
            } else {
                lightColorScheme(
                    primary = BrandAccent,
                    surface = Color(0xFFFFFFFF),
                    background = Color(0xFFF6F7F9),
                    onSurface = Color(0xFF16181C),
                )
            }
        }

        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1280.dp, 840.dp),
        )

        MaterialTheme(colorScheme = colorScheme) {
            MaterialDecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = "Nucleus PDF",
                minimumSize = DpSize(720.dp, 480.dp),
            ) {
                AppThemeProvider(isDark = isDark) {
                    val readerState = rememberReaderScreenState()
                    val onOpen = rememberOpenDocumentAction(readerState)

                    LaunchedEffect(readerState) {
                        // Nucleus DeepLinkHandler only dispatches args with a URI scheme (e.g.
                        // `myapp://…`). File associations deliver raw filesystem paths on Linux/
                        // Windows and via `setOpenFileHandler` on macOS — wire those directly.
                        DeepLinkHandler.register(args) { uri -> openFromUri(uri, readerState) }
                        DeepLinkHandler.uri?.let { openFromUri(it, readerState) }

                        args.firstOrNull { it.endsWith(".pdf", ignoreCase = true) }
                            ?.let { openFromPath(Paths.get(it), readerState) }

                        if (Desktop.isDesktopSupported()) {
                            runCatching {
                                Desktop.getDesktop().setOpenFileHandler { event ->
                                    event.files.firstOrNull()?.let {
                                        openFromPath(it.toPath(), readerState)
                                    }
                                }
                            }
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        MaterialTitleBar(
                            modifier = Modifier
                                .newFullscreenControls()
                                .macOSLargeCornerRadius(),
                        ) { _ ->
                            TitleBarBody(state = readerState, onOpen = onOpen)
                        }
                        Box(Modifier.fillMaxSize()) {
                            ReaderScreen(
                                state = readerState,
                                onOpenClick = onOpen,
                                showFileHeader = false,
                                showControlsBar = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Content rendered inside a Nucleus title bar. Each child self-aligns with `Modifier.align(...)`
 * and Nucleus' title-bar measure policy places them opposite to the native window controls
 * (Start on macOS, End on Windows/Linux), with centred content filling the middle.
 */
@Composable
private fun TitleBarScope.TitleBarBody(
    state: ReaderScreenState,
    onOpen: () -> Unit,
) {
    // macOS: native buttons on the LEFT → our content goes to the End.
    // Windows/Linux: native buttons on the RIGHT → content goes to the Start.
    val actionSide = if (Platform.Current == Platform.MacOS) Alignment.End else Alignment.Start
    val hasDocument = state.reader.pageCount > 0

    if (hasDocument) {
        Row(
            modifier = Modifier.align(actionSide).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleBarAction(text = "Open PDF", onClick = onOpen)
            TitleBarSeparator()
            TitleBarGhostAction(
                text = "−",
                onClick = {
                    state.reader.renderScale =
                        (state.reader.renderScale - ZOOM_STEP).coerceAtLeast(ZOOM_MIN)
                },
            )
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(shapes.small)
                    .border(1.dp, colors.border, shapes.small)
                    .background(colors.surfaceRaised)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                AppText(
                    text = "${(state.reader.renderScale * 100).roundToInt()}%",
                    style = type.body,
                )
            }
            Spacer(Modifier.width(4.dp))
            TitleBarGhostAction(
                text = "+",
                onClick = {
                    state.reader.renderScale =
                        (state.reader.renderScale + ZOOM_STEP).coerceAtMost(ZOOM_MAX)
                },
            )
            TitleBarSeparator()
            TitleBarGhostAction(text = "Fit W", onClick = state::fitWidth)
            Spacer(Modifier.width(4.dp))
            TitleBarGhostAction(text = "Fit H", onClick = state::fitHeight)
            Spacer(Modifier.width(4.dp))
            TitleBarGhostAction(text = "Fit", onClick = state::fitPage)
            TitleBarSeparator()
            TitleBarGhostAction(
                text = if (state.spreadMode == SpreadMode.Double) "Single" else "Spread",
                onClick = state::toggleSpreadMode,
            )
        }
    }

    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.reader.isLoading) {
            Spinner(Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
        }
        AppText(
            text = state.fileName ?: "No document",
            style = type.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.reader.pageCount > 0) {
            Spacer(Modifier.width(12.dp))
            AppText(
                text = "${state.currentPage + 1} / ${state.reader.pageCount}",
                style = type.body.copy(color = colors.muted),
            )
        }
    }
}

private const val ZOOM_STEP = 0.25f

/**
 * Compact filled action button sized for a Nucleus title bar. Uses [titleBarClickable] so
 * the tap survives macOS fullscreen phantom pointer events.
 */
@Composable
private fun TitleBarScope.TitleBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.accent)
            .titleBarClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        AppText(
            text = text,
            style = type.body.copy(color = colors.accentContent, fontWeight = FontWeight.Medium),
        )
    }
}

/** Outlined title-bar button — secondary action sitting alongside the primary one. */
@Composable
private fun TitleBarScope.TitleBarGhostAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(shapes.small)
            .border(1.dp, colors.border, shapes.small)
            .background(colors.surfaceRaised)
            .titleBarClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        AppText(text = text, style = type.body)
    }
}

@Composable
private fun TitleBarSeparator() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(width = 1.dp, height = 16.dp)
            .background(colors.border),
    )
}

/**
 * Resolve a Nucleus deep-link URI to a local file and load it into the reader.
 *
 * Accepts `file://` URIs (macOS Apple Events) as well as raw filesystem paths promoted to URIs
 * by DeepLinkHandler on Linux/Windows. Non-file schemes are ignored.
 */
private fun openFromUri(uri: URI, state: ReaderScreenState) {
    val path: Path = when {
        uri.scheme.equals("file", ignoreCase = true) -> Paths.get(uri)
        uri.scheme == null && !uri.path.isNullOrBlank() -> Paths.get(uri.path)
        else -> return
    }
    openFromPath(path, state)
}

private fun openFromPath(path: Path, state: ReaderScreenState) {
    if (!Files.isRegularFile(path)) return
    state.openDocument(displayName = path.fileName?.toString()) { Files.readAllBytes(path) }
}
