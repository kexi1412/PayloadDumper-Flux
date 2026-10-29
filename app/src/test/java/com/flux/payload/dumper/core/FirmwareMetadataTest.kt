package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import com.google.protobuf.CodedOutputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic format fixtures, not production fallback values or device-derived metadata. */
class FirmwareMetadataTest {
    private val fingerprint = "test/product/device:15/TEST/123:user/release-keys"
    private val banner = "Linux version 6.1.118-test (builder@test) #1 SMP PREEMPT\u0000".toByteArray()

    @Test fun otaTextUsesOnlyPostcondition() {
        val fields = FirmwareMetadata.fromOtaText("""
            pre-build=test/product/device:12/OLD/1:user/release-keys
            post-build=$fingerprint
            post-sdk-level=35
            post-security-patch-level=2025-02-05
        """.trimIndent())
        assertEquals("15", fields.androidVersion)
        assertEquals(fingerprint, fields.fingerprint)
        assertEquals("2025-02-05", fields.securityPatchLevel)
        assertEquals("", fields.kernelVersion)
        assertEquals(FirmwareFields(), FirmwareMetadata.fromOtaText("pre-build=$fingerprint\npre-sdk-level=35"))
    }

    @Test fun missingAndInvalidValuesStayUnknown() {
        assertEquals("", FirmwareMetadata.releaseFromSdk("999"))
        assertEquals("", FirmwareMetadata.fromOtaText("post-security-patch-level=2025-02-31").securityPatchLevel)
        assertEquals("", FirmwareMetadata.fromOtaText("post-build=not-a-fingerprint").fingerprint)
        assertEquals("", FirmwareMetadata.fromOtaText("post-sdk-level=35\npost-sdk-level=36").androidVersion)
        assertEquals("16", FirmwareMetadata.fromOtaText("post-sdk-level=36").androidVersion)
    }

    @Test fun fingerprintReleaseIsNotBuildIdOrApiLevel() {
        assertEquals("15", FirmwareMetadata.releaseFromFingerprint(fingerprint))
        assertEquals("", FirmwareMetadata.releaseFromFingerprint("CrAU:2"))
        val differentRelease = fingerprint.replace(":15/", ":16/")
        assertEquals("", FirmwareMetadata.releaseFromFingerprint("$fingerprint|$differentRelease"))
    }

    @Test fun buildPropertiesComeFromFirmwareText() {
        val fields = FirmwareMetadata.fromBuildProperties("ro.build.version.release=15.0\nro.build.fingerprint=$fingerprint\nro.build.version.security_patch=2025-03-05")
        assertEquals("15.0", fields.androidVersion)
        assertEquals(fingerprint, fields.fingerprint)
        assertEquals("2025-03-05", fields.securityPatchLevel)
        assertEquals("", FirmwareMetadata.fromBuildProperties("ro.product.model=Test phone").androidVersion)
    }

    @Test fun protobufReadsTargetAndSkipsUnknownFields() {
        val post = proto { writeString(2, fingerprint); writeString(5, "35"); writeString(6, "2025-02-05"); writeString(90, "future") }
        val pre = proto { writeString(2, fingerprint.replace(":15/", ":12/")); writeString(5, "31") }
        val metadata = proto { writeByteArray(5, pre); writeByteArray(6, post); writeUInt32(100, 7) }
        val fields = FirmwareMetadata.fromOtaProto(metadata)
        assertEquals("15", fields.androidVersion)
        assertEquals("2025-02-05", fields.securityPatchLevel)
        assertEquals(fingerprint, fields.fingerprint)
        assertEquals(FirmwareFields(), FirmwareMetadata.fromOtaProto(proto { writeByteArray(5, pre) }))
    }

    @Test fun bootVersionsAndPatchPrecision() {
        for (version in 0..4) {
            val fields = FirmwareMetadata.fromBootImage(bootImage(version, banner, packedVersion = packed(15, 0, 0, 2025, 2)))
            assertEquals("version $version", "15.0.0", fields.androidVersion)
            assertEquals("2025-02", fields.securityPatchLevel)
            assertEquals("6.1.118-test", fields.kernelVersion)
        }
    }

    @Test fun gkiZeroOsVersionRemainsUnknown() {
        val fields = FirmwareMetadata.fromBootImage(bootImage(4, banner))
        assertEquals("", fields.androidVersion)
        assertEquals("", fields.securityPatchLevel)
        assertEquals("6.1.118-test", fields.kernelVersion)
    }

    @Test fun gzipAndLz4KernelFormats() {
        val gzip = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(banner) } }.toByteArray()
        val frame = ByteArrayOutputStream().also { output -> FramedLZ4CompressorOutputStream(output).use { it.write(banner) } }.toByteArray()
        val block = ByteArrayOutputStream().also { output -> BlockLZ4CompressorOutputStream(output).use { it.write(banner) } }.toByteArray()
        val legacy = ByteArray(8 + block.size).also {
            put32(it, 0, 0x184c2102); put32(it, 4, block.size); block.copyInto(it, 8)
        }
        for (kernel in listOf(gzip, frame, legacy)) {
            assertEquals("6.1.118-test", FirmwareMetadata.fromBootImage(bootImage(4, kernel)).kernelVersion)
        }
    }

    @Test fun invalidBannerBeforeValidBannerIsIgnored() {
        val invalid = "Linux version definitely-not-a-release (noise)\u0000".toByteArray()
        assertEquals("6.1.118-test", FirmwareMetadata.kernelBanner(invalid + banner))
    }

    @Test fun repeatedCopiesOfTheSameKernelReleaseAreAccepted() {
        assertEquals("6.1.118-test", FirmwareMetadata.kernelBanner(banner + ByteArray(64) + banner))
    }

    @Test fun conflictingValidKernelReleasesStayUnknown() {
        val other = "Linux version 6.6.66-other (builder@test) #2 SMP\u0000".toByteArray()
        assertEquals("", FirmwareMetadata.kernelBanner(banner + ByteArray(64) + other))
        assertEquals("", FirmwareMetadata.kernelBanner(other + ByteArray(64) + banner))
    }

    @Test fun ramdiskBannerDoesNotBecomeKernelVersion() {
        val image = bootImage(4, ByteArray(256)) + banner
        assertEquals("", FirmwareMetadata.fromBootImage(image).kernelVersion)
        assertEquals(FirmwareFields(), FirmwareMetadata.fromBootImage(banner))
        assertNull(FirmwareMetadata.bootHeader(bootImage(99, banner)))
    }

    @Test fun deflateLengthAndTruncationAreChecked() {
        val text = "post-sdk-level=35\n".toByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val compressed = try {
            deflater.setInput(text); deflater.finish()
            ByteArray(200).let { it.copyOf(deflater.deflate(it)) }
        } finally { deflater.end() }
        assertArrayEquals(text, FirmwareMetadataReader.inflateExactly(compressed, text.size))
        assertThrows(IOException::class.java) { FirmwareMetadataReader.inflateExactly(compressed, text.size - 1) }
        assertThrows(IOException::class.java) { FirmwareMetadataReader.inflateExactly(compressed.copyOf(compressed.size - 1), text.size) }
    }

    @Test fun storedAndDataDescriptorDeflateMetadataAreVerified() {
        for (stored in listOf(true, false)) {
            val source = MemorySource(zip("META-INF/com/android/metadata", "post-build=$fingerprint\npost-security-patch-level=2025-02-05".toByteArray(), stored))
            val result = FirmwareMetadataReader.read(source, base(source))
            assertEquals("15", result.androidVersion)
            assertEquals(fingerprint, result.fingerprint)
            assertEquals(source.size, result.fileSize)
            val entry = ZipScanner.scanEntries(source).single()
            assertThrows(IOException::class.java) {
                FirmwareMetadataReader.readZipEntry(source, entry.copy(crc32 = entry.crc32!! xor 1), 1024)
            }
        }
    }

    @Test fun protobufOnlyArchiveWorksAndMalformedMetadataIsNonfatal() {
        val pb = proto { writeByteArray(6, proto { writeString(2, fingerprint); writeString(6, "2025-02-05") }) }
        val good = MemorySource(zip("META-INF/com/android/metadata.pb", pb, false))
        assertEquals(fingerprint, FirmwareMetadataReader.read(good, base(good)).fingerprint)
        val bad = MemorySource(zip("META-INF/com/android/metadata.pb", byteArrayOf(0x32, 0x7f), false))
        assertEquals("", FirmwareMetadataReader.read(bad, base(bad)).fingerprint)
    }

    @Test fun oversizedOrOutOfRangeEntriesDoNotAllocateOrRead() {
        val source = MemorySource(byteArrayOf(1, 2, 3, 4))
        val huge = ZipEntryInfo("metadata", Long.MAX_VALUE, Long.MAX_VALUE, 0, 0)
        assertThrows(IOException::class.java) { FirmwareMetadataReader.readZipEntry(source, huge, 1024) }
        assertEquals(0, source.bytesRead)
        assertThrows(IOException::class.java) {
            FirmwareMetadataReader.readZipEntry(source, huge.copy(uncompressedSize = 1, compressedSize = 1, localHeaderOffset = Long.MAX_VALUE), 1024)
        }
        assertEquals(0, source.bytesRead)
    }

    @Test fun manifestPatchDoesNotInventAndroidOrFingerprint() {
        val source = MemorySource("CrAU-test".toByteArray())
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setSecurityPatchLevel("2025-03-05").build()
        val result = FirmwareMetadataReader.read(source, base(source), payload(source, manifest))
        assertEquals("2025-03-05", result.securityPatchLevel)
        assertEquals("", result.androidVersion)
        assertEquals("", result.fingerprint)
        assertEquals("", result.kernelVersion)
    }

    @Test fun multipleDestinationExtentsAreScatteredNotConcatenated() {
        val image = bootImage(4, banner).copyOf(8192)
        // Stored operation order is deliberately opposite to image order.
        val operationBytes = image.copyOfRange(4096, 8192) + image.copyOfRange(0, 4096)
        val source = MemorySource(operationBytes)
        val operation = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE).setDataOffset(0).setDataLength(8192)
            .addDstExtents(extent(1)).addDstExtents(extent(0)).build()
        val result = FirmwareMetadataReader.read(source, base(source), bootPayload(source, operation))
        assertEquals("6.1.118-test", result.kernelVersion)
    }

    @Test fun deltaOrIncompleteBootDoesNotBecomeZeroFilledKernel() {
        val source = MemorySource(bootImage(4, banner).copyOf(8192))
        val replaceHeaderOnly = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE).setDataOffset(0).setDataLength(4096)
            .addDstExtents(extent(0)).build()
        val delta = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.SOURCE_COPY).addSrcExtents(extent(0)).addDstExtents(extent(0)).build()
        for (operation in listOf(replaceHeaderOnly, delta)) {
            assertEquals("", FirmwareMetadataReader.read(source, base(source), bootPayload(source, operation)).kernelVersion)
        }
    }

    @Test fun cancellationIsNotConvertedToUnknown() {
        assertThrows(CancellationException::class.java) { FirmwareMetadata.optional<Unit> { throw CancellationException("test") } }
        assertNull(FirmwareMetadata.optional<Unit> { throw IOException("unavailable") })
    }

    private fun extent(start: Long) = UpdateMetadata.Extent.newBuilder().setStartBlock(start).setNumBlocks(1).build()

    private fun bootPayload(source: MemorySource, operation: UpdateMetadata.InstallOperation): Payload {
        val partition = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(8192)).addOperations(operation).build()
        return payload(source, UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4096).addPartitions(partition).build())
    }

    private fun payload(source: MemorySource, manifest: UpdateMetadata.DeltaArchiveManifest) =
        Payload(source.fileName(), PayloadHeader(2, 0, 0), manifest, 0, 4096, source.size)

    private fun base(source: MemorySource) = ArchiveInfo(source.fileName(), source.size, "", 0, 4096)

    private fun proto(block: CodedOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        CodedOutputStream.newInstance(output).apply { block(); flush() }
        return output.toByteArray()
    }

    private fun zip(name: String, bytes: ByteArray, stored: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun bootImage(version: Int, kernel: ByteArray, packedVersion: Int = 0): ByteArray {
        val page = if (version >= 3) 4096 else 2048
        return ByteArray(page + kernel.size).also {
            "ANDROID!".toByteArray().copyInto(it)
            put32(it, 8, kernel.size); put32(it, 40, version)
            if (version < 3) put32(it, 36, page)
            put32(it, if (version >= 3) 16 else 44, packedVersion)
            kernel.copyInto(it, page)
        }
    }

    private fun packed(a: Int, b: Int, c: Int, year: Int, month: Int) =
        (a shl 25) or (b shl 18) or (c shl 11) or ((year - 2000) shl 4) or month

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private class MemorySource(private val bytes: ByteArray) : PayloadSource {
        var bytesRead: Int = 0
        override val size: Long get() = bytes.size.toLong()
        override fun fileName() = "fixture.zip"
        override fun close() = Unit
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            if (offset < 0 || len < 0 || offset > size || len > size - offset) throw EOFException()
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
            bytesRead += len
        }
    }
}
