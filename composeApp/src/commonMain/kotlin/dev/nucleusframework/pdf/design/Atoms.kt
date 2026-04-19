package dev.nucleusframework.pdf.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledButton

/** Plain text using the app's base body style by default. */
@Composable
fun AppText(
    text: String,
    style: TextStyle = type.body,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(text = text, style = style, modifier = modifier, maxLines = maxLines, overflow = overflow)
}

/** Primary filled button — main call-to-action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .clip(shapes.small)
            .background(if (enabled) colors.accent else colors.border)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        AppText(
            text = text,
            style = type.subtitle.copy(color = if (enabled) colors.accentContent else colors.muted),
        )
    }
}

/** Outlined secondary button on surface raised. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .clip(shapes.small)
            .border(1.dp, colors.border, shapes.small)
            .background(colors.surfaceRaised)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        AppText(
            text = text,
            style = type.body.copy(color = if (enabled) colors.onBackground else colors.muted),
        )
    }
}

/** Thin translucent button — used for in-page overlay actions. */
@Composable
fun OverlayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UnstyledButton(
        onClick = onClick,
        modifier = modifier
            .clip(shapes.small)
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        AppText(text, type.body.copy(color = androidx.compose.ui.graphics.Color.White))
    }
}

/**
 * Segmented pill control — one highlighted selection out of N options. Generic over the
 * option value so callers can plug their own enum in without mapping through strings.
 */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(shapes.small)
            .border(1.dp, colors.border, shapes.small)
            .background(colors.surfaceRaised)
            .padding(3.dp),
    ) {
        options.forEach { (label, value) ->
            val isActive = value == selected
            UnstyledButton(
                onClick = { onSelect(value) },
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                AppText(
                    text = label,
                    style = type.body.copy(
                        color = if (isActive) colors.accentContent else colors.onBackground,
                    ),
                )
            }
        }
    }
}

@Composable
fun HDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(colors.border))
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
    )
    Box(
        modifier = modifier
            .size(28.dp)
            .rotate(angle)
            .border(2.dp, colors.accent, RoundedCornerShape(50))
            .padding(4.dp),
    )
}
