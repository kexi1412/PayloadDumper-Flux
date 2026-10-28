package com.flux.payload.dumper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Parses the real 8.2 GB PJZ110 package through the production parser without the network.
 *
 * Only the byte ranges the parser actually touches are checked in (central directory tail,
 * payload header/manifest, metadata block). Every other read is a hole and fails loudly, so
 * this test proves the ZIP64 locator and CrAU parser against real bytes rather than a mock
 * that could accidentally accept the bug.
 */
class RealPayloadFixtureTest {

    private class FixtureSource : PayloadSource {
        private val chunks: List<Pair<Long, ByteArray>> = listOf(
            0L to resource("metadata-block.bin"),
            2794L to resource("payload-head.bin"),
            8_213_840_646L to resource("zip-tail.bin"),
        )

        override val size = 8_214_889_222L

        override fun fileName() = "d92cb1e567994a4eb1700351ee09d71d.zip"

        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            for ((start, data) in chunks) {
                if (offset >= start && offset + len <= start + data.size) {
                    data.copyInto(buffer, bufOffset, (offset - start).toInt(), (offset - start).toInt() + len)
                    return
                }
            }
            throw IOException("fixture hole at $offset+$len")
        }

        override fun close() {}

        private fun resource(name: String): ByteArray =
            checkNotNull(javaClass.getResourceAsStream("/real-ota/$name")) { "missing $name" }.use { it.readBytes() }
    }

    @Test
    fun locatesPayloadThroughRealZip64Directory() {
        assertEquals(2_861L, ZipLocator.findPayloadOffset(FixtureSource()))
    }

    @Test
    fun parsesRealCrAuManifestAndFirmwareMetadata() {
        val payload = PayloadParser.parse(FixtureSource())
        assertEquals(4_096, payload.blockSize)
        assertEquals(54, payload.manifest.partitionsCount)

        val result = UniversalParser.parse(FixtureSource())
        assertTrue("expected CrAU, got ${result::class.simpleName}", result is ParseResult.CrAU)
        val info = result.archiveInfo
        assertEquals(8_214_889_222L, info.fileSize)
        assertEquals(54, info.partitionCount)
        assertEquals(4_096, info.blockSize)
        assertEquals("2025-09-01", info.securityPatchLevel)
        assertEquals("15", info.androidVersion)
        assertTrue(
            "fingerprint was '${info.fingerprint}'",
            info.fingerprint.contains("OnePlus/PJZ110/OP5D0DL1:15/"),
        )
    }
}
