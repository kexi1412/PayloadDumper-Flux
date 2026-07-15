package com.flux.payload.dumper.core

import io.airlift.compress.zstd.ZstdDecompressor
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream

/**
 * Decodes a single operation's data blob into raw partition bytes.
 *
 * The standard full-OTA operation types (REPLACE / REPLACE_BZ / REPLACE_XZ / ZERO) are handled
 * explicitly, matching payload-dumper-c. For any *unrecognized* operation type we sniff the
 * blob's magic bytes and pick the right codec — this transparently covers zstd-compressed blobs
 * that some OEMs emit under non-standard op numbers, without hard-coding a guessed enum value.
 */
object Decompressor {

    // Compression container magics.
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00)
    private val BZ2_MAGIC = byteArrayOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte())
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    // Lazy so aircompressor's Unsafe-backed init only runs if a zstd blob is actually seen —
    // keeps the common xz/bz2 paths independent of it.
    private val zstd by lazy { ZstdDecompressor() }

    fun replace(data: ByteArray): ByteArray = data

    fun xz(data: ByteArray): ByteArray =
        XZCompressorInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    fun bz2(data: ByteArray): ByteArray =
        BZip2CompressorInputStream(ByteArrayInputStream(data).buffered()).use { it.readBytes() }

    /** One-shot zstd decode into a buffer sized to the known output length. */
    fun zstd(data: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        val n = zstd.decompress(data, 0, data.size, out, 0, expectedSize)
        return if (n == expectedSize) out else out.copyOf(n)
    }

    /** Best-effort decode for an unknown op type, based on the blob's leading bytes. */
    fun sniffAndDecode(data: ByteArray, expectedSize: Int): ByteArray = when {
        data.startsWith(ZSTD_MAGIC) -> zstd(data, expectedSize)
        data.startsWith(XZ_MAGIC) -> xz(data)
        data.startsWith(BZ2_MAGIC) -> bz2(data)
        else -> data
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
