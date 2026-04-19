package dev.nucleusframework.pdf.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.pdf.design.AppText
import dev.nucleusframework.pdf.design.PrimaryButton
import dev.nucleusframework.pdf.design.colors
import dev.nucleusframework.pdf.design.type

@Composable
internal fun ReaderEmptyState(onOpenClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppText("No document open", type.title)
        Spacer(Modifier.height(6.dp))
        AppText(
            "Click below to pick a PDF file.",
            type.body.copy(color = colors.muted),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(text = "Open PDF", onClick = onOpenClick)
    }
}
