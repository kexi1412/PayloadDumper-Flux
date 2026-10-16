package com.flux.payload.dumper.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scans a ZIP file's central directory to enumerate **all** entries, not just payload.bin.
 *
 * This is the foundation for multi-format support: Honor SD update packages contain nested
 * ZIPs, UPDATE.APP files, *.img files, and various other entries that we need to discover
 * and present to the user.
 *
 * Strategy:
 *  1. Read the last [TAIL] bytes of the file.
 *  2. Find the End of Central Directory (EOCD) record — supports ZIP64.
 *  3. Read the entire central directory.
 *  4. Parse each central directory entry into [ZipEntryInfo].
 *
 * Each entry's [localHeaderOffset] can later be used to compute the absolute data offset
 * for extraction (local header size + filename + extra field).
 */
object ZipScanner {

    private const val TAG = "ZipScanner"
    private const val TAIL = 65536
    private const val PK_END = 0x06054b50L
    private const val PK_CENTRAL = 0x02014b50L
    private const val PK_LOCAL = 0x04034b50L
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP64_LOC = 0x07064b50L
    private const val ZIP64_MAGIC = 0xFFFFFFFFL
    private const val ENDHDR = 22
    private const val CENHDR = 46
    private const val LOCHDR = 30

    /**
     * Enumerate all entries in the ZIP file backing [source].
     * @throws IOException if the central directory cannot be located or parsed.
     */
    fun scanEntries(source: PayloadSource): List<ZipEntryInfo> {
        val (cdOffset, cdSize) = locateCentralDirectory(source)
        if (cdOffset < 0 || cdSize <= 0) {
            throw IOException("Central directory not found")
        }

        val cd = source.readFully(cdOffset, cdSize.toInt())
        return parseCentralDirectory(cd)
    }

    /**
     * Find the absolute data offset of a ZIP entry by reading its local file header.
     * @param source the backing source
     * @param entry the entry from [scanEntries]
     * @return absolute offset where compressed/stored data begins
     */
    fun dataOffsetForEntry(source: PayloadSource, entry: ZipEntryInfo): Long {
        val localHeader = source.readFully(entry.localHeaderOffset, LOCHDR + 512)
        val bb = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.int.toLong() and 0xFFFFFFFFL
        if (sig != PK_LOCAL) throw IOException("Bad local header at ${entry.localHeaderOffset}")
        bb.position(26) // skip to name_len
        val nameLen = bb.short.toInt() and 0xFFFF
        val extraLen = bb.short.toInt() and 0xFFFF
        return entry.localHeaderOffset + LOCHDR + nameLen + extraLen
    }

    private data class CDRegion(val offset: Long, val size: Long)

    private fun locateCentralDirectory(source: PayloadSource): CDRegion {
        val tailLen = minOf(TAIL.toLong(), source.size).toInt()
        val tail = source.readFully(source.size - tailLen, tailLen)
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        // Scan backwards for EOCD signature
        for (i in 0..(bb.capacity() - ENDHDR)) {
            val pos = bb.capacity() - ENDHDR - i
            if (pos < 0) break
            bb.position(pos)
            if (bb.int.toLong() and 0xFFFFFFFFL != PK_END) continue

            // Found EOCD
            val endPos = bb.position() // just after signature
            bb.position(endPos + 4) // skip disk number
            bb.position(endPos + 8)
            val cenCountHere = bb.short.toInt() and 0xFFFF
            val cenCountTotal = bb.short.toInt() and 0xFFFF
            bb.position(endPos + 12)
            val cenSizeHere = bb.int.toLong() and 0xFFFFFFFFL
            val cenOffset = bb.int.toLong() and 0xFFFFFFFFL

            // ZIP64?
            if (cenCountTotal == ZIP64_MAGIC || cenOffset == ZIP64_MAGIC || cenSizeHere == ZIP64_MAGIC) {
                return locateZip64CentralDirectory(source, tail, pos, bb.capacity())
            }

            val cdSize = if (cenSizeHere != ZIP64_MAGIC) cenSizeHere else 0L
            val realCount = if (cenCountTotal != ZIP64_MAGIC) cenCountTotal else cenCountHere
            val realOffset = if (cenOffset != ZIP64_MAGIC) cenOffset else 0L
            val realSize = if (cdSize > 0) cdSize else source.size - realOffset - (tailLen - pos - ENDHDR)
            return CDRegion(realOffset, realSize)
        }

        throw IOException("EOCD record not found")
    }

    private fun locateZip64CentralDirectory(
        source: PayloadSource,
        tail: ByteArray,
        eocdPos: Int,
        tailLen: Int,
    ): CDRegion {
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        // Search for ZIP64 EOCD locator (PK\x06\x07) before the regular EOCD
        for (i in 0..(bb.capacity() - 20)) {
            val pos = eocdPos - 20 - i
            if (pos < 0) break
            bb.position(pos)
            if (bb.int.toLong() and 0xFFFFFFFFL != ZIP64_LOC) continue

            // Found ZIP64 EOCD Locator
            bb.position(pos + 4)
            // disk number (4), then offset to ZIP64 EOCD (8)
            bb.position(pos + 8)
            val zip64EndOffset = bb.long

            // Read ZIP64 EOCD record
            val zip64End = source.readFully(zip64EndOffset, 56)
            val zbb = ByteBuffer.wrap(zip64End).order(ByteOrder.LITTLE_ENDIAN)
            val sig = zbb.int.toLong() and 0xFFFFFFFFL
            if (sig != ZIP64_END) throw IOException("Bad ZIP64 EOCD")

            zbb.position(40) // skip to size of central directory
            val cdSize = zbb.long
            val cdOffset = zbb.long
            return CDRegion(cdOffset, cdSize)
        }

        throw IOException("ZIP64 EOCD locator not found")
    }

    private fun parseCentralDirectory(cd: ByteArray): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        val bb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

        while (bb.remaining() >= CENHDR) {
            val sig = bb.int.toLong() and 0xFFFFFFFFL
            if (sig != PK_CENTRAL) break

            // Central directory entry layout (46-byte fixed header):
            // 0  sig(4)
            // 4  version_made(2)
            // 6  version_needed(2)
            // 8  gp_flags(2)
            // 10 method(2)
            // 12 mod_time(2)
            // 14 mod_date(2)
            // 16 crc32(4)
            // 20 comp_size(4)
            // 24 uncomp_size(4)
            // 28 name_len(2)
            // 30 extra_len(2)
            // 32 comment_len(2)
            // 34 disk_start(2)
            // 36 int_attrs(2)
            // 38 ext_attrs(4)
            // 42 local_header_offset(4)
            // 46 name(name_len)
            bb.position(bb.position() + 2) // skip version_made
            bb.position(bb.position() + 2) // skip version_needed
            val gpFlags = bb.short.toInt() and 0xFFFF
            val method = bb.short.toInt() and 0xFFFF
            bb.position(bb.position() + 4) // skip mod_time + mod_date
            bb.position(bb.position() + 4) // skip crc32

            val compSize = bb.int.toLong() and 0xFFFFFFFFL
            val uncompSize = bb.int.toLong() and 0xFFFFFFFFL
            val nameLen = bb.short.toInt() and 0xFFFF
            val extraLen = bb.short.toInt() and 0xFFFF
            val commentLen = bb.short.toInt() and 0xFFFF
            bb.position(bb.position() + 2) // skip disk_start
            bb.position(bb.position() + 2) // skip int_attrs
            bb.position(bb.position() + 4) // skip ext_attrs

            // ZIP64: if offset/sizes are 0xFFFFFFFF, read from extra field
            val localOffsetRaw = bb.int.toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            bb.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            // Parse ZIP64 extra field if needed
            var realCompSize = compSize
            var realUncompSize = uncompSize
            var realLocalOffset = localOffsetRaw

            if (uncompSize == ZIP64_MAGIC || compSize == ZIP64_MAGIC || localOffsetRaw == ZIP64_MAGIC) {
                val extraEnd = bb.position() + extraLen
                while (bb.position() < extraEnd - 4) {
                    val extraId = bb.short.toInt() and 0xFFFF
                    val extraSize = bb.short.toInt() and 0xFFFF
                    if (extraId == 0x0001) { // ZIP64 extended info
                        if (realUncompSize == ZIP64_MAGIC) realUncompSize = bb.long
                        if (realCompSize == ZIP64_MAGIC) realCompSize = bb.long
                        if (realLocalOffset == ZIP64_MAGIC) realLocalOffset = bb.long
                        break
                    } else {
                        bb.position(bb.position() + extraSize)
                    }
                }
                bb.position(extraEnd)
            } else {
                bb.position(bb.position() + extraLen)
            }
            bb.position(bb.position() + commentLen)

            entries.add(ZipEntryInfo(
                name = name,
                uncompressedSize = realUncompSize,
                compressedSize = realCompSize,
                method = method,
                localHeaderOffset = realLocalOffset,
            ))
        }

        return entries
    }
}
