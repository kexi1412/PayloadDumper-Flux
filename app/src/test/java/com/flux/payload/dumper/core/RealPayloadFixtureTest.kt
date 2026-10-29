package com.flux.payload.dumper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Parses the real 8.2 GB PJZ110 package through the production parser without the network.
 *
 * Only the byte ranges the parser actually touches are checked in (central directory tail,
 * payload header/manifest, metadata block and every boot operation blob). Every other read is
 * a hole and fails loudly, so this test proves the ZIP64 locator, CrAU parser and boot kernel
 * reconstruction against real bytes rather than a mock that could accept a broken implementation.
 */
class RealPayloadFixtureTest {

    private class FixtureSource(private val allowedBootOps: Set<Int>? = null) : PayloadSource {
        private val bootOffsets = mutableSetOf<Long>()
        var bootBytes = 0L
        val bootReads = mutableListOf<Long>()
        private val chunks: List<Pair<Long, ByteArray>> = buildList {
            add(0L to resource("metadata-block.bin"))
            add(2794L to resource("payload-head.bin"))
            add(8_213_840_646L to resource("zip-tail.bin"))

            // Every non-empty boot operation blob, at its absolute payload offset.
            val blobs = resource("boot-blobs.bin")
            var cursor = 0
            resourceText("boot-index.txt").lineSequence()
                .filter { it.isNotBlank() }
                .forEachIndexed { index, line ->
                    val (offset, length) = line.split(' ').map(String::toLong)
                    if (allowedBootOps == null || index in allowedBootOps) {
                        add(offset to blobs.copyOfRange(cursor, cursor + length.toInt()))
                        bootOffsets += offset
                    }
                    cursor += length.toInt()
                }
            check(cursor == blobs.size) { "boot index does not cover the blob file" }
        }

        override val size = 8_214_889_222L

        override fun fileName() = "d92cb1e567994a4eb1700351ee09d71d.zip"

        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            for ((start, data) in chunks) {
                if (offset >= start && offset + len <= start + data.size) {
                    data.copyInto(buffer, bufOffset, (offset - start).toInt(), (offset - start).toInt() + len)
                    if (offset in bootOffsets) {
                        bootBytes += len
                        bootReads += offset
                    }
                    return
                }
            }
            throw IOException("fixture hole at $offset+$len")
        }

        override fun close() {}

        private fun resource(name: String): ByteArray =
            checkNotNull(javaClass.getResourceAsStream("/real-ota/$name")) { "missing $name" }.use { it.readBytes() }

        private fun resourceText(name: String): String =
            checkNotNull(javaClass.getResourceAsStream("/real-ota/$name")) { "missing $name" }
                .bufferedReader().use { it.readText() }
    }

    @Test
    fun readsActualKernelFromOnlyHeaderAndInitializedDataOperations() {
        val source = FixtureSource(setOf(0, 16, 17))
        val result = UniversalParser.parse(source)
        assertEquals("6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k", result.archiveInfo.kernelVersion)
        assertEquals(54, result.archiveInfo.partitionCount)
        assertEquals("15", result.archiveInfo.androidVersion)
        assertEquals("2025-09-01", result.archiveInfo.securityPatchLevel)
        assertEquals(1_052_544L, source.bootBytes)
        assertEquals(listOf(1_275_947_753L, 1_286_397_533L, 1_286_586_413L), source.bootReads)
        println("PJZ110_UTS bootBytes=${source.bootBytes} bootOperations=${source.bootReads.size} kernel=${result.archiveInfo.kernelVersion}")
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
        assertEquals(
            "6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k",
            info.kernelVersion,
        )
    }
}
