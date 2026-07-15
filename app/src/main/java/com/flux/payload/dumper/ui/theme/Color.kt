package com.flux.payload.dumper.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra ColorOS-16 "Flux" tokens that Material3's ColorScheme has no slot for
 * (the aurora gradient, verify/success accents, layered glass tints).
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
)

val LocalFluxColors = staticCompositionLocalOf {
    FluxColors(
        gradientStart = Color(0xFF3D6BFF),
        gradientEnd = Color(0xFF6E5BFF),
        success = Color(0xFF12B76A),
        warning = Color(0xFFF79009),
        glass = Color(0xCCFFFFFF),
        cardShadow = Color(0x14000000),
        iconTintLocal = Color(0xFF5B67F0),
        iconTintUrl = Color(0xFF12B76A),
        iconBgLocal = Color(0x1A5B67F0),
        iconBgUrl = Color(0x1A12B76A),
    )
}

// ---- Light ----
val LightBackground = Color(0xFFF4F5F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDEFF3)
val LightPrimary = Color(0xFF3D6BFF)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF16181D)
val LightOnSurfaceVariant = Color(0xFF8A9099)
val LightOutline = Color(0xFFE4E7EC)
val LightError = Color(0xFFF04438)

val LightFlux = FluxColors(
    gradientStart = Color(0xFF3D6BFF),
    gradientEnd = Color(0xFF6E5BFF),
    success = Color(0xFF12B76A),
    warning = Color(0xFFF79009),
    glass = Color(0xCCF4F5F7),
    cardShadow = Color(0x14000000),
    iconTintLocal = Color(0xFF5B67F0),
    iconTintUrl = Color(0xFF12B76A),
    iconBgLocal = Color(0x1A5B67F0),
    iconBgUrl = Color(0x1A12B76A),
)

// ---- Dark (ColorOS dark is near-black) ----
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF17191E)
val DarkSurfaceVariant = Color(0xFF212429)
val DarkPrimary = Color(0xFF5B84FF)
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
    glass = Color(0xCC000000),
    cardShadow = Color(0x33000000),
    iconTintLocal = Color(0xFF8AA0FF),
    iconTintUrl = Color(0xFF34D399),
    iconBgLocal = Color(0x268AA0FF),
    iconBgUrl = Color(0x2634D399),
)
