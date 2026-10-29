package com.flux.payload.dumper.core

import java.io.IOException

/**
 * Locates `payload.bin` inside an OTA `.zip` without decompressing anything.
 *
 * OTA zips store `payload.bin` uncompressed (STORED), so once we know the offset of its local
 * file data we can seek straight into the CrAU stream. Reads go through [PayloadSource] so the
 * exact same code path works for both local files and remote URLs.
 *
 * The central-directory walk is delegated to [ZipScanner], which already handles ZIP64 EOCD
 * records, the ZIP64 locator, a central directory beyond 4 GiB, a non-empty ZIP comment and
 * ZIP64 extended local-header offsets. Duplicating that parser here previously introduced an
 * off-by-four locator read that made every ZIP64 OTA fail before the payload was ever opened.
 */
object ZipLocator {

    private const val PAYLOAD_NAME = "payload.bin"

    /** Absolute offset where the CrAU stream begins, or throws if it can't be located. */
    fun findPayloadOffset(source: PayloadSource, prefix: ByteArray? = null): Long {
        // Signed/CDN links often have no .bin suffix; filenames cannot identify the format.
        val magic = prefix ?: source.readFully(0, minOf(4L, source.size).toInt())
        if (magic.size >= 4 && magic[0] == 'C'.code.toByte() && magic[1] == 'r'.code.toByte() &&
            magic[2] == 'A'.code.toByte() && magic[3] == 'U'.code.toByte()) return 0L

        val entry = ZipScanner.scanEntries(source).firstOrNull { it.name == PAYLOAD_NAME }
            ?: throw IOException("payload.bin not found inside the zip")
        return ZipScanner.dataOffsetForEntry(source, entry)
    }
}
