package dev.nucleusframework.pdf.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Short confirmation pill anchored to the bottom of its parent. Dismisses itself after
 * [durationMillis], invoking [onExpire] so the caller can clear the state that showed it.
 */
@Composable
fun ToastOverlay(
    message: String?,
    onExpire: () -> Unit,
    durationMillis: Long = 1800L,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(durationMillis)
        onExpire()
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .padding(bottom = 40.dp)
                .clip(shapes.small)
                .background(colors.accent)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            AppText(message, type.subtitle.copy(color = colors.accentContent))
        }
    }
}
