package dev.nucleusframework.pdf

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.ComposeUIViewController
import dev.nucleusframework.pdf.design.AppThemeProvider

fun MainViewController() = ComposeUIViewController {
    AppThemeProvider(isDark = isSystemInDarkTheme()) {
        App()
    }
}
