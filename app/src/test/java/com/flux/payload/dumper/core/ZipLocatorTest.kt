package com.flux.payload.dumper.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression coverage for the ZIP64 locator bug that broke every OTA larger than 4 GiB:
 * the locator sits exactly 20 bytes before the EOCD, not 24.
 */
class ZipLocatorTest {

    private class Memory(private val bytes: ByteArray) : PayloadSource {
        override val size = bytes.size.toLong()
        override fun fileName() = "ota.zip"
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            if (offset < 0 || len > bytes.size - offset) throw IOException("EOF at $offset")
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
        }
        override fun close() {}
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.s(value: Int): ByteBuffer = putShort(value.toShort())

    @Test
    fun findsPayloadInZip64ArchiveWithCommentSpaceAndEocdMagicFields() {
        val name = "payload.bin".toByteArray(Charsets.UTF_8)
        val data = "CrAU-test".toByteArray(Charsets.US_ASCII)

        val local = le(30 + name.size).apply {
            putInt(0x04034b50)
            s(45); s(0); s(0); s(0); s(0)
            putInt(0)                       // crc
            putInt(data.size); putInt(data.size)
            s(name.size); s(0)
            put(name)
        }.array()

        val cdOffset = local.size + data.size
        val cd = le(46 + name.size).apply {
            putInt(0x02014b50)
            s(45); s(45); s(0); s(0); s(0); s(0)
            putInt(0)
            putInt(data.size); putInt(data.size)
            s(name.size); s(0); s(0)
            s(0); s(0); putInt(0)
            putInt(0)                       // local header offset (fits in 32 bits here)
            put(name)
        }.array()

        val z64Offset = cdOffset + cd.size
        val z64 = le(56).apply {
            putInt(0x06064b50)
            putLong(44)
            s(45); s(45)
            putInt(0); putInt(0)
            putLong(1); putLong(1)
            putLong(cd.size.toLong()); putLong(cdOffset.toLong())
        }.array()

        val locator = le(20).apply {
            putInt(0x07064b50)
            putInt(0)
            putLong(z64Offset.toLong())
            putInt(1)
        }.array()

        // A 1 KiB comment plus ZIP64 escape values in the classic EOCD: this is the exact
        // shape produced by ota_from_target_files, and what the real 8.2 GB package uses.
        val comment = ByteArray(1024) { 0x41 }
        val eocd = le(22 + comment.size).apply {
            putInt(0x06054b50)
            s(0); s(0)
            s(0xFFFF); s(0xFFFF)
            putInt(0xFFFFFFFF.toInt()); putInt(0xFFFFFFFF.toInt())
            s(comment.size.toShort())
            put(comment)
        }.array()

        val archive = local + data + cd + z64 + locator + eocd
        val expected = 30 + name.size
        assertEquals(expected.toLong(), ZipLocator.findPayloadOffset(Memory(archive)))
    }

    @Test(expected = IOException::class)
    fun missingPayloadIsReported() {
        val name = "metadata".toByteArray(Charsets.UTF_8)
        val local = le(30 + name.size).apply {
            putInt(0x04034b50)
            s(20); s(0); s(0); s(0); s(0)
            putInt(0); putInt(0); putInt(0)
            s(name.size); s(0)
            put(name)
        }.array()
        val cd = le(46 + name.size).apply {
            putInt(0x02014b50)
            s(20); s(20); s(0); s(0); s(0); s(0)
            putInt(0); putInt(0); putInt(0)
            s(name.size); s(0); s(0)
            s(0); s(0); putInt(0); putInt(0)
            put(name)
        }.array()
        val eocd = le(22).apply {
            putInt(0x06054b50)
            s(0); s(0); s(1); s(1)
            putInt(cd.size); putInt(local.size + 0)
            s(0)
        }.array()
        ZipLocator.findPayloadOffset(Memory(local + cd + eocd))
    }
}
