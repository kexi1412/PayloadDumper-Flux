package com.flux.payload.dumper.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxColorsTest {
    @Test fun standaloneSurfacesAreOpaqueInBothThemes() {
        val surfaces = listOf(
            LightBackground, LightSurface, LightSurfaceVariant,
            LightFlux.card, LightFlux.cardPressed, LightFlux.fieldBg,
            DarkBackground, DarkSurface, DarkSurfaceVariant,
            DarkFlux.card, DarkFlux.cardPressed, DarkFlux.fieldBg,
        )
        surfaces.forEach { assertEquals("Surface $it must be opaque", 1f, it.alpha, 0f) }
    }

    @Test fun darkCardsPreserveTheOriginalBlackBackedAppearanceWithoutTransparency() {
        assertEquals(Color(0xFF1A1A1A), DarkFlux.card)
        assertEquals(Color(0xFF333333), DarkFlux.cardPressed)
        // The result must not change when another surface is underneath the card.
        assertEquals(DarkFlux.card, DarkFlux.card.compositeOver(Color.Magenta))
        assertEquals(DarkFlux.card, DarkFlux.card.compositeOver(Color.White))
    }

    @Test fun primaryAndSecondaryLabelsRemainReadableOnCardsAndDialogs() {
        listOf(LightFlux to LightSurface, DarkFlux to DarkSurface).forEach { (flux, dialog) ->
            listOf(flux.card, dialog).forEach { surface ->
                assertContrast(flux.labelPrimary, surface, 4.5f)
                assertContrast(flux.labelSecondary, surface, 4.5f)
            }
            assertContrast(flux.labelPrimary, flux.fieldBg, 4.5f)
        }
    }

    @Test fun controlOutlinesAreNotDecorativeHairlines() {
        listOf(
            Triple(LightControlOutline, LightFlux, LightSurface),
            Triple(DarkControlOutline, DarkFlux, DarkSurface),
        ).forEach { (outline, flux, dialog) ->
            assertEquals(1f, outline.alpha, 0f)
            listOf(flux.card, flux.fieldBg, dialog).forEach { surface ->
                assertContrast(outline, surface, 3f)
                assertContrast(flux.accent, surface, 3f)
                assertContrast(flux.danger, surface, 3f)
            }
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val foregroundLuminance = foreground.compositeOver(background).luminance()
        val backgroundLuminance = background.luminance()
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
        assertTrue("$foreground on $background has contrast $ratio, expected >= $minimum", ratio >= minimum)
    }
}
