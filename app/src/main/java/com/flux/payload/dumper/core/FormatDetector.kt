package com.flux.payload.dumper.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Supported OTA archive formats.
 */
sealed interface ArchiveFormat {
    /** Google A/B OTA — contains a STORED payload.bin with a CrAU header. */
    data object CrAUPayload : ArchiveFormat

    /** Honor/Huawei SD-card update package — nested ZIPs possibly containing UPDATE.APP. */
    data class HonorSdUpdate(
        val entries: List<ZipEntryInfo>,
    ) : ArchiveFormat

    /** Raw UPDATE.APP (Huawei firmware container). */
    data object UpdateApp : ArchiveFormat

    /** Traditional block-based OTA (system.new.dat + transfer.list). */
    data object BlockOta : ArchiveFormat

    /** Unknown / unsupported format. */
    data object Unknown : ArchiveFormat
}

/** Minimal info about one ZIP central-directory entry. */
data class ZipEntryInfo(
    val name: String,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val method: Int,          // 0=STORED, 8=DEFLATED
    val localHeaderOffset: Long,
    val crc32: Long? = null,
    val flags: Int = 0,
)

/**
 * Auto-detects the archive format of a [PayloadSource].
 *
 * Strategy:
 *  1. If the raw bytes start with "CrAU" → CrAUPayload
 *  2. If the raw bytes start with PK\x03\x04 → scan central directory for all entries
 *     a. If "payload.bin" exists → CrAUPayload (existing path)
 *     b. If "UPDATE.APP" or "update.app" exists → UpdateApp inside ZIP
 *     c. If "Software/dload/" or "*.new.dat" entries exist → HonorSdUpdate / BlockOta
 *  3. If bytes start with 0x55AA55AA → raw UPDATE.APP
 *  4. Else → Unknown
 */
object FormatDetector {

    private const val PK_LOCAL = 0x04034b50L
    private const val PK_CENTRAL = 0x02014b50L
    private const val PK_END = 0x06054b50L
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP64_LOC = 0x07064b50L
    private const val UPDATE_APP_MAGIC = 0x55AA55AAL

    fun detect(source: PayloadSource): ArchiveFormat {
        if (source.size < 8) return ArchiveFormat.Unknown

        // Read first 4 bytes to check magic
        val head = source.readFully(0, 4)
        val magic = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        // CrAU?
        if (head[0] == 'C'.code.toByte() && head[1] == 'r'.code.toByte() &&
            head[2] == 'A'.code.toByte() && head[3] == 'U'.code.toByte()) {
            return ArchiveFormat.CrAUPayload
        }

        // Raw UPDATE.APP?
        if (magic == UPDATE_APP_MAGIC) {
            return ArchiveFormat.UpdateApp
        }

        // ZIP?
        if (magic == PK_LOCAL) {
            return detectZip(source)
        }

        // Could be a .bin that's actually payload inside a zip with weird naming
        if (source.fileName().endsWith(".bin", true) || source.fileName().endsWith(".zip", true)) {
            return detectZip(source)
        }

        return ArchiveFormat.Unknown
    }

    private fun detectZip(source: PayloadSource): ArchiveFormat {
        val entries = try {
            ZipScanner.scanEntries(source)
        } catch (e: Throwable) {
            // Do NOT swallow this. Returning Unknown here is what turned every distinct
            // failure — a short read, an out-of-range central directory, a measured EOCD
            // offset that disagrees with the file — into the same generic
            // "Unrecognised archive" text, with the real cause discarded. Rethrow it
            // annotated so the error card can show what actually went wrong.
            throw IOException(
                "ZIP central directory unreadable in '${source.fileName()}' " +
                    "(${source.size} bytes): ${Diagnostics.describe(e)}",
                e,
            )
        }

        // Check for payload.bin first
        if (entries.any { it.name.equals("payload.bin", true) }) {
            return ArchiveFormat.CrAUPayload
        }

        // Check for UPDATE.APP
        if (entries.any { it.name.contains("UPDATE.APP", true) || it.name.contains("update.app", true) }) {
            return ArchiveFormat.HonorSdUpdate(entries)
        }

        // Check for Honor SD update structure
        if (entries.any { it.name.contains("Software/dload/", true) }) {
            return ArchiveFormat.HonorSdUpdate(entries)
        }

        // Check for traditional block OTA
        if (entries.any { it.name.endsWith(".new.dat", true) || it.name.endsWith(".transfer.list", true) }) {
            return ArchiveFormat.BlockOta
        }

        // Has any .img files?
        if (entries.any { it.name.endsWith(".img", true) }) {
            return ArchiveFormat.HonorSdUpdate(entries)
        }

        return ArchiveFormat.HonorSdUpdate(entries)
    }
}
