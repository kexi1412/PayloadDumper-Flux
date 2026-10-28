package com.flux.payload.dumper.model

import org.junit.Assert.*
import org.junit.Test

class SourceSelectionTest {
    @Test fun emptyLocalCannotParse() {
        assertFalse(SourceSelection().canParse)
        assertEquals("", SourceSelection().activeSource)
    }

    @Test fun localMustBeAPickedContentUri() {
        assertFalse(SourceSelection(local = LocalDocument("/sdcard/payload.bin")).canParse)
        assertTrue(SourceSelection(local = LocalDocument("content://documents/123")).canParse)
    }

    @Test fun switchingModesDoesNotParseTheOtherSource() {
        val local = LocalDocument("content://documents/123", "OTA.zip", 123L)
        val state = SourceSelection(local = local, networkUrl = "https://example.com/remote.zip")
        assertEquals(local.uri, state.activeSource)
        val remote = state.copy(mode = SourceMode.NETWORK)
        assertEquals("https://example.com/remote.zip", remote.activeSource)
        assertEquals(local, remote.local)
        assertEquals(local.uri, remote.copy(mode = SourceMode.LOCAL).activeSource)
    }

    @Test fun clearingRemoteDoesNotClearPickedFile() {
        val state = SourceSelection(local = LocalDocument("content://documents/1"), networkUrl = "https://example.com/ota.zip")
        assertTrue(state.copy(networkUrl = "").canParse)
        assertFalse(state.copy(mode = SourceMode.NETWORK, networkUrl = "").canParse)
    }

    @Test fun invalidNetworkValuesCannotParse() {
        listOf("", "payload.bin", "content://documents/1", "ftp://example.com/ota", "https://", "https://x y").forEach {
            assertFalse(it, SourceSelection(mode = SourceMode.NETWORK, networkUrl = it).canParse)
        }
        assertTrue(SourceSelection(mode = SourceMode.NETWORK, networkUrl = "  https://example.com/ota.zip  ").canParse)
    }

    @Test fun legacyPathsAreNotReintroducedAsEditableLocalInput() {
        assertNull(SourceSelection.fromLegacy("/sdcard/Download/ota.zip").local)
        assertEquals("content://documents/1", SourceSelection.fromLegacy("content://documents/1").activeSource)
        assertEquals(SourceMode.NETWORK, SourceSelection.fromLegacy("https://example.com/ota.zip").mode)
    }
}
