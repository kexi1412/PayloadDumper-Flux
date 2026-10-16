package com.flux.payload.dumper.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses Huawei/Honor UPDATE.APP firmware containers.
 *
 * UPDATE.APP is a proprietary container format used by Huawei and Honor (post-separation)
 * devices. It stores multiple partition images (boot.img, system.img, recovery.img, etc.)
 * in a sequence of entries, each preceded by a fixed-size header.
 *
 * Header format (all little-endian):
 *   Offset  Size  Field
 *   0       4     Magic: 0x55AA55AA
 *   4       4     Header size (typically 0x00000200 = 512 bytes)
 *   8       4     ??? (unknown, often 0)
 *   12      4     File/hardware ID
 *   16      4     ??? (unknown)
 *   20      4     Sequence number / entry count info
 *   24      4     Data size (payload bytes following the header)
 *   28      4     ??? (padding/alignment)
 *   32      64    SHA-1 or SHA-256 checksum of the data (varies by version)
 *   96-512       Padding to header_size
 *
 * After the header, [dataSize] bytes of raw partition image data follow,
 * then the next entry begins (aligned to the header size boundary).
 *
 * There are several format variations:
 *  - Older Huawei: 512-byte headers, SHA-1 checksums
 *  - Newer Honor: Larger headers, SHA-256, additional metadata
 *  - Some entries are "padding" entries with size 0 used for alignment
 */
object UpdateAppParser {

    private const val MAGIC = 0x55AA55AAL
    private const val DEFAULT_HEADER_SIZE = 512

    /** One entry in the UPDATE.APP container. */
    data class UpdateAppEntry(
        val filename: String,
        val offset: Long,        // absolute offset of the data in the source
        val size: Long,          // data size in bytes
        val hardwareId: Int,
        val sequence: Int,
        val hash: ByteArray,     // checksum bytes (length varies)
        val rawHeaderSize: Int,
    )

    /**
     * Parse all entries from an UPDATE.APP stream.
     * @param source random-access source positioned at the start of UPDATE.APP data
     * @param baseOffset absolute offset where UPDATE.APP begins (usually from [ZipScanner.dataOffsetForEntry])
     */
    fun parse(source: PayloadSource, baseOffset: Long): List<UpdateAppEntry> {
        val entries = mutableListOf<UpdateAppEntry>()
        var pos = baseOffset
        var entryIndex = 0

        while (pos + DEFAULT_HEADER_SIZE <= source.size) {
            val header = source.readFully(pos, DEFAULT_HEADER_SIZE)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val magic = bb.int.toLong() and 0xFFFFFFFFL
            if (magic != MAGIC) {
                // Not an entry — might be padding or end of container
                // Try skipping ahead to find the next magic
                val nextMagic = findNextMagic(source, pos + 4) ?: break
                pos = nextMagic
                continue
            }

            bb.position(4)
            val headerSize = bb.int
            if (headerSize <= 0 || headerSize > 65536) {
                // Skip suspicious header
                pos += DEFAULT_HEADER_SIZE
                continue
            }

            bb.position(12)
            val hwId = bb.int
            bb.position(16)
            // Skip unknown field
            bb.position(20)
            val sequence = bb.int
            bb.position(24)
            val dataSize = bb.int.toLong() and 0xFFFFFFFFL

            // Extended size for large partitions (>4GB) — check if high bits are in extra
            bb.position(28)
            val dataSizeHigh = bb.int.toLong() and 0xFFFFFFFFL
            val fullDataSize = dataSize or (dataSizeHigh shl 32)

            // Read checksum (offset 32, up to 64 bytes)
            bb.position(32)
            val hashBytes = ByteArray(32)
            bb.get(hashBytes)

            // Try to extract a filename — some versions embed it in the header padding area
            // (typically at offset 96+ in the raw header)
            val filename = extractFilename(header, entryIndex)

            val dataOffset = pos + headerSize

            if (fullDataSize > 0) {
                entries.add(UpdateAppEntry(
                    filename = filename,
                    offset = dataOffset,
                    size = fullDataSize,
                    hardwareId = hwId,
                    sequence = sequence,
                    hash = hashBytes,
                    rawHeaderSize = headerSize,
                ))
            }

            // Advance to next entry
            pos = dataOffset + fullDataSize
            // Align to header size boundary
            if (pos % headerSize != 0L) {
                pos = (pos / headerSize + 1) * headerSize
            }
            entryIndex++
        }

        return entries
    }

    /** Search for the next 0x55AA55AA magic from [from], returning its absolute offset. */
    private fun findNextMagic(source: PayloadSource, from: Long): Long? {
        val scanChunk = 65536
        var pos = from
        while (pos + 4 <= source.size) {
            val readLen = minOf(scanChunk.toLong(), source.size - pos).toInt()
            val chunk = source.readFully(pos, readLen)
            for (i in 0 until chunk.size - 3) {
                if (chunk[i] == 0x55.toByte() && chunk[i + 1] == 0xAA.toByte() &&
                    chunk[i + 2] == 0x55.toByte() && chunk[i + 3] == 0xAA.toByte()
                ) {
                    return pos + i
                }
            }
            pos += chunk.size - 3
        }
        return null
    }

    /**
     * Attempt to extract a filename from the UPDATE.APP header.
     *
     * Some Honor/Huawei UPDATE.APP versions embed the partition name as a null-terminated
     * UTF-8 string in the header's padding area (after offset 96). Others don't include it
     * at all — in that case we generate a name like "entry_N".
     */
    private fun extractFilename(header: ByteArray, index: Int): String {
        // Scan from offset 96 to end of header for a printable string
        val sb = StringBuilder()
        var started = false
        for (i in 96 until header.size) {
            val b = header[i]
            if (b >= 0x20 && b < 0x7F) {
                sb.append(b.toInt().toChar())
                started = true
            } else if (started && b == 0.toByte()) {
                break
            } else if (started) {
                break
            }
        }

        val name = sb.toString().trim()
        // Filter out garbage — valid partition names are alphanumeric with underscores
        return if (name.length in 2..64 && name.matches(Regex("[a-zA-Z0-9_./-]+"))) {
            name
        } else {
            "entry_$index"
        }
    }

    /**
     * Partition-name heuristics: map UPDATE.APP entries to common Android partition names
     * based on their sequence number and size.
     */
    private val PARTITION_ORDER = listOf(
        "boot", "xz_boot", "recovery", "recovery2", "fastboot",
        "dtbo", "teeos", "trustfirmware", "vbmeta", "vbmeta_dr",
        "modem", "dsp", "lpfilter", "wlandsp", "modem_dt", "modem_om",
        "sbl", "sbl1", "sbl2", "sbl3", "aboot", "rpm", "tz", "hyp",
        "devcfg", "cmnlib", "cmnlib64", "keymaster", "keycert",
        "system", "vendor", "product", "odm", "odm_vendor",
        "userdata", "cache", "persist", "misc", "metadata",
        "super", "xpm", "oppo", "reserve", "modem_secure",
    )

    fun guessPartitionName(index: Int, size: Long, totalEntries: Int): String {
        if (index < PARTITION_ORDER.size) return PARTITION_ORDER[index]
        return "partition_$index"
    }
}
