package com.flux.payload.dumper.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Locates `payload.bin` inside an OTA `.zip` without decompressing anything.
 *
 * OTA zips store `payload.bin` uncompressed (STORED), so once we know the offset of its local
 * file data we can seek straight into the CrAU stream. Reads go through [PayloadSource] so the
 * exact same code path works for both local files and remote URLs. Handles ZIP64.
 */
object ZipLocator {

    private const val ENDSIG = 0x06054b50L        // End of central directory "PK\05\06"
    private const val CENSIG = 0x02014b50L        // Central directory entry "PK\01\02"
    private const val LOCSIG = 0x04034b50L        // Local file header "PK\03\04"
    private const val ZIP64_ENDSIG = 0x06064b50L
    private const val ZIP64_LOCSIG = 0x07064b50L
    private const val ENDHDR = 22
    private const val ZIP64_LOCHDR = 20
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL
    private const val TAIL = 4096
    private const val PAYLOAD_NAME = "payload.bin"

    /** Absolute offset where the CrAU stream begins, or throws if it can't be located. */
    fun findPayloadOffset(source: PayloadSource): Long {
        if (source.fileName().endsWith(".bin", ignoreCase = true)) return 0L

        val tailLen = minOf(TAIL.toLong(), source.size).toInt()
        val tail = source.readFully(source.size - tailLen, tailLen)
        val cd = locateCentralDirectory(tail, source.size)
        if (cd.offset < 0 || cd.size <= 0) {
            throw IOException("Central directory not found — is this an OTA zip or payload.bin?")
        }

        val centralDirectory = source.readFully(cd.offset, cd.size.toInt())
        val localHeaderOffset = locateLocalFileHeader(centralDirectory, PAYLOAD_NAME)
        if (localHeaderOffset < 0) throw IOException("payload.bin not found inside the zip")

        val localHeader = source.readFully(localHeaderOffset, 256)
        val dataStartWithinHeader = localFileDataOffset(localHeader)
        if (dataStartWithinHeader < 0) throw IOException("Malformed local file header for payload.bin")
        return localHeaderOffset + dataStartWithinHeader
    }

    private data class Region(val offset: Long, val size: Long)

    private fun locateCentralDirectory(tail: ByteArray, fileLength: Long): Region {
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val base = bb.capacity() - ENDHDR
        var cenSize = -1L
        var cenOffset = -1L

        for (i in 0..(bb.capacity() - ENDHDR)) {
            bb.position(base - i)
            if (bb.int.toLong() and 0xFFFFFFFFL == ENDSIG) {
                val endSigOffset = bb.position()
                bb.position(endSigOffset + 12)
                val maybeMagic = bb.int.toLong() and 0xFFFFFFFFL
                if (maybeMagic == ZIP64_MAGICVAL) {
                    // ZIP64 path
                    bb.position(endSigOffset - ZIP64_LOCHDR - 4)
                    if (bb.int.toLong() and 0xFFFFFFFFL == ZIP64_LOCSIG) {
                        bb.position(bb.position() + 4)
                        val zip64EndSigOffset = bb.long
                        val rel = (fileLength - zip64EndSigOffset).toInt()
                        if (rel in 0..tail.size) {
                            bb.position(tail.size - rel)
                            if (bb.int.toLong() and 0xFFFFFFFFL == ZIP64_ENDSIG) {
                                bb.position(bb.position() + 36)
                                cenSize = bb.long
                                cenOffset = bb.long
                            }
                        }
                    }
                } else {
                    bb.position(endSigOffset + 8)
                    cenSize = bb.int.toLong() and 0xFFFFFFFFL
                    cenOffset = bb.int.toLong() and 0xFFFFFFFFL
                    break
                }
            }
        }
        return Region(cenOffset, cenSize)
    }

    private fun locateLocalFileHeader(centralDirectory: ByteArray, name: String): Long {
        val bb = ByteBuffer.wrap(centralDirectory).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 46) {
            if (bb.int.toLong() and 0xFFFFFFFFL != CENSIG) break
            bb.position(bb.position() + 24)
            val nameLen = bb.short.toInt() and 0xFFFF
            val extraLen = bb.short.toInt() and 0xFFFF
            val commentLen = bb.short.toInt() and 0xFFFF
            bb.position(bb.position() + 8)
            val localHeaderOffset = bb.int.toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen)
            bb.get(nameBytes)
            if (name == String(nameBytes, Charsets.UTF_8)) return localHeaderOffset
            bb.position(bb.position() + extraLen + commentLen)
        }
        return -1
    }

    /** Offset from the start of a local file header to its file data. */
    private fun localFileDataOffset(localHeader: ByteArray): Long {
        val bb = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int.toLong() and 0xFFFFFFFFL != LOCSIG) return -1
        bb.position(bb.position() + 22)
        val nameLen = bb.short.toInt() and 0xFFFF
        val extraLen = bb.short.toInt() and 0xFFFF
        return (bb.position() + nameLen + extraLen).toLong()
    }
}
