package dev.nucleusframework.pdf.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Unstyled slider: thin track + filled range + draggable thumb. Works across JVM / Android / iOS.
 * Uses raw pointer-input (no Material Slider dependency).
 */
@Composable
fun MinimalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableStateOf(1) }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(valueRange) {
                detectTapGestures { off ->
                    val next = (off.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + next * span)
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    val next = (change.position.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + next * span)
                }
            },
    ) {
        // Track
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart)
                .clip(shapes.small)
                .background(colors.border),
        )
        // Filled range
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .align(Alignment.CenterStart)
                .clip(shapes.small)
                .background(colors.accent),
        )
        // Thumb
        val thumbOffsetPx = ((widthPx - density.run { 14.dp.roundToPx() }) * fraction).roundToInt()
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(thumbOffsetPx, 0) }
                .size(14.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accent),
        )
    }
}
