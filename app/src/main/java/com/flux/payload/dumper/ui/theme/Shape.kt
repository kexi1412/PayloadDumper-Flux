package com.flux.payload.dumper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * ColorOS-16 roundness. The card-list corner is 17 dp (coui_card_list_os_16_1_radius_17_dp);
 * inner controls step down through the coui_round_corner ladder (16 / 12 / 8 dp).
 */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(17.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

object FluxRadius {
    val Card = 17.dp     // grouped card list (coui os16)
    val Inner = 14.dp    // nested chips / tiles inside a card
    val Field = 12.dp    // text fields
    val Button = 14.dp   // COUI filled buttons
    val Dialog = 24.dp   // COUIAlertDialog
    val Pill = 100.dp    // fully-rounded controls (segment thumb, search)
}
