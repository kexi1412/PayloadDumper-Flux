package com.flux.payload.dumper.ui

import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.SourceMode
import com.flux.payload.dumper.model.SourceSelection
import com.flux.payload.dumper.ui.components.CardGroup
import com.flux.payload.dumper.ui.components.RelinkDialog
import com.flux.payload.dumper.ui.components.SourceCard
import com.flux.payload.dumper.ui.theme.DarkBackground
import com.flux.payload.dumper.ui.theme.DarkControlOutline
import com.flux.payload.dumper.ui.theme.DarkFlux
import com.flux.payload.dumper.ui.theme.DarkSurface
import com.flux.payload.dumper.ui.theme.FluxColors
import com.flux.payload.dumper.ui.theme.LightBackground
import com.flux.payload.dumper.ui.theme.LightControlOutline
import com.flux.payload.dumper.ui.theme.LightFlux
import com.flux.payload.dumper.ui.theme.LightSurface
import com.flux.payload.dumper.ui.theme.LocalFluxColors
import com.flux.payload.dumper.ui.theme.PayloadDumperFluxTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FluxThemeRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun systemNightChangesAndExplicitOverrideSelectMatchingPalettes() {
        var systemDark by mutableStateOf(true)
        var themeOverride by mutableStateOf<Boolean?>(null)
        lateinit var scheme: ColorScheme
        lateinit var flux: FluxColors
        compose.setContent {
            TestSystemTheme(systemDark, themeOverride) {
                val currentScheme = MaterialTheme.colorScheme
                val currentFlux = LocalFluxColors.current
                SideEffect { scheme = currentScheme; flux = currentFlux }
            }
        }
        fun assertTheme(dark: Boolean) {
            assertSame(if (dark) DarkFlux else LightFlux, flux)
            assertEquals(if (dark) DarkSurface else LightSurface, scheme.surface)
            assertEquals(if (dark) DarkBackground else LightBackground, scheme.background)
            assertEquals(if (dark) DarkControlOutline else LightControlOutline, scheme.outline)
            listOf(scheme.surface, scheme.surfaceVariant, scheme.surfaceContainerHigh, flux.card, flux.fieldBg)
                .forEach { assertEquals(1f, it.alpha, 0f) }
        }
        compose.runOnIdle { assertTheme(true); systemDark = false }
        compose.runOnIdle { assertTheme(false); themeOverride = true }
        compose.runOnIdle { assertTheme(true); systemDark = true; themeOverride = false }
        compose.runOnIdle { assertTheme(false); themeOverride = null }
        compose.runOnIdle { assertTheme(true) }
    }

    @Test fun darkCardPaintDoesNotShowContrastingContentBehindIt() {
        var backdrop by mutableStateOf(Color.Magenta)
        lateinit var composeView: View
        var insetPx = 0
        val cardDraws = AtomicInteger()
        compose.setContent {
            PayloadDumperFluxTheme(darkTheme = true) {
                val view = LocalView.current
                val inset = with(LocalDensity.current) { 8.dp.roundToPx() }
                SideEffect { composeView = view; insetPx = inset }
                Box(
                    Modifier.fillMaxSize().testTag("card_backdrop")
                        .background(backdrop).padding(24.dp),
                ) {
                    CardGroup(
                        Modifier.testTag("opaque_card").drawWithContent {
                            drawContent()
                            cardDraws.incrementAndGet()
                        },
                    ) { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
        fun assertCard(expectedBackdrop: Color, suffix: String) {
            // Capture uses node coordinates from BEFORE its own decorView redraw. For a
            // brand-new, otherwise empty composition, first commit the actual Compose View
            // so those coordinates and retained graphics layers belong to the rendered frame.
            compose.waitForIdle()
            awaitCommittedComposeFrame(composeView)
            val card = compose.onNodeWithTag("opaque_card").assertIsDisplayed().assertHeightIsEqualTo(72.dp)
            assertTrue("CardGroup's drawContent was never called", cardDraws.get() > 0)
            val cardBounds = card.fetchSemanticsNode().boundsInRoot
            val backdropBounds = compose.onNodeWithTag("card_backdrop").fetchSemanticsNode().boundsInRoot
            Log.i("FluxThemeRegression", "card=$cardBounds backdrop=$backdropBounds draws=${cardDraws.get()}")
            val fullFrame = capturePixels("card_backdrop", "card-frame-$suffix")
            assertPixel(expectedBackdrop, fullFrame[fullFrame.width / 2, insetPx])
            val centerX = (cardBounds.center.x - backdropBounds.left).roundToInt()
            val centerY = (cardBounds.center.y - backdropBounds.top).roundToInt()
            assertPixel(DarkFlux.card, fullFrame[centerX, centerY])
            val pixels = capturePixels("opaque_card", "card-dark-$suffix")
            assertPixel(DarkFlux.card, pixels[pixels.width / 2, pixels.height / 2])
        }
        assertCard(Color.Magenta, "magenta")
        compose.runOnIdle { backdrop = Color.Cyan }
        assertCard(Color.Cyan, "cyan")
    }

    @Test fun openDialogRepaintsItsOpaqueBodyWhenSystemThemeChanges() {
        var systemDark by mutableStateOf(false)
        var insetPx = 0
        compose.setContent {
            TestSystemTheme(systemDark) {
                insetPx = with(LocalDensity.current) { 8.dp.roundToPx() }
                Box(Modifier.fillMaxSize().background(Color.Magenta))
                RelinkDialog(partitionName = "boot", onSubmit = {}, onCancel = {})
            }
        }
        fun assertDialogBody(expected: Color, screenshot: String) {
            val pixels = capturePixels("flux_dialog_surface", screenshot)
            // Sample empty top padding INSIDE the body, not the intentionally transparent corners.
            assertPixel(expected, pixels[pixels.width / 2, insetPx])
            assertPixel(expected, pixels[insetPx, pixels.height / 2])
        }
        assertDialogBody(LightSurface, "dialog-light")
        compose.runOnIdle { systemDark = true }
        assertDialogBody(DarkSurface, "dialog-dark")
        compose.runOnIdle { systemDark = false }
        assertDialogBody(LightSurface, "dialog-light-restored")
    }

    @Test fun networkEditorHasOpaqueFillAndVisibleUnfocusedAndDisabledOutlines() {
        var dark by mutableStateOf(true)
        var locked by mutableStateOf(false)
        var insetPx = 0
        var borderBandPx = 0
        compose.setContent {
            PayloadDumperFluxTheme(darkTheme = dark) {
                insetPx = with(LocalDensity.current) { 8.dp.roundToPx() }
                borderBandPx = with(LocalDensity.current) { 3.dp.roundToPx() }
                Box(Modifier.fillMaxSize().background(Color.Magenta).padding(16.dp)) {
                    SourceCard(SourceSelection(mode = SourceMode.NETWORK), {}, {}, {}, locked, false, {})
                }
            }
        }
        fun assertEditor(flux: FluxColors) {
            val mode = if (dark) "dark" else "light"
            val state = if (locked) "disabled" else "unfocused"
            val pixels = capturePixels("network_url_input", "field-$mode-$state")
            assertPixel(flux.fieldBg, pixels[pixels.width - insetPx, pixels.height / 2])
            val backgroundLuminance = flux.fieldBg.luminance()
            val visibleOutline = (pixels.height - borderBandPx until pixels.height).any { y ->
                val luminance = pixels[pixels.width / 2, y].luminance()
                val contrast = (maxOf(luminance, backgroundLuminance) + 0.05f) /
                    (minOf(luminance, backgroundLuminance) + 0.05f)
                contrast >= 3f
            }
            assertTrue("The unfocused/disabled editor must have a visible bottom outline", visibleOutline)
        }
        assertEditor(DarkFlux)
        compose.runOnIdle { locked = true }
        compose.onNodeWithTag("network_url_input").assertIsNotEnabled()
        assertEditor(DarkFlux)
        compose.runOnIdle { dark = false; locked = false }
        assertEditor(LightFlux)
    }

    @Test fun relinkReasonErrorAndBusyStatesSurviveThemeUpdates() {
        val reason = "Connection timed out while reading boot"
        val mismatch = "ROM identity mismatch"
        val url = "https://example.com/new-OTA.zip"
        var dark by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var submitted: String? = null
        var cancelled = false
        compose.setContent {
            PayloadDumperFluxTheme(darkTheme = dark) {
                RelinkDialog(
                    partitionName = "boot",
                    onSubmit = { submitted = it },
                    onCancel = { cancelled = true },
                    busy = busy,
                    failureReason = reason,
                    error = error,
                )
            }
        }
        compose.onNodeWithText(reason, substring = true).assertExists()
        compose.onNodeWithText("403", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.relink_confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("relink_url_input").performTextInput(url)
        compose.onNodeWithText(context.getString(R.string.relink_confirm)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(url, submitted); error = mismatch }
        compose.onNodeWithText(mismatch).assertExists()
        compose.runOnIdle { busy = true; dark = false }
        compose.onNodeWithText(context.getString(R.string.relink_checking)).assertIsNotEnabled()
        compose.onNodeWithTag("relink_url_input").assertIsNotEnabled()
        compose.onNodeWithText(url).assertExists()
        compose.onNodeWithText(reason, substring = true).assertExists()
        compose.onNodeWithText(mismatch).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun nativeWindowAndSplashColorsMatchTheSystemNightConfiguration() {
        listOf(false, true).forEach { dark ->
            val configuration = nightConfiguration(context.resources.configuration, dark)
            val resources = context.createConfigurationContext(configuration).resources
            val expected = if (dark) DarkBackground else LightBackground
            assertEquals(expected.toArgb(), resources.getColor(R.color.window_bg, null))
            assertEquals(expected.toArgb(), resources.getColor(R.color.splash_bg, null))
        }
    }

    private fun awaitCommittedComposeFrame(view: View) {
        val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            HardwareRendererCompat.setDrawingEnabled(true)
            val committed = CountDownLatch(1)
            compose.runOnUiThread {
                check(view.isAttachedToWindow && view.isHardwareAccelerated)
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                // Invalidate AndroidComposeView itself, not only the window's decorView.
                view.invalidate()
            }
            assertTrue("The Compose View did not commit a rendered frame", committed.await(5, TimeUnit.SECONDS))
        } finally {
            HardwareRendererCompat.setDrawingEnabled(drawingWasEnabled)
        }
    }

    private fun capturePixels(tag: String, name: String): PixelMap {
        val image = compose.onNodeWithTag(tag).captureToImage()
        // UTP uninstalls the target after connected tests and removes app-specific files.
        // Published MediaStore images survive that cleanup; no storage permission is needed
        // to create our own images on API 30+. CI pulls /sdcard/Pictures/FluxThemeRegression.
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FluxThemeRegression/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "Cannot create screenshot $name"
        }
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(resolver.update(uri, complete, null, null) == 1) { "Cannot publish screenshot $uri" }
            published = true
            Log.i("FluxThemeRegression", "Saved $name.png ${image.width}x${image.height} at $uri")
        } finally {
            // Only discard the new pending row if its write failed, never previous evidence.
            if (!published) resolver.delete(uri, null, null)
        }
        return image.toPixelMap()
    }

    private fun assertPixel(expected: Color, actual: Color) {
        val detail = "expected=%08X actual=%08X".format(expected.toArgb(), actual.toArgb())
        assertEquals("Surface alpha ($detail)", 1f, actual.alpha, 0.01f)
        assertEquals("Surface red ($detail)", expected.red, actual.red, 2f / 255f)
        assertEquals("Surface green ($detail)", expected.green, actual.green, 2f / 255f)
        assertEquals("Surface blue ($detail)", expected.blue, actual.blue, 2f / 255f)
    }
}

/** Exercise the real default isSystemInDarkTheme path without changing the device's global mode. */
@Composable
private fun TestSystemTheme(
    systemDark: Boolean,
    themeOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val configuration = nightConfiguration(LocalConfiguration.current, systemDark)
    CompositionLocalProvider(LocalConfiguration provides configuration) {
        if (themeOverride == null) PayloadDumperFluxTheme(content = content)
        else PayloadDumperFluxTheme(darkTheme = themeOverride, content = content)
    }
}

private fun nightConfiguration(base: Configuration, dark: Boolean): Configuration = Configuration(base).apply {
    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
}
