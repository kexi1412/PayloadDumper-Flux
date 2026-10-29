package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import chromeos_update_engine.UpdateMetadata.InstallOperation
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Serialized synthetic CrAU fixtures: minor_version is not proof of source dependence. */
class PayloadOperationCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()

    private class Memory(private val bytes: ByteArray) : PayloadSource {
        val reads = CopyOnWriteArrayList<Pair<Long, Int>>()
        override val size = bytes.size.toLong()
        override fun fileName() = "payload.bin"
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            reads += offset to len
            if (offset < 0 || len < 0 || offset > size || len > size - offset) throw IOException("EOF")
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
        }
        override fun close() = Unit
    }

    private data class Fixture(val source: Memory, val payload: Payload) {
        val partition get() = payload.manifest.getPartitions(0)
    }

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun extent(start: Long, count: Long = 1) = UpdateMetadata.Extent.newBuilder()
        .setStartBlock(start).setNumBlocks(count).build()
    private fun operation(type: InstallOperation.Type, blob: ByteArray, offset: Long, vararg starts: Long) =
        InstallOperation.newBuilder().setType(type).setDataOffset(offset).setDataLength(blob.size.toLong())
            .setDataSha256Hash(ByteString.copyFrom(sha(blob)))
            .addAllDstExtents(starts.map { extent(it) }).build()
    private fun xz(bytes: ByteArray) = ByteArrayOutputStream().also { output ->
        XZOutputStream(output, LZMA2Options()).use { it.write(bytes) }
    }.toByteArray()
    private fun bz(bytes: ByteArray) = ByteArrayOutputStream().also { output ->
        BZip2CompressorOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    private fun fixture(
        operations: List<InstallOperation>, blobs: ByteArray, image: ByteArray,
        minor: Int = 9, oldInfo: Boolean = false, imageHash: ByteArray = sha(image),
    ): Fixture {
        val partition = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(image.size.toLong())
                .setHash(ByteString.copyFrom(imageHash))).addAllOperations(operations)
        if (oldInfo) partition.setOldPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder()
            .setSize(image.size.toLong()).setHash(ByteString.copyFrom(sha(ByteArray(image.size)))))
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(BLOCK)
            .setMinorVersion(minor).addPartitions(partition).build().toByteArray()
        // Include a nonempty metadata signature region to exercise the parsed data boundary.
        val signature = byteArrayOf(1, 2, 3)
        val raw = ByteBuffer.allocate(24).put("CrAU".toByteArray()).putLong(2)
            .putLong(manifest.size.toLong()).putInt(signature.size).array() + manifest + signature + blobs
        val source = Memory(raw)
        val payload = PayloadParser.parse(source)
        source.reads.clear() // Only operation-blob reads, not necessary header/manifest reads.
        return Fixture(source, payload)
    }

    private fun resume(data: Fixture, directory: File) = ResumeState(File(directory, "boot.img.progress"),
        payloadResumeIdentity(data.payload, "boot"), data.partition.operationsCount)

    private fun extract(data: Fixture, directory: File, state: ResumeState = resume(data, directory)) = runBlocking {
        PayloadExtractor(workers = 2).extractPartition(data.payload, data.source, data.partition,
            directory, true, state) {}
    }

    @Test fun nonzeroMinorSelfContainedPayloadParsesExtractsAndVerifies() {
        val a = ByteArray(BLOCK) { (it % 251).toByte() }
        val b = ByteArray(BLOCK) { (it * 3 % 251).toByte() }
        val c = ByteArray(BLOCK) { (it * 7 % 251).toByte() }
        val d = ByteArray(BLOCK) { (it * 11 % 251).toByte() }
        val xzBlob = xz(d + b) // Scattered extents are intentionally not in image order.
        val bzBlob = bz(c)
        val blobs = a + xzBlob + bzBlob
        val image = a + b + ByteArray(BLOCK) + c + d + ByteArray(BLOCK)
        val operations = listOf(
            operation(InstallOperation.Type.REPLACE, a, 0, 0),
            operation(InstallOperation.Type.REPLACE_XZ, xzBlob, a.size.toLong(), 4, 1),
            operation(InstallOperation.Type.ZERO, byteArrayOf(), 0, 2),
            operation(InstallOperation.Type.REPLACE_BZ, bzBlob, (a.size + xzBlob.size).toLong(), 3),
            operation(InstallOperation.Type.DISCARD, byteArrayOf(), 0, 5),
        )
        for (minor in listOf(0, 1, 3, 9)) {
            val data = fixture(operations, blobs, image, minor, oldInfo = true)
            assertEquals(minor, data.payload.manifest.minorVersion)
            val directory = File(temp.root, "full-$minor")
            val state = resume(data, directory)
            val result = extract(data, directory, state)
            assertEquals("minor=$minor", true, result.verified)
            assertArrayEquals(image, result.outputFile.readBytes())
            assertArrayEquals(sha(image), sha(result.outputFile.readBytes()))
            assertEquals("ZERO/DISCARD must not fetch data", 3, data.source.reads.size)
            assertEquals(blobs.size.toLong(), data.source.reads.sumOf { it.second.toLong() })
            assertTrue(state.completedIndices().isEmpty())
        }
    }

    @Test fun nonzeroMinorReplaceWithUnusedSourceLengthExtractsAndVerifies() {
        val bytes = ByteArray(BLOCK) { (it % 251).toByte() }
        // The proto uses src_length only for BSDIFF/SOURCE_BSDIFF. It is ignored on
        // REPLACE, even when nonzero or different from the destination length.
        val replace = operation(InstallOperation.Type.REPLACE, bytes, 0, 0).toBuilder()
            .setSrcLength(BLOCK.toLong() * 2).build()
        val data = fixture(listOf(replace), bytes, bytes, minor = 9)
        val result = extract(data, File(temp.root, "unused-source-length"))
        assertEquals(true, result.verified)
        assertArrayEquals(bytes, result.outputFile.readBytes())
        assertArrayEquals(sha(bytes), sha(result.outputFile.readBytes()))
        assertEquals(1, data.source.reads.size)
    }

    @Test fun everySourceDependentTypeRejectsWholePlanAtMinorZeroBeforeReadingOrCreatingFiles() {
        val types = listOf(InstallOperation.Type.MOVE, InstallOperation.Type.BSDIFF,
            InstallOperation.Type.SOURCE_COPY, InstallOperation.Type.SOURCE_BSDIFF,
            InstallOperation.Type.BROTLI_BSDIFF, InstallOperation.Type.PUFFDIFF,
            InstallOperation.Type.ZUCCHINI, InstallOperation.Type.LZ4DIFF_BSDIFF,
            InstallOperation.Type.LZ4DIFF_PUFFDIFF)
        val firstBlob = ByteArray(BLOCK) { 65 }
        val first = operation(InstallOperation.Type.REPLACE, firstBlob, 0, 0)
        for (type in types) {
            val dependent = InstallOperation.newBuilder().setType(type)
                .addSrcExtents(extent(0)).addDstExtents(extent(1)).build()
            val data = fixture(listOf(first, dependent), firstBlob, ByteArray(BLOCK * 2), minor = 0)
            val directory = File(temp.root, "delta-${type.name}")
            val error = assertThrows(IOException::class.java) { extract(data, directory) }
            assertTrue(error.message, error.message.orEmpty().contains("operation[1]"))
            assertTrue(error.message, error.message.orEmpty().contains(type.name))
            assertTrue(error.message, error.message.orEmpty().contains("old/source"))
            assertTrue(error.message, error.message.orEmpty().contains("delta"))
            assertTrue("Earlier REPLACE blob must not be read", data.source.reads.isEmpty())
            assertFalse("No directory, image or journal may be created", directory.exists())
        }
    }

    @Test fun sourceDependentTypeIsRejectedEvenIfSourceExtentsAreMissing() {
        val dependent = InstallOperation.newBuilder().setType(InstallOperation.Type.SOURCE_COPY)
            .addDstExtents(extent(0)).build()
        val data = fixture(listOf(dependent), byteArrayOf(), ByteArray(BLOCK), minor = 0)
        val directory = File(temp.root, "missing-source-extents")
        val error = assertThrows(IOException::class.java) { extract(data, directory) }
        assertTrue(error.message, error.message.orEmpty().contains("operation[0] (SOURCE_COPY)"))
        assertTrue(data.source.reads.isEmpty())
        assertFalse(directory.exists())
    }

    @Test fun otherwiseSelfContainedOperationsWithSourceReferencesFailBeforeIo() {
        val blob = ByteArray(BLOCK) { 42 }
        val first = operation(InstallOperation.Type.REPLACE, blob, 0, 0)
        val second = operation(InstallOperation.Type.REPLACE, blob, 0, 1)
        val invalid = listOf(
            "src_extents" to second.toBuilder().addSrcExtents(extent(0)).build(),
            "src_sha256_hash" to second.toBuilder().setSrcSha256Hash(ByteString.copyFrom(sha(blob))).build(),
        )
        for ((field, operation) in invalid) {
            for (minor in listOf(0, 9)) {
                val data = fixture(listOf(first, operation), blob, ByteArray(BLOCK * 2), minor)
                val directory = File(temp.root, "$field-$minor")
                val error = assertThrows(IOException::class.java) { extract(data, directory) }
                assertTrue(error.message, error.message.orEmpty().contains("operation[1] (REPLACE)"))
                assertTrue(error.message, error.message.orEmpty().contains(field))
                assertTrue(data.source.reads.isEmpty())
                assertFalse(directory.exists())
            }
        }
    }

    @Test fun missingOrUnknownTypeCannotFallBackToCompressionMagic() {
        val bytes = xz(ByteArray(BLOCK))
        // Unknown proto2 enum values have no known required type; a partial message must not
        // be interpreted as the REPLACE default even when its blob has valid compression magic.
        val unknown = operation(InstallOperation.Type.REPLACE_XZ, bytes, 0, 0).toBuilder()
            .clearType().mergeFrom(byteArrayOf(8, 127)).buildPartial()
        assertFalse(unknown.hasType())
        val partition = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(BLOCK.toLong()))
            .addOperations(unknown).buildPartial()
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(BLOCK)
            .setMinorVersion(9).addPartitions(partition).buildPartial()
        val source = Memory(bytes)
        val data = Fixture(source, Payload("payload.bin", PayloadHeader(2, 0, 0), manifest, 0, BLOCK, source.size))
        val directory = File(temp.root, "unknown-operation")
        val error = assertThrows(IOException::class.java) { extract(data, directory) }
        assertTrue(error.message, error.message.orEmpty().contains("operation[0] (unknown)"))
        assertTrue(error.message, error.message.orEmpty().contains("unknown operation type"))
        assertTrue(source.reads.isEmpty())
        assertFalse(directory.exists())
    }

    @Test fun nonzeroMinorStillRejectsOverlappingExtentsBeforeIo() {
        val bytes = ByteArray(BLOCK * 2)
        val invalid = operation(InstallOperation.Type.REPLACE, bytes, 0, 0, 0)
        val data = fixture(listOf(invalid), bytes, bytes)
        val directory = File(temp.root, "overlap")
        val error = assertThrows(IOException::class.java) { extract(data, directory) }
        assertTrue(error.message, error.message.orEmpty().contains("Overlapping"))
        assertTrue(data.source.reads.isEmpty())
        assertFalse(directory.exists())
    }

    @Test fun nonzeroMinorStillRejectsBlobOutsideSourceBeforeIo() {
        val bytes = ByteArray(BLOCK)
        val invalid = operation(InstallOperation.Type.REPLACE, bytes, 1, 0)
        val data = fixture(listOf(invalid), bytes, bytes)
        val directory = File(temp.root, "out-of-bounds")
        val error = assertThrows(IOException::class.java) { extract(data, directory) }
        assertTrue(error.message, error.message.orEmpty().contains("beyond source"))
        assertTrue(data.source.reads.isEmpty())
        assertFalse(directory.exists())
    }

    @Test fun nonzeroMinorStillChecksCompressedDecodedLengthBeforeJournaling() {
        for (type in listOf(InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ)) {
            val expanded = ByteArray(BLOCK + 1)
            val compressed = if (type == InstallOperation.Type.REPLACE_BZ) bz(expanded) else xz(expanded)
            val op = operation(type, compressed, 0, 0)
            val data = fixture(listOf(op), compressed, ByteArray(BLOCK))
            val directory = File(temp.root, "oversized-$type")
            val state = resume(data, directory)
            val error = assertThrows(IOException::class.java) { extract(data, directory, state) }
            assertTrue(error.message, error.message.orEmpty().contains("expected decoded length"))
            assertFalse(state.isDone(0))
            assertArrayEquals(ByteArray(BLOCK), File(directory, "boot.img").readBytes())
        }
    }

    @Test fun nonzeroMinorStillChecksOperationHashBeforeJournaling() {
        val bytes = ByteArray(BLOCK) { 33 }
        val badHash = operation(InstallOperation.Type.REPLACE, bytes, 0, 0).toBuilder()
            .setDataSha256Hash(ByteString.copyFrom(ByteArray(32))).build()
        val data = fixture(listOf(badHash), bytes, bytes)
        val directory = File(temp.root, "blob-hash")
        val state = resume(data, directory)
        val error = assertThrows(IOException::class.java) { extract(data, directory, state) }
        assertTrue(error.message, error.message.orEmpty().contains("Operation blob SHA-256 mismatch"))
        assertFalse(state.isDone(0))
        assertArrayEquals(ByteArray(BLOCK), File(directory, "boot.img").readBytes())
    }

    @Test fun nonzeroMinorStillChecksPartitionHashAndClearsResume() {
        val bytes = ByteArray(BLOCK) { 33 }
        val data = fixture(listOf(operation(InstallOperation.Type.REPLACE, bytes, 0, 0)), bytes, bytes,
            imageHash = ByteArray(32))
        val directory = File(temp.root, "partition-hash")
        val state = resume(data, directory)
        val result = extract(data, directory, state)
        assertEquals(false, result.verified)
        assertArrayEquals(bytes, result.outputFile.readBytes())
        assertTrue(state.completedIndices().isEmpty())
    }

    companion object { private const val BLOCK = 4096 }
}
