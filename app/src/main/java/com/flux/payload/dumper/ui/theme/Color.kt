package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra ColorOS-16 "Flux" tokens that Material3's ColorScheme has no slot for
 * (the aurora gradient, verify/success accents, layered glass tints, the soft
 * pastel background wash that gives the redesign its glassmorphism feel).
 */
@Immutable
data class FluxColors(
    val gradientStart: Color,
    val gradientEnd: Color,
    val success: Color,
    val warning: Color,
    val glass: Color,          // translucent tint for the frosted top bar
    val cardShadow: Color,
    val iconTintLocal: Color,
    val iconTintUrl: Color,
    val iconBgLocal: Color,
    val iconBgUrl: Color,
    val auroraTop: Color,      // soft glow bleeding down from the top of the screen
    val auroraBlob: Color,     // secondary lavender glow, offset to one side
    val glassCard: Color,      // translucent fill for frosted cards / tiles
    val glassStroke: Color,    // hairline border that sells the glass edge
)

val LocalFluxColors = staticCompositionLocalOf { LightFlux }

// ---- Light (soft pastel blue + white, vibrant purple accent) ----
val LightBackground = Color(0xFFEFF3FD)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE8EDF7)
val LightPrimary = Color(0xFF6B4EFF)          // vibrant purple — the parse CTA leans this way
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF14171F)
val LightOnSurfaceVariant = Color(0xFF79818F)
val LightOutline = Color(0xFFE1E7F2)
val LightError = Color(0xFFF04438)

val LightFlux = FluxColors(
    gradientStart = Color(0xFF4E7CFF),        // blue
    gradientEnd = Color(0xFF7A54FF),          // purple
    success = Color(0xFF12B76A),
    warning = Color(0xFFF79009),
    glass = Color(0xCCF0F3FD),
    cardShadow = Color(0x14000000),
    iconTintLocal = Color(0xFF5766FF),
    iconTintUrl = Color(0xFF12B76A),
    iconBgLocal = Color(0x1A5766FF),
    iconBgUrl = Color(0x1A12B76A),
    auroraTop = Color(0x334E7CFF),
    auroraBlob = Color(0x2E7A54FF),
    glassCard = Color(0xB8FFFFFF),
    glassStroke = Color(0x80FFFFFF),
)

// ---- Dark (ColorOS dark is near-black; glow is a faint blue/purple wash) ----
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF141821)
val DarkSurfaceVariant = Color(0xFF1E2430)
val DarkPrimary = Color(0xFF8A7CFF)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xFFF2F4F7)
val DarkOnSurfaceVariant = Color(0xFF8B929B)
val DarkOutline = Color(0xFF2A2E35)
val DarkError = Color(0xFFF97066)

val DarkFlux = FluxColors(
    gradientStart = Color(0xFF5B84FF),
    gradientEnd = Color(0xFF8A6BFF),
    success = Color(0xFF34D399),
    warning = Color(0xFFFDB022),
    glass = Color(0xCC0A0B10),
    cardShadow = Color(0x33000000),
    iconTintLocal = Color(0xFF8AA0FF),
    iconTintUrl = Color(0xFF34D399),
    iconBgLocal = Color(0x268AA0FF),
    iconBgUrl = Color(0x2634D399),
    auroraTop = Color(0x4D3D6BFF),
    auroraBlob = Color(0x3D6E5BFF),
    glassCard = Color(0x1FFFFFFF),
    glassStroke = Color(0x26FFFFFF),
)
