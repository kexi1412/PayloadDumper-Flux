package com.flux.payload.dumper.core

import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.Payload
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Best-effort enrichment, shared by local and HTTP PayloadSource implementations.
 * Only small metadata entries and the boot kernel are read. Missing, delta-only,
 * oversized or unsupported fields remain empty; archive extraction stays available.
 */
object FirmwareMetadataReader {
    private const val METADATA = "META-INF/com/android/metadata"
    private const val METADATA_PB = "META-INF/com/android/metadata.pb"
    private const val MAX_BOOT_BYTES = FirmwareMetadata.MAX_KERNEL_BYTES
    private const val MAX_OP_BYTES = 16 * 1024 * 1024
    private const val BOOT_FETCH_BUDGET = 64L * 1024 * 1024
    private val BUILD_PROPERTIES = listOf("system/system/build.prop", "system/build.prop", "SYSTEM/build.prop", "build.prop")

    fun read(source: PayloadSource, base: ArchiveInfo, payload: Payload? = null): ArchiveInfo {
        val manifestFields = FirmwareFields(securityPatchLevel = FirmwareMetadata.cleanPatch(
            if (payload?.manifest?.hasSecurityPatchLevel() == true) payload.manifest.securityPatchLevel else base.securityPatchLevel
        ))
        val entries = FirmwareMetadata.optional {
            if (source.size >= 4 && source.readFully(0, 4).contentEquals(byteArrayOf(0x50, 0x4b, 3, 4)))
                ZipScanner.scanEntries(source) else emptyList()
        }.orEmpty()
        var fields = FirmwareFields()
        fun uniqueEntry(name: String) = entries.filter { it.name == name }.singleOrNull()
        uniqueEntry(METADATA_PB)?.let { entry ->
            FirmwareMetadata.optional {
                FirmwareMetadata.fromOtaProto(readZipEntry(source, entry, FirmwareMetadata.MAX_TEXT_BYTES))
            }?.let { fields = fields.fallback(it) }
        }
        uniqueEntry(METADATA)?.let { entry ->
            FirmwareMetadata.optional {
                FirmwareMetadata.fromOtaText(String(readZipEntry(source, entry, FirmwareMetadata.MAX_TEXT_BYTES), Charsets.UTF_8))
            }?.let { fields = fields.fallback(it) }
        }
        BUILD_PROPERTIES.forEach { name ->
            uniqueEntry(name)?.let { entry ->
                FirmwareMetadata.optional {
                    FirmwareMetadata.fromBuildProperties(String(readZipEntry(source, entry, FirmwareMetadata.MAX_TEXT_BYTES), Charsets.UTF_8))
                }?.let { fields = fields.fallback(it) }
            }
        }
        fields = fields.fallback(manifestFields)

        // A second, strictly bounded stage. No system/vendor filesystem is downloaded.
        val boot = FirmwareMetadata.optional {
            if (payload != null) {
                val payloadEntry = entries.filter { it.name.equals("payload.bin", true) }.singleOrNull()
                val end = if (payloadEntry == null) source.size else {
                    if (payloadEntry.method != 0) return@optional null
                    val start = ZipScanner.dataOffsetForEntry(source, payloadEntry)
                    checkedEnd(start, payloadEntry.uncompressedSize, source.size)
                }
                readPayloadBoot(source, payload, end)
            } else {
                // Do not confuse vendor_boot/init_boot (ramdisks) or recovery kernels with boot.
                val entry = entries.filter { it.name.substringAfterLast('/').lowercase(Locale.ROOT) == "boot.img" }.singleOrNull()
                entry?.let { readZipEntry(source, it, MAX_BOOT_BYTES) }
            }
        }
        if (boot != null) {
            FirmwareMetadata.optional { FirmwareMetadata.fromBootImage(boot) }?.let { fields = fields.fallback(it) }
        }
        return base.copy(
            androidVersion = fields.androidVersion,
            securityPatchLevel = fields.securityPatchLevel,
            kernelVersion = fields.kernelVersion,
            fingerprint = fields.fingerprint,
        )
    }

    /** ZIP STORED and raw DEFLATE, with exact length, CRC, flags and range validation. */
    internal fun readZipEntry(source: PayloadSource, entry: ZipEntryInfo, limit: Int): ByteArray {
        require(limit >= 0)
        if (entry.flags and 1 != 0 || entry.method !in listOf(0, 8)) throw IOException("Unsupported metadata ZIP entry")
        if (entry.uncompressedSize !in 0L..limit.toLong() || entry.compressedSize !in 0L..limit.toLong())
            throw IOException("ZIP metadata exceeds byte budget")
        checkedEnd(entry.localHeaderOffset, 30, source.size)
        val local = source.readFully(entry.localHeaderOffset, 30)
        if (FirmwareMetadata.u32(local, 0) != 0x04034b50L) throw IOException("Invalid ZIP local header")
        val flags = u16(local, 6)
        if (flags and 1 != 0 || u16(local, 8) != entry.method) throw IOException("Mismatched ZIP header")
        val nameSize = u16(local, 26)
        val extraSize = u16(local, 28)
        val dataStart = checkedEnd(entry.localHeaderOffset, 30L + nameSize + extraSize, source.size)
        val localName = source.readFully(entry.localHeaderOffset + 30, nameSize).toString(Charsets.UTF_8)
        if (localName != entry.name) throw IOException("Mismatched ZIP entry name")
        checkedEnd(dataStart, entry.compressedSize, source.size)
        val compressed = source.readFully(dataStart, entry.compressedSize.toInt())
        val bytes = when (entry.method) {
            0 -> {
                if (entry.compressedSize != entry.uncompressedSize) throw IOException("STORED ZIP length mismatch")
                compressed
            }
            else -> inflateExactly(compressed, entry.uncompressedSize.toInt())
        }
        val expectedCrc = entry.crc32 ?: if (flags and 8 == 0) FirmwareMetadata.u32(local, 14)
            else throw IOException("ZIP data-descriptor entry lacks central-directory CRC")
        val crc = CRC32().apply { update(bytes) }.value
        if (crc != expectedCrc) throw IOException("ZIP metadata CRC mismatch")
        return bytes
    }

    internal fun inflateExactly(compressed: ByteArray, expectedSize: Int): ByteArray {
        require(expectedSize in 0..MAX_BOOT_BYTES)
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedSize + 1)
            var offset = 0
            while (!inflater.finished()) {
                FirmwareMetadata.checkCancelled()
                val count = inflater.inflate(output, offset, output.size - offset)
                offset += count
                if (offset > expectedSize) throw IOException("ZIP metadata expands beyond advertised size")
                if (count == 0 && !inflater.finished()) throw IOException("Truncated or invalid ZIP DEFLATE stream")
            }
            if (offset != expectedSize || inflater.remaining != 0) throw IOException("ZIP DEFLATE length mismatch")
            return output.copyOf(expectedSize)
        } finally {
            inflater.end()
        }
    }

    private class Budget(var remaining: Long = BOOT_FETCH_BUDGET) {
        fun consume(bytes: Long) {
            if (bytes < 0 || bytes > remaining) throw IOException("Boot metadata read budget exceeded")
            remaining -= bytes
        }
    }

    private fun readPayloadBoot(source: PayloadSource, payload: Payload, payloadEnd: Long): ByteArray? {
        val boot = payload.manifest.partitionsList.filter { it.partitionName == "boot" }.singleOrNull() ?: return null
        if (!boot.hasNewPartitionInfo() || !boot.newPartitionInfo.hasSize()) return null
        val partitionSize = boot.newPartitionInfo.size
        if (partitionSize < 48 || payload.blockSize <= 0) return null
        val budget = Budget()
        val prefix = reconstructPrefix(source, payload, payloadEnd, boot, minOf(4096L, partitionSize).toInt(), budget) ?: return null
        val header = FirmwareMetadata.bootHeader(prefix) ?: return null
        val length = header.kernelOffset.toLong() + header.kernelSize
        if (length > partitionSize || length > MAX_BOOT_BYTES) return null
        return reconstructPrefix(source, payload, payloadEnd, boot, length.toInt(), budget)
    }

    /**
     * Reconstruct only a required boot prefix, scattering decoded bytes through EVERY
     * dst_extent. Coverage must be complete; source-dependent or unknown operations
     * never become zero-filled fake firmware. Non-intersecting tail operations are skipped.
     */
    private fun reconstructPrefix(
        source: PayloadSource,
        payload: Payload,
        payloadEnd: Long,
        partition: chromeos_update_engine.UpdateMetadata.PartitionUpdate,
        length: Int,
        budget: Budget,
    ): ByteArray? {
        if (length <= 0 || length > MAX_BOOT_BYTES) return null
        val output = ByteArray(length)
        val covered = mutableListOf<Pair<Int, Int>>()
        for (operation in partition.operationsList) {
            FirmwareMetadata.checkCancelled()
            val extents = operation.dstExtentsList.map { extent ->
                val start = multiply(extent.startBlock, payload.blockSize)
                val size = multiply(extent.numBlocks, payload.blockSize)
                checkedEnd(start, size, partition.newPartitionInfo.size)
                start to size
            }
            if (extents.none { (start, size) -> size > 0 && start < length }) continue
            val expected = extents.fold(0L) { sum, (_, size) -> checkedEnd(sum, size, MAX_OP_BYTES.toLong()) }.toInt()
            if (expected <= 0 || operation.srcExtentsCount != 0) return null
            val kind = operation.type.name
            if (kind !in setOf("REPLACE", "REPLACE_XZ", "REPLACE_BZ", "REPLACE_ZSTD", "ZERO")) return null
            val decoded = if (kind == "ZERO") ByteArray(expected) else {
                if (!operation.hasDataOffset() || !operation.hasDataLength() || operation.dataLength !in 1L..MAX_OP_BYTES.toLong()) return null
                val start = checkedEnd(payload.dataOffset, operation.dataOffset, payloadEnd)
                checkedEnd(start, operation.dataLength, payloadEnd)
                budget.consume(operation.dataLength)
                val bytes = source.readFully(start, operation.dataLength.toInt())
                if (!operation.dataSha256Hash.isEmpty && !MessageDigest.getInstance("SHA-256").digest(bytes)
                        .contentEquals(operation.dataSha256Hash.toByteArray())) return null
                when (kind) {
                    "REPLACE" -> bytes
                    "REPLACE_XZ" -> XZInputStream(ByteArrayInputStream(bytes), 64 * 1024).use { FirmwareMetadata.boundedRead(it, expected) }
                    "REPLACE_BZ" -> BZip2CompressorInputStream(ByteArrayInputStream(bytes)).use { FirmwareMetadata.boundedRead(it, expected) }
                    "REPLACE_ZSTD" -> Decompressor.zstd(bytes, expected)
                    else -> return null
                }
            }
            if (decoded.size != expected) return null
            var cursor = 0
            for ((start, size) in extents) {
                if (size > 0 && start < length) {
                    val count = minOf(size, length.toLong() - start).toInt()
                    decoded.copyInto(output, start.toInt(), cursor, cursor + count)
                    covered.add(start.toInt() to (start.toInt() + count))
                }
                cursor += size.toInt()
            }
        }
        var end = 0
        for ((start, stop) in covered.sortedBy { it.first }) {
            if (start != end) return null // gap OR overlapping writes is not a proven target image
            end = stop
        }
        return output.takeIf { end == length }
    }

    private fun multiply(blocks: Long, blockSize: Int): Long {
        if (blocks < 0 || blockSize <= 0 || blocks > Long.MAX_VALUE / blockSize) throw IOException("Invalid payload extent")
        return blocks * blockSize
    }

    private fun checkedEnd(start: Long, size: Long, limit: Long): Long {
        if (start < 0 || size < 0 || start > limit || size > limit - start) throw IOException("Metadata range outside source")
        return start + size
    }

    private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
}
