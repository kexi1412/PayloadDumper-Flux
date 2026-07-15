package com.flux.payload.dumper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** ColorOS-flavoured roundness: generously rounded cards, pill controls. */
val FluxShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

object FluxRadius {
    val Card = 28.dp
    val Inner = 20.dp
    val Field = 18.dp
    val Pill = 100.dp
}
