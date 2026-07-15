package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra ColorOS-16 "Flux" tokens that Material3's ColorScheme has no slot for
 * (the aurora gradient, verify/success accents, layered glass tints, the soft
 * pastel background wash that gives the redesign its glassmorphism feel).
 *
 * Accent scheme: "Aqua" — cyan→blue gradient with an aqua-blue primary. The
 * previous purple accent was retired at the user's request; the multi-colour
 * summary-card icons (blue/green/amber) are set independently in DumperScreen.
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
    val auroraBlob: Color,     // secondary glow, offset to one side
    val glassCard: Color,      // translucent fill for frosted cards / tiles
    val glassStroke: Color,    // hairline border that sells the glass edge
)

val LocalFluxColors = staticCompositionLocalOf { LightFlux }

// ---- Light (soft pastel blue + white, aqua accent) ----
val LightBackground = Color(0xFFEFF3FD)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE8EDF7)
val LightPrimary = Color(0xFF1E9AD6)          // aqua-blue — the parse CTA leans this way
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF14171F)
val LightOnSurfaceVariant = Color(0xFF79818F)
val LightOutline = Color(0xFFE1E7F2)
val LightError = Color(0xFFF04438)

val LightFlux = FluxColors(
    gradientStart = Color(0xFF19C2D6),        // cyan
    gradientEnd = Color(0xFF3B82F6),          // blue
    success = Color(0xFF12B76A),
    warning = Color(0xFFF59E0B),
    glass = Color(0xCCF0F3FD),
    cardShadow = Color(0x14000000),
    iconTintLocal = Color(0xFF1E9AD6),
    iconTintUrl = Color(0xFF12B76A),
    iconBgLocal = Color(0x1A1E9AD6),
    iconBgUrl = Color(0x1A12B76A),
    auroraTop = Color(0x3319C2D6),
    auroraBlob = Color(0x2E3B82F6),
    glassCard = Color(0xB8FFFFFF),
    glassStroke = Color(0x80FFFFFF),
)

// ---- Dark (ColorOS dark is near-black; glow is a faint cyan/blue wash) ----
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF141821)
val DarkSurfaceVariant = Color(0xFF1E2430)
val DarkPrimary = Color(0xFF46B6E0)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkOnSurface = Color(0xFFF2F4F7)
val DarkOnSurfaceVariant = Color(0xFF8B929B)
val DarkOutline = Color(0xFF2A2E35)
val DarkError = Color(0xFFF97066)

val DarkFlux = FluxColors(
    gradientStart = Color(0xFF35CFE0),
    gradientEnd = Color(0xFF5B9BFF),
    success = Color(0xFF34D399),
    warning = Color(0xFFFDB022),
    glass = Color(0xCC0A0B10),
    cardShadow = Color(0x33000000),
    iconTintLocal = Color(0xFF5BC8E8),
    iconTintUrl = Color(0xFF34D399),
    iconBgLocal = Color(0x265BC8E8),
    iconBgUrl = Color(0x2634D399),
    auroraTop = Color(0x4D2E9ED6),
    auroraBlob = Color(0x3D3B82F6),
    glassCard = Color(0x1FFFFFFF),
    glassStroke = Color(0x26FFFFFF),
)
