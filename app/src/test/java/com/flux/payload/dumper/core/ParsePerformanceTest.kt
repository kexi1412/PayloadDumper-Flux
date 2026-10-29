package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Deterministic I/O budgets: the real StepAOS ZIP is 6.7 GB, not a tiny synthetic ZIP. */
class ParsePerformanceTest {
    private class StepAosSource : PayloadSource {
        private val head = resource("parse-head.bin")
        private val tail = resource("zip-tail.bin")
        val reads = mutableListOf<Pair<Long, Int>>()
        var holes = 0
        var closed = false
        override val size = 6_718_923_088L
        val tailStart = size - tail.size
        override fun fileName() = "update.zip"
        override fun close() { closed = true }
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            check(!closed)
            reads += offset to len
            val start: Long
            val bytes: ByteArray
            when {
                offset >= 0 && offset + len <= head.size -> { start = 0; bytes = head }
                offset >= tailStart && offset + len <= size -> { start = tailStart; bytes = tail }
                else -> { holes++; throw IOException("unavailable boot/image bytes at $offset+$len") }
            }
            bytes.copyInto(buffer, bufOffset, (offset - start).toInt(), (offset - start).toInt() + len)
        }
        private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/stepaos/$name"))
            .use { it.readBytes() }
    }

    @Test fun realStepAosFastParseReadsNoBootOperationsAndOnlyOneDirectoryTail() {
        val source = StepAosSource()
        val result = UniversalParser.parse(source, includeKernel = false)
        assertTrue(result is ParseResult.CrAU)
        val payload = (result as ParseResult.CrAU).payload
        val info = result.archiveInfo
        assertEquals(57, info.partitionCount)
        assertEquals(4096, info.blockSize)
        assertEquals(6_718_923_088L, info.fileSize)
        assertEquals(259_101L, payload.dataOffset)
        assertEquals(253_900L, payload.header.manifestSize)
        assertEquals("16", info.androidVersion)
        assertEquals("2025-12-05", info.securityPatchLevel)
        assertTrue(info.fingerprint.startsWith("StepX/pandora/pandora:16/"))
        assertEquals("", info.kernelVersion)
        assertEquals("fast parse attempted optional boot/image I/O", 0, source.holes)
        assertEquals("ZIP directory tail was downloaded again", 1, source.reads.count { it.first == source.tailStart })
        val bytesRead = source.reads.sumOf { it.second.toLong() }
        assertTrue("fast parse transferred $bytesRead bytes", bytesRead < 350_000L)
        assertTrue("too many round trips: ${source.reads}", source.reads.size <= 10)
        assertFalse("the parse cache closed the extraction source", source.closed)
        println("STEPAOS_FAST bytes=$bytesRead reads=${source.reads.size} bootReads=${source.holes} partitions=${info.partitionCount}")
    }

    @Test fun cacheDoesNotSurviveAnotherParseOrOwnTheExtractionSource() {
        val source = StepAosSource()
        repeat(2) { UniversalParser.parse(source, includeKernel = false) }
        assertEquals(2, source.reads.count { it.first == source.tailStart })
        assertFalse(source.closed)
    }

    @Test fun payloadFixedHeaderUsesOneReadNotFour() {
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4096)
            .addPartitions(UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")).build().toByteArray()
        val header = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .put("CrAU".toByteArray()).putLong(2).putLong(manifest.size.toLong()).putInt(0).array()
        val source = CountingSource(header + manifest, "payload.bin")
        val result = PayloadParser.parse(source)
        assertEquals(1, result.manifest.partitionsCount)
        assertEquals(listOf(0L to 24, 24L to manifest.size), source.reads)
    }

    @Test fun cacheServesContainedRangesWithoutExposingMutableCachedBytes() {
        val source = CountingSource(ByteArray(256) { it.toByte() })
        val cache = ParseReadCache(source, 128)
        val first = cache.readFully(64, 64)
        first.fill(0)
        val output = ByteArray(40) { 0x55 }
        cache.readFully(80, output, 3, 16)
        assertArrayEquals(ByteArray(16) { (80 + it).toByte() }, output.copyOfRange(3, 19))
        assertTrue(output.take(3).all { it == 0x55.toByte() })
        assertTrue(output.drop(19).all { it == 0x55.toByte() })
        assertEquals(1, source.reads.size)
        cache.close()
        assertFalse(source.closed)
        assertArrayEquals(byteArrayOf(80), source.readFully(80, 1))
    }

    @Test fun cacheEvictsWithinItsByteBudgetAndBypassesLargeOperations() {
        val source = CountingSource(ByteArray(256) { it.toByte() })
        val cache = ParseReadCache(source, 16)
        cache.readFully(0, 8)
        cache.readFully(8, 8)
        cache.readFully(16, 8)
        cache.readFully(0, 8) // oldest range had to be evicted
        repeat(2) { cache.readFully(32, 32) } // exceeds the entire budget: never retained
        assertEquals(6, source.reads.size)
        assertEquals(2, source.reads.count { it == 32L to 32 })
    }

    @Test fun failedReadsAreNeverCached() {
        var attempts = 0
        val source = object : PayloadSource {
            override val size = 8L
            override fun fileName() = "partial.bin"
            override fun close() {}
            override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
                attempts++
                buffer[bufOffset] = 99
                if (attempts == 1) throw EOFException("truncated")
                repeat(len) { buffer[bufOffset + it] = (offset + it).toByte() }
            }
        }
        val cache = ParseReadCache(source)
        assertThrows(EOFException::class.java) { cache.readFully(0, 8) }
        assertArrayEquals(ByteArray(8) { it.toByte() }, cache.readFully(0, 8))
        assertEquals(2, attempts)
    }

    private class CountingSource(private val bytes: ByteArray, private val name: String = "memory.bin") : PayloadSource {
        val reads = mutableListOf<Pair<Long, Int>>()
        var closed = false
        override val size = bytes.size.toLong()
        override fun fileName() = name
        override fun close() { closed = true }
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            if (offset < 0 || len < 0 || offset > size - len) throw EOFException()
            reads += offset to len
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
        }
    }
}
