package com.flux.payload.dumper.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scans a ZIP file's central directory to enumerate **all** entries, not just payload.bin.
 *
 * This is the foundation for multi-format support: Honor SD update packages contain nested
 * ZIPs, UPDATE.APP files, `*.img` files, and other entries that must be discovered and shown.
 *
 * ## EOCD layout (offsets are from the end-of-central-directory signature)
 * ```
 *  0  signature                "PK\x05\x06"  (4)
 *  4  number of this disk                    (2)
 *  6  disk where CD starts                   (2)
 *  8  CD entries on this disk                (2)
 * 10  CD entries total                       (2)
 * 12  size of central directory              (4)
 * 16  offset of central directory            (4)
 * 20  comment length                         (2)
 * ```
 * Any of fields 8/10/12/16 set to `0xFFFF`/`0xFFFFFFFF` signals a ZIP64 record, which is
 * then located through the ZIP64 EOCD locator (`PK\x06\x07`) that precedes the EOCD.
 *
 * ## ZIP64 EOCD record layout
 * ```
 *  0  signature                "PK\x06\x06"  (4)
 *  4  size of remaining record               (8)
 * 12  version made by                        (2)
 * 14  version needed                         (2)
 * 16  number of this disk                    (4)
 * 20  disk where CD starts                   (4)
 * 24  CD entries on this disk                (8)
 * 32  CD entries total                       (8)
 * 40  size of central directory              (8)
 * 48  offset of central directory            (8)
 * ```
 */
object ZipScanner {

    private const val TAIL = 1 shl 20                // 1 MB tail is enough for EOCD + locator
    private const val MAX_CD_SIZE = 1 shl 26         // 64 MB sanity cap on the central directory
    private const val ENDHDR = 22
    private const val CENHDR = 46
    private const val LOCHDR = 30

    private const val PK_END = 0x06054b50L
    private const val PK_CENTRAL = 0x02014b50L
    private const val PK_LOCAL = 0x04034b50L
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP64_LOC = 0x07064b50L
    private const val ZIP64_MAGIC = 0xFFFFFFFFL

    /** Region of the archive occupied by the central directory. */
    private data class CDRegion(val offset: Long, val size: Long)

    /**
     * Enumerate every entry in the ZIP file backing [source].
     * @throws IOException if the central directory cannot be located or parsed.
     */
    fun scanEntries(source: PayloadSource): List<ZipEntryInfo> {
        val region = locateCentralDirectory(source)
        if (region.offset < 0 || region.size <= 0) throw IOException("Central directory not found")
        if (region.offset + region.size > source.size) {
            throw IOException(
                "Central directory out of range: offset=${region.offset} size=${region.size} file=${source.size}"
            )
        }
        if (region.size > MAX_CD_SIZE) {
            throw IOException("Central directory implausibly large: ${region.size} bytes")
        }
        val cd = source.readFully(region.offset, region.size.toInt())
        return parseCentralDirectory(cd)
    }

    /**
     * Find the absolute data offset of a ZIP entry by reading its local file header.
     *
     * Reads only as many bytes as the archive actually has left, so entries whose local
     * header sits near EOF do not trip a spurious short-read.
     *
     * @return absolute offset where the entry's compressed/stored data begins
     */
    fun dataOffsetForEntry(source: PayloadSource, entry: ZipEntryInfo): Long {
        if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + LOCHDR > source.size) {
            throw IOException("Local header offset out of range: ${entry.localHeaderOffset}")
        }
        val want = minOf((LOCHDR + 512).toLong(), source.size - entry.localHeaderOffset).toInt()
        val localHeader = source.readFully(entry.localHeaderOffset, want)

        val bb = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        val sig = bb.int.toLong() and 0xFFFFFFFFL
        if (sig != PK_LOCAL) {
            throw IOException(
                "Bad local header signature at ${entry.localHeaderOffset}: 0x${sig.toString(16)}"
            )
        }
        bb.position(26) // name_len
        val nameLen = bb.short.toInt() and 0xFFFF
        val extraLen = bb.short.toInt() and 0xFFFF
        return entry.localHeaderOffset + LOCHDR + nameLen + extraLen
    }

    /**
     * Locate the central directory, transparently handling ZIP64.
     *
     * The regular EOCD is searched in the tail of the file. When its count/size/offset
     * fields hold the ZIP64 escape value, the ZIP64 EOCD locator is scanned backwards from
     * the EOCD, and the real 64-bit values are read from the ZIP64 EOCD record.
     */
    private fun locateCentralDirectory(source: PayloadSource): CDRegion {
        val tailLen = minOf(TAIL.toLong(), source.size).toInt()
        val tailStart = source.size - tailLen
        val tail = source.readFully(tailStart, tailLen)

        val eocdRel = rfind(tail, tailLen, PK_END)
        if (eocdRel < 0) throw IOException("EOCD record not found")
        if (eocdRel + ENDHDR > tailLen) throw IOException("Truncated EOCD record")

        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

        // Correct field offsets, measured from the signature.
        val entriesThisDisk = shortAt(bb, eocdRel + 8)
        val entriesTotal = shortAt(bb, eocdRel + 10)
        val cdSize32 = intAt(bb, eocdRel + 12)
        val cdOffset32 = intAt(bb, eocdRel + 16)

        val needsZip64 = entriesThisDisk == 0xFFFF || entriesTotal == 0xFFFF ||
            cdSize32 == ZIP64_MAGIC || cdOffset32 == ZIP64_MAGIC

        if (!needsZip64) {
            return CDRegion(cdOffset32, cdSize32)
        }

        // ZIP64 EOCD locator sits somewhere before the EOCD.
        val locRel = rfind(tail, eocdRel, ZIP64_LOC)
        if (locRel < 0) throw IOException("ZIP64 EOCD locator not found")

        // locator: 0 sig(4), 4 disk with zip64 eocd(4), 8 offset of zip64 eocd(8), 16 total disks(4)
        val z64Offset = longAt(bb, locRel + 8)
        if (z64Offset < 0 || z64Offset + 56 > source.size) {
            throw IOException("ZIP64 EOCD offset out of range: $z64Offset")
        }

        // Read the ZIP64 EOCD record. It usually sits inside the tail we already have, but
        // a large central directory can push it out — in that case read it directly.
        val z64InTail = (z64Offset - tailStart).toInt()
        val z64: ByteArray
        var z64Base = 0
        if (z64InTail >= 0 && z64InTail + 56 <= tailLen) {
            z64 = tail
            z64Base = z64InTail
        } else {
            z64 = source.readFully(z64Offset, 56)
        }
        val zb = ByteBuffer.wrap(z64).order(ByteOrder.LITTLE_ENDIAN)
        val sig = intAt(zb, z64Base)
        if (sig != ZIP64_END) {
            throw IOException("Bad ZIP64 EOCD signature: 0x${sig.toString(16)}")
        }
        val cdSize = longAt(zb, z64Base + 40)
        val cdOffset = longAt(zb, z64Base + 48)
        return CDRegion(cdOffset, cdSize)
    }

    /** Parse a central directory blob into entries, expanding ZIP64 extra fields. */
    private fun parseCentralDirectory(cd: ByteArray): List<ZipEntryInfo> {
        val entries = mutableListOf<ZipEntryInfo>()
        val bb = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)

        while (bb.remaining() >= CENHDR) {
            val entryStart = bb.position()
            val sig = bb.int.toLong() and 0xFFFFFFFFL
            if (sig != PK_CENTRAL) break

            bb.position(entryStart + 10)
            val method = bb.short.toInt() and 0xFFFF

            var compSize = intAt(bb, entryStart + 20)
            var uncompSize = intAt(bb, entryStart + 24)
            val nameLen = shortAt(bb, entryStart + 28)
            val extraLen = shortAt(bb, entryStart + 30)
            val commentLen = shortAt(bb, entryStart + 32)
            var localOffset = intAt(bb, entryStart + 42)

            val nameStart = entryStart + CENHDR
            if (nameStart + nameLen > cd.size) break
            val name = String(cd, nameStart, nameLen, Charsets.UTF_8)

            // ZIP64 extended information extra field (header id 0x0001).
            // Values appear in this fixed order, and ONLY for fields that were 0xFFFFFFFF.
            if (uncompSize == ZIP64_MAGIC || compSize == ZIP64_MAGIC || localOffset == ZIP64_MAGIC) {
                var ep = nameStart + nameLen
                val extraEnd = minOf(ep + extraLen, cd.size)
                while (ep + 4 <= extraEnd) {
                    val id = shortAt(bb, ep)
                    val sz = shortAt(bb, ep + 2)
                    ep += 4
                    if (id == 0x0001) {
                        if (uncompSize == ZIP64_MAGIC && ep + 8 <= extraEnd) { uncompSize = longAt(bb, ep); ep += 8 }
                        if (compSize == ZIP64_MAGIC && ep + 8 <= extraEnd) { compSize = longAt(bb, ep); ep += 8 }
                        if (localOffset == ZIP64_MAGIC && ep + 8 <= extraEnd) { localOffset = longAt(bb, ep); ep += 8 }
                        break
                    }
                    ep += sz
                }
            }

            entries.add(
                ZipEntryInfo(
                    name = name,
                    uncompressedSize = uncompSize,
                    compressedSize = compSize,
                    method = method,
                    localHeaderOffset = localOffset,
                )
            )
            bb.position(nameStart + nameLen + extraLen + commentLen)
        }

        return entries
    }

    // ------------------------------------------------------------------ primitives

    private fun shortAt(bb: ByteBuffer, off: Int): Int = bb.getShort(off).toInt() and 0xFFFF

    private fun intAt(bb: ByteBuffer, off: Int): Long = bb.getInt(off).toLong() and 0xFFFFFFFFL

    private fun longAt(bb: ByteBuffer, off: Int): Long = bb.getLong(off)

    /** Backwards search for a 4-byte little-endian signature, starting at (and including) [from]. */
    private fun rfind(hay: ByteArray, fromExclusive: Int, sig: Long): Int {
        val b0 = (sig and 0xFF).toByte()
        val b1 = ((sig ushr 8) and 0xFF).toByte()
        val b2 = ((sig ushr 16) and 0xFF).toByte()
        val b3 = ((sig ushr 24) and 0xFF).toByte()
        var i = fromExclusive - 4
        while (i >= 0) {
            if (hay[i] == b0 && hay[i + 1] == b1 && hay[i + 2] == b2 && hay[i + 3] == b3) return i
            i--
        }
        return -1
    }
}
