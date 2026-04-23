package dev.nucleusframework.pdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.nucleusframework.pdf.design.AppThemeProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AppThemeProvider(isDark = isSystemInDarkTheme()) {
                App()
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    AppThemeProvider(isDark = isSystemInDarkTheme()) {
        App()
    }
}