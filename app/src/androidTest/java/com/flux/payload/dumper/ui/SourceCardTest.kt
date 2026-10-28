package com.flux.payload.dumper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.flux.payload.dumper.R
import com.flux.payload.dumper.model.LocalDocument
import com.flux.payload.dumper.model.SourceMode
import com.flux.payload.dumper.model.SourceSelection
import com.flux.payload.dumper.ui.components.SourceCard
import com.flux.payload.dumper.ui.theme.PayloadDumperFluxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SourceCardTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localIsAnActionNotATextEditor() {
        var browsed = false
        compose.setContent {
            PayloadDumperFluxTheme {
                SourceCard(SourceSelection(), {}, {}, { browsed = true }, false, false, {})
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("parse_source").assertIsNotEnabled()
        compose.onNodeWithTag("local_file_picker").assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(browsed) }
    }

    @Test fun selectedDocumentShowsNameAndEnablesParse() {
        val state = SourceSelection(local = LocalDocument("content://files/1", "full-OTA.zip", 2048))
        compose.setContent {
            PayloadDumperFluxTheme { SourceCard(state, {}, {}, {}, false, false, {}) }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("full-OTA.zip", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("parse_source").assertIsEnabled()
    }

    @Test fun networkAloneHasAnEditorAndSwitchingKeepsFile() {
        var state by mutableStateOf(SourceSelection(local = LocalDocument("content://files/1", "local.zip", 1024)))
        compose.setContent {
            PayloadDumperFluxTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SourceCard(state, { state = state.copy(mode = it) }, { state = state.copy(networkUrl = it) }, {}, false, false, {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.source_network)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag("network_url_input").performTextInput("https://example.com/OTA.zip")
        compose.onNodeWithText(context.getString(R.string.source_local)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("local.zip", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals("https://example.com/OTA.zip", state.networkUrl) }
    }

    @Test fun busySourceCannotBeReplacedOrParsedAgain() {
        val state = SourceSelection(local = LocalDocument("content://files/1", "OTA.zip", 1024))
        compose.setContent {
            PayloadDumperFluxTheme(darkTheme = true) { SourceCard(state, {}, {}, {}, true, true, {}) }
        }
        compose.onNodeWithTag("local_file_picker").assertIsNotEnabled()
        compose.onNodeWithTag("parse_source").assertIsNotEnabled()
    }
}
