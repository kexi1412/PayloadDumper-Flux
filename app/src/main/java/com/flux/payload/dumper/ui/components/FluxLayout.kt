package com.flux.payload.dumper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/** Explicit light/dark with-card background token; no wallpaper or OEM asset. */
@Composable
fun FluxBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/** COUI card token, without an unverified all-around hairline or tonal elevation. */
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

/** Optional grouped-list separator. Independent cards should not call this. */
@Composable
fun RowDivider(startInset: Dp = 32.dp, endInset: Dp = 32.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset, end = endInset),
        thickness = Dp.Hairline,
        color = LocalFluxColors.current.divider,
    )
}

@Composable
fun CategoryHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = LocalFluxColors.current.labelSecondary,
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * Assignment on the trailing side, with bounded measurement for long hashes.
 * 16dp internal padding plus the screen's 16dp card margin is a Flux adaptation
 * of the sample's 32dp ungrouped preference padding. Heights remain content-led.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = LocalFluxColors.current.labelSecondary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    valueMaxLines: Int = 2,
) {
    val flux = LocalFluxColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = onClickLabel,
                    onClick = onClick,
                ) else Modifier,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = flux.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/**
 * Resource-backed track/indicator colors and 4dp inset. The native SegmentButton
 * style is 40dp tall; a separate 48dp interaction band protects touch targets.
 * Both grow with multiline/scaled labels instead of clipping them.
 */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val flux = LocalFluxColors.current
    if (options.isEmpty()) return
    Box(modifier = modifier.fillMaxWidth().selectableGroup()) {
        Box(
            Modifier.matchParentSize()
                .padding(4.dp)
                .clip(RoundedCornerShape(FluxRadius.Pill))
                .background(flux.segmentTrack),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val background by animateColorAsState(
                    if (selected) flux.segmentIndicator else Color.Transparent,
                    label = "segmentIndicator",
                )
                val foreground by animateColorAsState(
                    when {
                        !enabled -> flux.labelTertiary
                        selected -> flux.labelPrimary
                        else -> flux.labelSecondary
                    },
                    label = "segmentLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(FluxRadius.Pill))
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.matchParentSize()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(FluxRadius.Pill))
                            .background(background),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = foreground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Flux-owned icon presentation; sizes are adaptations, not extracted OEM artwork. */
@Composable
fun IconBox(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color = LocalFluxColors.current.accent,
    bgColor: Color = LocalFluxColors.current.iconBoxBg,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(FluxRadius.IconBox))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Resource palette plus a Flux press animation; not an extracted OEM spring. */
@Composable
fun ExtractFab(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "fabPress")
    val background by animateColorAsState(
        if (pressed) flux.pressMask.compositeOver(flux.accent) else flux.accent,
        label = "fabColor",
    )
    Row(
        modifier = modifier
            .widthIn(max = 392.dp)
            .scale(scale)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(FluxRadius.Button))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = flux.onAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = flux.onAccent,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
