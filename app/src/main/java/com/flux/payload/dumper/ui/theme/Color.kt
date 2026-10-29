package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Resource-backed COUI palette from com.android.settings 17.0.0.
 * Provenance, configuration qualifiers and adaptation limits: docs/COUI17_REFERENCE.md.
 * These are selected library tokens, not a claim that every Settings screen uses them.
 */
@Immutable
data class FluxColors(
    val card: Color,
    val cardPressed: Color,
    val cardOutline: Color,
    val divider: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val fieldBg: Color,
    val iconBoxBg: Color,
    val iconLocal: Color,
    val iconUrl: Color,
    val segmentTrack: Color,
    val segmentIndicator: Color,
    val buttonDisabled: Color,
    val buttonOnDisabled: Color,
    val pressMask: Color,
)

val LocalFluxColors = staticCompositionLocalOf { LightFlux }

// Use the explicit *_light/*_dark background variants. The generic resource's
// night entry is not equivalent; see the qualifier caveat in the reference.
val LightBackground = Color(0xFFF0F1F2)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F1F2)
val LightPrimary = Color(0xFF0080FF) // coui_color_primary_blue
val LightOnPrimary = Color(0xFFFFFFFF) // coui_color_label_on_color
val LightOnSurface = Color(0xE6000000)
val LightOnSurfaceVariant = Color(0x8A000000)
val LightOutline = Color(0x14000000) // decorative hairlines, not control boundaries
val LightControlOutline = Color(0xFF747474)
val LightError = Color(0xFFDB382C)

val LightFlux = FluxColors(
    card = Color(0xFFFFFFFF), // coui_color_card
    cardPressed = Color(0xFFE6E6E6),
    cardOutline = LightOutline,
    divider = Color(0x1F000000),
    labelPrimary = LightOnSurface,
    labelSecondary = LightOnSurfaceVariant,
    labelTertiary = Color(0x42000000),
    accent = LightPrimary,
    onAccent = LightOnPrimary,
    accentContainer = Color(0x260080FF), // coui_color_secondary_blue
    success = Color(0xFF00990F), // coui_color_label_theme_green
    warning = Color(0xFFEB6E00), // coui_color_label_theme_orange
    danger = LightError,
    fieldBg = LightSurfaceVariant,
    iconBoxBg = Color(0x260080FF), // project-owned icons reuse the tonal token
    iconLocal = LightPrimary,
    iconUrl = Color(0xFF00990F),
    segmentTrack = Color(0x0F000000),
    segmentIndicator = Color(0xB2FFFFFF),
    buttonDisabled = Color(0x4D0080FF),
    buttonOnDisabled = Color(0x8AFFFFFF),
    pressMask = Color.Black.copy(alpha = 0.12f), // coui_button_press_black_alpha
)

val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1E1E1E) // opaque elevated surface for dialogs
val DarkSurfaceVariant = Color(0xFF1E1E1E)
val DarkPrimary = Color(0xFF1A8CFF)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xE6FFFFFF)
val DarkOnSurfaceVariant = Color(0x8AFFFFFF)
val DarkOutline = Color(0x26FFFFFF) // decorative hairlines, not control boundaries
val DarkControlOutline = Color(0xFF8A8A8A)
val DarkError = Color(0xFFFF6C61)

val DarkFlux = FluxColors(
    // OEM night tokens are overlays. Resolve them over the page background once:
    // a standalone card must not depend on the content or window behind it.
    card = Color(0x1AFFFFFF).compositeOver(DarkBackground),
    cardPressed = Color(0x33FFFFFF).compositeOver(DarkBackground),
    cardOutline = DarkOutline,
    divider = Color(0x33FFFFFF),
    labelPrimary = DarkOnSurface,
    labelSecondary = DarkOnSurfaceVariant,
    labelTertiary = Color(0x4DFFFFFF),
    accent = DarkPrimary,
    onAccent = DarkOnPrimary,
    accentContainer = Color(0x401A8CFF),
    success = Color(0xFF24B232),
    warning = Color(0xFFF08222),
    danger = DarkError,
    fieldBg = DarkSurfaceVariant,
    iconBoxBg = Color(0x401A8CFF),
    iconLocal = DarkPrimary,
    iconUrl = Color(0xFF24B232),
    segmentTrack = Color(0x1FFFFFFF),
    segmentIndicator = Color(0x33FFFFFF),
    buttonDisabled = Color(0x4D1A8CFF),
    buttonOnDisabled = Color(0x29FFFFFF),
    pressMask = Color.Black.copy(alpha = 0.20f),
)
