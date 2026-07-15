package com.flux.payload.dumper.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** A ColorOS-style rounded surface card. */
@Composable
fun FluxCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluxRadius.Card),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** Section header text ("OTA 信息", "镜像列表"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** A vertical label / value info block. */
@Composable
fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Primary aurora-gradient pill button with a springy press animation. */
@Composable
fun GradientPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val brush = if (enabled) Brush.horizontalGradient(listOf(flux.gradientStart, flux.gradientEnd))
    else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))

    Box(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(FluxRadius.Pill))
            .background(brush)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact tonal pill used for row-level actions like 提取. */
@Composable
fun TonalPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    content: Color = MaterialTheme.colorScheme.primary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "pill")
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(FluxRadius.Pill))
            .background(if (enabled) container else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Human-readable byte size, matching the reference app's rounding. */
fun formatSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000L * 1000 -> "%d KB".format(bytes / 1024)
    bytes < 1000L * 1000 * 1000 -> "%d MB".format(bytes / (1024 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
