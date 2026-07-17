package com.flux.payload.dumper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * ColorOS-16 card list background: a flat pastel-grey wash (coui_color_background_with_card).
 * The redesign drops the old glassmorphism aurora in favour of the OS's plain surface so the
 * white grouped cards read as native Settings groups.
 */
@Composable
fun FluxBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/**
 * A rounded white group card — the primitive the whole list is built from. Children are stacked
 * in a Column; use [RowDivider] between rows to get ColorOS's inset hairlines.
 */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val flux = LocalFluxColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluxRadius.Card))
            .background(flux.card),
        content = content,
    )
}

/** Inset hairline divider between rows inside a [CardGroup] (coui_color_divider). */
@Composable
fun RowDivider(startInset: Dp = 20.dp, endInset: Dp = 0.dp) {
    val flux = LocalFluxColors.current
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = 1.dp,
        color = flux.divider,
    )
}

/** Small grey caption sitting above a card group (support_preference_category_title_size 12 sp). */
@Composable
fun CategoryHeader(text: String, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = flux.labelSecondary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Label-left / value-right info row (device-info style), used for the OTA summary. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = LocalFluxColors.current.labelSecondary,
) {
    val flux = LocalFluxColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = flux.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** ColorOS segmented control (COUITabView): grey track with a white sliding thumb. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flux = LocalFluxColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(flux.fieldBg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(if (selected) flux.card else Color.Transparent, label = "segbg")
            val fg by animateColorAsState(if (selected) flux.accent else flux.labelSecondary, label = "segfg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = fg)
            }
        }
    }
}

/** Solid-accent extended floating button (COUIFloatingButton) with icon + label. */
@Composable
fun ExtractFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, label = "fab")
    Row(
        modifier = modifier
            .scale(scale)
            .height(54.dp)
            .clip(RoundedCornerShape(FluxRadius.Button))
            .background(flux.accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = flux.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = flux.onAccent, textAlign = TextAlign.Center)
    }
}
