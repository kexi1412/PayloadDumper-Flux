package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ColorOS-16 (COUI) semantic tokens that Material3's ColorScheme has no slot for:
 * the grouped-card surfaces, the layered label greys (90/54/26 %), the inset
 * hairline divider and the tonal accent container used by list-row buttons.
 *
 * Values are lifted verbatim from the decompiled ColorOS 16 Settings resources
 * (coui_color_*). The accent stays "Aqua" — ColorOS is theme-able, so an
 * aqua-tinted card list still reads as a native OS surface.
 */
@Immutable
data class FluxColors(
    val card: Color,            // grouped-card / row surface fill
    val cardPressed: Color,     // row pressed state
    val divider: Color,         // inset hairline between rows in a group
    val labelPrimary: Color,    // row title  (coui label-primary  ≈90 %)
    val labelSecondary: Color,  // summary / value (label-secondary ≈54 %)
    val labelTertiary: Color,   // hint / disabled (label-tertiary  ≈26 %)
    val accent: Color,          // theme accent
    val onAccent: Color,        // content on top of the accent
    val accentContainer: Color, // tonal fill for borderless list buttons
    val success: Color,         // verify-passed green
    val warning: Color,         // amber
    val danger: Color,          // error red
    val fieldBg: Color,         // text-field container on a card
    val iconLocal: Color,       // "local file" leading tint
    val iconUrl: Color,         // "network link" leading tint
)

val LocalFluxColors = staticCompositionLocalOf { LightFlux }

// ---- Light — coui_color_background_with_card #F0F1F2, white cards ----
val LightBackground = Color(0xFFF0F1F2)
val LightSurface = Color(0xFFFFFFFF)          // grouped card
val LightSurfaceVariant = Color(0xFFEDEFF3)   // text-field container
val LightPrimary = Color(0xFF1E9AD6)          // aqua accent
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xE6000000)        // coui label-primary
val LightOnSurfaceVariant = Color(0x8A000000) // coui label-secondary
val LightOutline = Color(0x1F000000)          // coui divider
val LightError = Color(0xFFE4463C)

val LightFlux = FluxColors(
    card = Color(0xFFFFFFFF),
    cardPressed = Color(0xFFE6E6E6),
    divider = Color(0x1F000000),
    labelPrimary = Color(0xE6000000),
    labelSecondary = Color(0x8A000000),
    labelTertiary = Color(0x42000000),
    accent = Color(0xFF1E9AD6),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0x1F1E9AD6),
    success = Color(0xFF12B76A),
    warning = Color(0xFFF59E0B),
    danger = Color(0xFFE4463C),
    fieldBg = Color(0xFFEDEFF3),
    iconLocal = Color(0xFF1E9AD6),
    iconUrl = Color(0xFF12B76A),
)

// ---- Dark — coui background pure black, elevatedWithCard #1E1E1E ----
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1E1E1E)           // grouped card (elevatedWithCard_dark)
val DarkSurfaceVariant = Color(0xFF2A2A2E)    // text-field container
val DarkPrimary = Color(0xFF46B6E0)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xE6FFFFFF)
val DarkOnSurfaceVariant = Color(0x8AFFFFFF)
val DarkOutline = Color(0x26FFFFFF)
val DarkError = Color(0xFFF97066)

val DarkFlux = FluxColors(
    card = Color(0xFF1E1E1E),
    cardPressed = Color(0x33FFFFFF),
    divider = Color(0x26FFFFFF),
    labelPrimary = Color(0xE6FFFFFF),
    labelSecondary = Color(0x8AFFFFFF),
    labelTertiary = Color(0x4DFFFFFF),
    accent = Color(0xFF46B6E0),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0x3346B6E0),
    success = Color(0xFF34D399),
    warning = Color(0xFFFDB022),
    danger = Color(0xFFF97066),
    fieldBg = Color(0xFF2A2A2E),
    iconLocal = Color(0xFF5BC8E8),
    iconUrl = Color(0xFF34D399),
)
