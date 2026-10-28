package com.flux.payload.dumper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Circular-corner Compose approximation of the COUI resource sizes.
 * COUI also ships smooth-corner radius/weight families; a RoundedCornerShape
 * does not reproduce that proprietary path. See docs/COUI17_REFERENCE.md.
 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object FluxRadius {
    val Card = 12.dp       // coui_preference_card_radius; not SettingsLib's v36 20dp token
    val Inner = 12.dp
    val IconBox = 10.dp    // Flux-owned icon-container adaptation, not a measured OEM size
    val Field = 12.dp      // reuse the card token for the application's input surface
    val Button = 21.5.dp   // coui_btn_drawable_radius_large
    val SmallButton = 13.dp // coui_btn_drawable_radius_small
    val Dialog = 24.dp     // coui_dialog_background_corner_radius
    val Sheet = 28.dp      // coui_bottom_sheet_bg_top_corner_radius -> coui_round_corner_xxxl
    val Pill = 100.dp      // capsule approximation; no OEM path copied
}
