package com.flux.payload.dumper.core

/**
 * Locate initialized kernel data structurally, not by guessing where linux_banner lives.
 * Android ARM64 EFI Images carry a PE section table. Their .data contains init_uts_ns:
 * six 65-byte, NUL-padded new_utsname fields, including the actual uname release.
 * Unsupported layouts return null so the existing bounded full-kernel reader remains the fallback.
 */
internal object Arm64KernelUts {
    const val HEADER_BYTES = 4096
    private const val MAX_DATA_BYTES = 8 * 1024 * 1024
    private const val FIELD_SIZE = 65
    private const val UTS_SIZE = 6 * FIELD_SIZE
    private val linux = "Linux\u0000".toByteArray(Charsets.US_ASCII)
    private val releasePattern = Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?[A-Za-z0-9._+\\-]*")
    private val versionPattern = Regex("#[0-9]+(?: .*|$)")

    data class DataSection(val offset: Int, val size: Int)

    /** Offsets are relative to the kernel, not to boot.img, a payload blob, or a PE RVA. */
    fun dataSection(header: ByteArray, kernelSize: Int): DataSection? {
        if (header.size < 64 || kernelSize < header.size || kernelSize > FirmwareMetadata.MAX_KERNEL_BYTES) return null
        if (header[0] != 0x4d.toByte() || header[1] != 0x5a.toByte()) return null
        if (FirmwareMetadata.u32(header, 56) != 0x644d5241L) return null // ARM64 Image magic
        val pe = FirmwareMetadata.u32(header, 60)
        if (pe < 64 || pe > header.size.toLong() - 24) return null
        val p = pe.toInt()
        if (FirmwareMetadata.u32(header, p) != 0x00004550L || u16(header, p + 4) != 0xaa64) return null
        val count = u16(header, p + 6)
        val optionalSize = u16(header, p + 20)
        if (count !in 1..16 || optionalSize !in 112..512) return null
        val table = p + 24 + optionalSize
        val tableEnd = table + count * 40
        if (tableEnd > header.size || u16(header, p + 24) != 0x20b) return null // PE32+
        val headerSize = FirmwareMetadata.u32(header, p + 24 + 60)
        if (headerSize < tableEnd || headerSize > kernelSize) return null
        var data: DataSection? = null
        val occupied = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until count) {
            val entry = table + i * 40
            val name = String(header, entry, 8, Charsets.US_ASCII).trimEnd('\u0000')
            val virtualSize = FirmwareMetadata.u32(header, entry + 8)
            val rawSize = FirmwareMetadata.u32(header, entry + 16)
            val rawOffset = FirmwareMetadata.u32(header, entry + 20)
            val flags = FirmwareMetadata.u32(header, entry + 36)
            if (rawSize > 0) {
                if (rawOffset < headerSize || rawOffset > kernelSize || rawSize > kernelSize.toLong() - rawOffset) return null
                val end = rawOffset + rawSize
                if (occupied.any { (start, stop) -> rawOffset < stop && end > start }) return null
                occupied += rawOffset to end
            }
            if (name == ".data") {
                // Initialized, readable, writable data; never executable code or uninitialized BSS.
                val initializedSize = minOf(rawSize, virtualSize)
                if (data != null || initializedSize !in UTS_SIZE.toLong()..MAX_DATA_BYTES.toLong()) return null
                if (flags and 0xc0000040L != 0xc0000040L || flags and 0x20000020L != 0L) return null
                data = DataSection(rawOffset.toInt(), initializedSize.toInt())
            }
        }
        return data
    }

    /** null = no proven UTS structure; empty = conflicting releases, which must stay unknown. */
    fun release(data: ByteArray): String? {
        if (data.size > MAX_DATA_BYTES) return null
        var result: String? = null
        for (offset in 0..data.size - UTS_SIZE) {
            if (offset and 65535 == 0) FirmwareMetadata.checkCancelled()
            if (data[offset] != linux[0] || linux.indices.any { data[offset + it] != linux[it] }) continue
            val sysname = field(data, offset) ?: continue
            val nodename = field(data, offset + FIELD_SIZE) ?: continue
            val candidate = field(data, offset + FIELD_SIZE * 2) ?: continue
            val version = field(data, offset + FIELD_SIZE * 3) ?: continue
            val machine = field(data, offset + FIELD_SIZE * 4) ?: continue
            field(data, offset + FIELD_SIZE * 5) ?: continue // domain may be empty, but must be well-formed
            if (sysname != "Linux" || nodename.isEmpty() || machine != "aarch64") continue
            if (!releasePattern.matches(candidate) || !versionPattern.matches(version)) continue
            if (result != null && result != candidate) return ""
            result = candidate
        }
        return result
    }

    private fun field(data: ByteArray, offset: Int): String? {
        var end = offset
        val limit = offset + FIELD_SIZE
        while (end < limit && data[end] != 0.toByte()) {
            if ((data[end].toInt() and 255) !in 32..126) return null
            end++
        }
        if (end == limit) return null
        if ((end until limit).any { data[it] != 0.toByte() }) return null
        return String(data, offset, end - offset, Charsets.US_ASCII)
    }

    private fun u16(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
}
