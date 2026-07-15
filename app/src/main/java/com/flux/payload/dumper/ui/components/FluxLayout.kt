package com.flux.payload.dumper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * Full-screen pastel wash that sits behind everything and gives the redesign its
 * ColorOS-16 glassmorphism feel: a soft glow bleeding down from the status bar plus
 * a lavender blob tucked into the top corner. Frosted cards float on top of it.
 */
@Composable
fun FluxBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val flux = LocalFluxColors.current
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().height(460.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(flux.auroraTop, Color.Transparent))),
        )
        Box(
            Modifier.size(320.dp).align(Alignment.TopEnd).offset(x = 70.dp, y = (-60).dp)
                .background(Brush.radialGradient(listOf(flux.auroraBlob, Color.Transparent))),
        )
        content()
    }
}

/** Frosted translucent card — the surface primitive the whole redesign is built on. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FluxRadius.Card),
    content: @Composable BoxScope.() -> Unit,
) {
    val flux = LocalFluxColors.current
    Box(
        modifier = modifier
            .clip(shape)
            .background(flux.glassCard)
            .border(1.dp, flux.glassStroke, shape),
        content = content,
    )
}

/** Pill-shaped segmented control (e.g. 本地文件 | 网络链接). */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.Pill))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                label = "segbg",
            )
            val fg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "segfg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(FluxRadius.Pill))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
            }
        }
    }
}

/**
 * One of the three headline summary cards (File Size / Security Patch / Partitions).
 * A tinted glyph chip sits above a big value and a quiet label.
 */
@Composable
fun RowScope.SummaryCard(icon: ImageVector, label: String, value: String, accent: Color) {
    GlassCard(modifier = Modifier.weight(1f), shape = RoundedCornerShape(FluxRadius.Inner)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The row of three summary cards. */
@Composable
fun SummaryRow(cards: List<SummaryData>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { SummaryCard(it.icon, it.label, it.value, it.accent) }
    }
}

data class SummaryData(val icon: ImageVector, val label: String, val value: String, val accent: Color)

/** Floating aurora-gradient action button with icon + label. */
@Composable
fun GradientFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, label = "fab")
    Row(
        modifier = modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(FluxRadius.Pill))
            .background(Brush.horizontalGradient(listOf(flux.gradientStart, flux.gradientEnd)))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, textAlign = TextAlign.Center)
    }
}
