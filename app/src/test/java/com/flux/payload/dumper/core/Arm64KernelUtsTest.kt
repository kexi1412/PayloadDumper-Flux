package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import com.google.protobuf.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.security.MessageDigest

class Arm64KernelUtsTest {
    private val release = "6.6.66-android15-8-g29d86c5fc9dd-abogki428889875-4k"
    private val dataOffset = 0x4000
    private val table = 64 + 24 + 240

    @Test fun sectionUsesFileOffsetNotVirtualAddress() {
        val kernel = kernel()
        val section = Arm64KernelUts.dataSection(kernel.copyOf(4096), kernel.size)
        assertEquals(Arm64KernelUts.DataSection(dataOffset, 4096), section)
        assertEquals(release, Arm64KernelUts.release(kernel.copyOfRange(dataOffset, kernel.size)))
    }

    @Test fun ignoresUtsDecoysInUnmappedRawPadding() {
        val kernel = kernel()
        put32(kernel, table + 40 + 8, 512) // virtual mapping ends before raw file padding
        uts("6.6.67-padding-decoy").copyInto(kernel, dataOffset + 900)
        val section = requireNotNull(Arm64KernelUts.dataSection(kernel.copyOf(4096), kernel.size))
        assertEquals(512, section.size)
        assertEquals(release, Arm64KernelUts.release(kernel.copyOfRange(section.offset, section.offset + section.size)))
        val (source, payload) = payload(kernel)
        assertEquals(release, FirmwareMetadataReader.readKernel(source, base(source), payload).kernelVersion)
    }

    @Test fun rejectsMalformedOrUnrelatedPeLayouts() {
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { it[56] = 0 }, { put16(it, 68, 0x8664) }, { put32(it, 60, Int.MAX_VALUE) },
            { put16(it, 70, 65535) }, { put16(it, 84, 65535) },
            { put32(it, table + 40 + 20, 0x7fffffff) },
            { put32(it, table + 40 + 16, 0x7fffffff) },
            { put32(it, table + 40 + 20, 4096) },
            { put32(it, table + 40 + 36, 0xe0000060.toInt()) },
            { put32(it, table + 40 + 16, 0) },
        )
        for ((index, mutate) in mutations.withIndex()) {
            val kernel = kernel().also(mutate)
            assertNull("mutation $index", Arm64KernelUts.dataSection(kernel.copyOf(4096), kernel.size))
        }
        assertNull(Arm64KernelUts.dataSection(ByteArray(63), 0x5000))
    }

    @Test fun repeatedUtsCopiesAgreeButConflictingReleasesStayUnknown() {
        val data = ByteArray(2048)
        uts(release).copyInto(data, 50)
        uts(release).copyInto(data, 900)
        assertEquals(release, Arm64KernelUts.release(data))
        uts("6.6.67-different").copyInto(data, 900)
        assertEquals("", Arm64KernelUts.release(data))
    }

    @Test fun bannerOrDamagedNamespaceIsNotAProvenUtsRelease() {
        assertNull(Arm64KernelUts.release("Linux version $release (builder@host)".toByteArray()))
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { it[6] = 1 }, // sysname padding must be zero
            { it[65 * 4] = 'x'.code.toByte() },
            { it[65 * 3] = '!'.code.toByte() },
            { for (i in 0 until 65) it[65 * 2 + i] = 'a'.code.toByte() },
        )
        for (mutate in mutations) assertNull(Arm64KernelUts.release(uts(release).also(mutate)))
    }

    @Test fun payloadRangeHonorsReversedExtentsAndDoesNotReadText() {
        val kernel = kernel()
        "Linux version 9.9.9-decoy (not-init-uts)".toByteArray().copyInto(kernel, 6000)
        val (source, payload) = payload(kernel)
        val info = FirmwareMetadataReader.readKernel(source, base(source), payload)
        assertEquals(release, info.kernelVersion)
        assertEquals("15", info.androidVersion)
        assertEquals("2025-09-01", info.securityPatchLevel)
        assertEquals(4L + 8192 + 4096, source.bytesRead)
    }

    @Test fun namespaceCanCrossPhysicalOperationBoundaries() {
        val kernel = kernel(dataSize = 8192, utsOffset = 4096 - 200)
        val (source, payload) = payload(kernel)
        assertEquals(release, FirmwareMetadataReader.readKernel(source, base(source), payload).kernelVersion)
        // Header + last data block share one scattered operation; the other data block is separate.
        assertEquals(4L + 8192 + 4096 + 4096, source.bytesRead)
    }

    @Test fun missingNamespaceFallsBackWithoutFetchingProbeOperationsTwice() {
        val kernel = kernel(withUts = false)
        "Linux version 6.1.118-fallback (builder@host)".toByteArray().copyInto(kernel, 6000)
        val (source, payload) = payload(kernel)
        assertEquals("6.1.118-fallback", FirmwareMetadataReader.readKernel(source, base(source), payload).kernelVersion)
        assertEquals("all image bytes exactly once plus ZIP magic", source.size + 4, source.bytesRead)
    }

    @Test fun conflictingNamespaceDoesNotFallBackToAnUnrelatedBanner() {
        val kernel = kernel()
        uts("6.6.67-conflict").copyInto(kernel, dataOffset + 900)
        "Linux version 9.9.9-decoy (builder@host)".toByteArray().copyInto(kernel, 6000)
        val (source, payload) = payload(kernel)
        assertEquals("", FirmwareMetadataReader.readKernel(source, base(source), payload).kernelVersion)
    }

    @Test fun hashFailureInSelectedOperationNeverBecomesAVersion() {
        val (source, payload) = payload(kernel())
        source.bytes[102] = (source.bytes[102].toInt() xor 1).toByte()
        assertEquals("", FirmwareMetadataReader.readKernel(source, base(source), payload).kernelVersion)
    }

    private fun kernel(dataSize: Int = 4096, utsOffset: Int = 100, withUts: Boolean = true): ByteArray {
        val image = ByteArray(dataOffset + dataSize)
        image[0] = 'M'.code.toByte(); image[1] = 'Z'.code.toByte()
        put32(image, 56, 0x644d5241); put32(image, 60, 64)
        put32(image, 64, 0x4550); put16(image, 68, 0xaa64); put16(image, 70, 2)
        put16(image, 84, 240); put16(image, 88, 0x20b); put32(image, 148, 4096)
        section(image, table, ".text", 4096, dataOffset - 4096, 4096, 0x60000020)
        // Deliberately distinct RVA: file reads must not use a virtual address.
        section(image, table + 40, ".data", dataOffset, dataSize, 0x8000, 0xc0000040.toInt())
        if (withUts) uts(release).copyInto(image, dataOffset + utsOffset)
        return image
    }

    private fun section(bytes: ByteArray, at: Int, name: String, raw: Int, size: Int, rva: Int, flags: Int) {
        name.toByteArray().copyInto(bytes, at)
        put32(bytes, at + 8, size); put32(bytes, at + 12, rva)
        put32(bytes, at + 16, size); put32(bytes, at + 20, raw); put32(bytes, at + 36, flags)
    }

    private fun uts(release: String) = ByteArray(390).also { bytes ->
        listOf("Linux", "(none)", release, "#1 SMP PREEMPT Tue Jul 1 19:48:18 UTC 2025", "aarch64", "(none)")
            .forEachIndexed { i, field -> field.toByteArray().copyInto(bytes, i * 65) }
    }

    private fun payload(kernel: ByteArray): Pair<MemorySource, Payload> {
        val image = ByteArray(4096 + kernel.size)
        "ANDROID!".toByteArray().copyInto(image)
        put32(image, 8, kernel.size); put32(image, 40, 4)
        kernel.copyInto(image, 4096)
        val lastBlock = image.size / 4096 - 1
        val groups = listOf(listOf(lastBlock, 0)) + (1 until lastBlock).map { listOf(it) }
        val bytes = ArrayList<Byte>()
        val boot = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(image.size.toLong()))
        for (blocks in groups) {
            val content = blocks.fold(ByteArray(0)) { acc, block -> acc + image.copyOfRange(block * 4096, (block + 1) * 4096) }
            val op = UpdateMetadata.InstallOperation.newBuilder().setType(UpdateMetadata.InstallOperation.Type.REPLACE)
                .setDataOffset(bytes.size.toLong()).setDataLength(content.size.toLong())
                .setDataSha256Hash(ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(content)))
            for (block in blocks) op.addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(block.toLong()).setNumBlocks(1))
            boot.addOperations(op)
            bytes.addAll(content.toList())
        }
        val source = MemorySource(bytes.toByteArray())
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4096).addPartitions(boot).build()
        return source to Payload(source.fileName(), PayloadHeader(2, 0, 0), manifest, 0, 4096, source.size)
    }

    private fun base(source: MemorySource) = ArchiveInfo(source.fileName(), source.size, "", 0, 4096)
        .copy(androidVersion = "15", securityPatchLevel = "2025-09-01")

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..1) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
    private class MemorySource(val bytes: ByteArray) : PayloadSource {
        var bytesRead = 0L
        override val size = bytes.size.toLong()
        override fun fileName() = "structured-kernel.bin"
        override fun close() = Unit
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            if (offset < 0 || len < 0 || offset > size || len > size - offset) throw EOFException()
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
            bytesRead += len
        }
    }
}
