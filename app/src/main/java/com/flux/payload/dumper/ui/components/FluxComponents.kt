package com.flux.payload.dumper.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flux.payload.dumper.ui.theme.FluxRadius
import com.flux.payload.dumper.ui.theme.LocalFluxColors

/**
 * COUI large-button colors/radius/text size. Native minimum height is 44dp;
 * Flux raises it to a content-led 48dp minimum, preserving font scaling.
 * The subtle press scale is an application animation, not a measured OEM curve.
 */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "buttonPress")
    val background by animateColorAsState(
        when {
            !enabled -> flux.buttonDisabled
            pressed -> flux.pressMask.compositeOver(flux.accent)
            else -> flux.accent
        },
        label = "buttonColor",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(FluxRadius.Button))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) flux.onAccent else flux.buttonOnDisabled,
            textAlign = TextAlign.Center,
        )
    }
}

/** Tonal small-button palette/radius with an expanded, font-scale-safe touch target. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val flux = LocalFluxColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "tonalPress")
    val background by animateColorAsState(
        when {
            !enabled -> flux.fieldBg
            pressed -> flux.pressMask.compositeOver(flux.accentContainer)
            else -> flux.accentContainer
        },
        label = "tonalColor",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 48.dp)
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(FluxRadius.SmallButton))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) flux.accent else flux.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Human-readable byte size; keep the application's existing display convention. */
fun formatSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1000L * 1000 -> "%d KB".format(bytes / 1024)
    bytes < 1000L * 1000 * 1000 -> "%d MB".format(bytes / (1024 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
