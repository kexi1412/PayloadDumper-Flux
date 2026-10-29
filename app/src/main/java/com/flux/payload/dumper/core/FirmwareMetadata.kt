package com.flux.payload.dumper.core

import com.google.protobuf.CodedInputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/** Firmware-derived values only. Empty means unavailable; never consult android.os.Build. */
internal data class FirmwareFields(
    val androidVersion: String = "",
    val securityPatchLevel: String = "",
    val kernelVersion: String = "",
    val fingerprint: String = "",
) {
    fun fallback(other: FirmwareFields) = FirmwareFields(
        androidVersion.ifEmpty { other.androidVersion },
        securityPatchLevel.ifEmpty { other.securityPatchLevel },
        kernelVersion.ifEmpty { other.kernelVersion },
        fingerprint.ifEmpty { other.fingerprint },
    )
}

/** Independent, bounded implementation of the documented AOSP metadata/boot formats. */
internal object FirmwareMetadata {
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_KERNEL_BYTES = 64 * 1024 * 1024
    private val fingerprintPattern = Regex("^[^/:\\s]+/[^/:\\s]+/[^/:\\s]+:([^/:\\s]+)/[^/:\\s]+/[^/:\\s]+:[^/:\\s]+/[^/:\\s]+$")
    private val bannerPattern = Regex("^Linux version ([0-9]+\\.[0-9]+(?:\\.[0-9]+)?[A-Za-z0-9._+\\-]*) \\(")

    fun fromOtaText(text: String): FirmwareFields {
        val values = properties(text)
        return targetFields(values["post-build"].orEmpty(), values["post-sdk-level"].orEmpty(),
            values["post-security-patch-level"].orEmpty())
    }

    fun fromBuildProperties(text: String): FirmwareFields {
        val values = properties(text)
        val fingerprint = cleanFingerprint(values["ro.build.fingerprint"] ?: values["ro.system.build.fingerprint"].orEmpty())
        val release = clean(values["ro.build.version.release"] ?: values["ro.system.build.version.release"].orEmpty())
        return FirmwareFields(
            androidVersion = release.ifEmpty { releaseFromFingerprint(fingerprint) }.ifEmpty {
                releaseFromSdk(values["ro.build.version.sdk"] ?: values["ro.system.build.version.sdk"].orEmpty())
            },
            securityPatchLevel = cleanPatch(values["ro.build.version.security_patch"] ?: values["ro.system.build.version.security_patch"].orEmpty()),
            fingerprint = fingerprint,
        )
    }

    /** Only postcondition (field 6) describes the target. Precondition is never a fallback. */
    fun fromOtaProto(bytes: ByteArray): FirmwareFields {
        require(bytes.size <= MAX_TEXT_BYTES)
        val input = CodedInputStream.newInstance(bytes)
        var result = FirmwareFields()
        while (!input.isAtEnd) {
            checkCancelled()
            val tag = input.readTag()
            if (tag == 50) {
                val post = CodedInputStream.newInstance(input.readByteArray())
                val builds = mutableListOf<String>()
                var sdk = ""
                var patch = ""
                while (!post.isAtEnd) {
                    val inner = post.readTag()
                    when (inner) {
                        18 -> builds.add(post.readStringRequireUtf8())
                        42 -> sdk = post.readStringRequireUtf8()
                        50 -> patch = post.readStringRequireUtf8()
                        else -> if (!post.skipField(inner)) throw IOException("Unexpected protobuf end group")
                    }
                }
                // Multiple target fingerprints can be legitimate for multi-SKU OTAs.
                result = targetFields(builds.joinToString("|"), sdk, patch).fallback(result)
            } else if (!input.skipField(tag)) throw IOException("Unexpected protobuf end group")
        }
        return result
    }

    private fun targetFields(build: String, sdk: String, patch: String): FirmwareFields {
        val fingerprint = cleanFingerprint(build)
        return FirmwareFields(
            androidVersion = releaseFromFingerprint(fingerprint).ifEmpty { releaseFromSdk(sdk) },
            securityPatchLevel = cleanPatch(patch),
            fingerprint = fingerprint,
        )
    }

    /** Conflicting duplicate keys are discarded instead of silently choosing a fabricated target. */
    private fun properties(text: String): Map<String, String> {
        require(text.length <= MAX_TEXT_BYTES)
        val result = linkedMapOf<String, String>()
        val conflicting = hashSetOf<String>()
        text.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach
            val equal = trimmed.indexOf('=')
            if (equal <= 0) return@forEach
            val key = trimmed.substring(0, equal).trim()
            val value = clean(trimmed.substring(equal + 1))
            if (result.containsKey(key) && result[key] != value) conflicting.add(key)
            result[key] = value
        }
        conflicting.forEach(result::remove)
        return result
    }

    private fun clean(value: String): String = value.trim().takeIf {
        it.length <= 4096 && it.none { c -> c.code < 32 || c.code == 127 } &&
            !it.equals("unknown", true) && !it.equals("null", true) && it != "—"
    }.orEmpty()

    private fun cleanFingerprint(value: String): String {
        val candidates = clean(value).split('|').map(String::trim).filter(String::isNotEmpty).distinct()
        return if (candidates.isNotEmpty() && candidates.all { fingerprintPattern.matches(it) })
            candidates.joinToString("|") else ""
    }

    fun releaseFromFingerprint(value: String): String {
        val releases = value.split('|').mapNotNull { fingerprintPattern.matchEntire(it.trim())?.groupValues?.get(1) }.distinct()
        return releases.singleOrNull().orEmpty()
    }

    /** An unknown/future API level stays unknown, rather than being extrapolated numerically. */
    fun releaseFromSdk(value: String): String = when (value.trim().toIntOrNull()) {
        19, 20 -> "4.4"
        21 -> "5.0"
        22 -> "5.1"
        23 -> "6.0"
        24 -> "7.0"
        25 -> "7.1"
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> ""
    }

    fun cleanPatch(value: String): String {
        val text = value.trim()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(text)) return ""
        return try { LocalDate.parse(text).toString() } catch (_: Exception) { "" }
    }

    data class BootHeader(val kernelOffset: Int, val kernelSize: Int, val fields: FirmwareFields)

    fun bootHeader(image: ByteArray): BootHeader? {
        if (image.size < 48 || !image.copyOfRange(0, 8).contentEquals("ANDROID!".toByteArray(Charsets.US_ASCII))) return null
        val version = u32(image, 40)
        if (version !in 0L..4L) return null
        val kernelSize = u32(image, 8)
        if (kernelSize <= 0 || kernelSize > MAX_KERNEL_BYTES) return null
        val page = if (version >= 3) 4096L else u32(image, 36)
        if (page !in 512L..65536L || page and (page - 1) != 0L) return null
        if (page + kernelSize > MAX_KERNEL_BYTES) return null
        val packed = u32(image, if (version >= 3) 16 else 44)
        val android = if (packed ushr 11 == 0L) "" else
            "${(packed ushr 25) and 127}.${(packed ushr 18) and 127}.${(packed ushr 11) and 127}"
        val month = (packed and 15).toInt()
        val year = 2000 + ((packed ushr 4) and 127).toInt()
        // Boot headers only encode a month. Never invent a day such as 01 or 05.
        val patch = if (month in 1..12) "$year-${month.toString().padStart(2, '0')}" else ""
        return BootHeader(page.toInt(), kernelSize.toInt(), FirmwareFields(android, patch))
    }

    fun fromBootImage(image: ByteArray): FirmwareFields {
        val header = bootHeader(image) ?: return FirmwareFields()
        if (image.size.toLong() < header.kernelOffset.toLong() + header.kernelSize) return header.fields
        val kernel = image.copyOfRange(header.kernelOffset, header.kernelOffset + header.kernelSize)
        val decoded = optional {
            when {
                starts(kernel, 0x1f, 0x8b) -> GZIPInputStream(ByteArrayInputStream(kernel)).use { boundedRead(it, MAX_KERNEL_BYTES) }
                starts(kernel, 0x04, 0x22, 0x4d, 0x18) -> FramedLZ4CompressorInputStream(ByteArrayInputStream(kernel)).use { boundedRead(it, MAX_KERNEL_BYTES) }
                starts(kernel, 0x02, 0x21, 0x4c, 0x18) -> legacyLz4(kernel)
                else -> kernel
            }
        }
        return header.fields.copy(kernelVersion = decoded?.let(::kernelBanner).orEmpty())
    }

    /**
     * Search only the kernel region, never a ramdisk string or the host's /proc/version.
     * Repeated copies of one release are harmless, but two different syntactically valid
     * banners are ambiguous: byte order alone does not prove which one backs linux_banner.
     */
    fun kernelBanner(bytes: ByteArray): String {
        val marker = "Linux version ".toByteArray(Charsets.US_ASCII)
        var release = ""
        for (i in 0..bytes.size - marker.size) {
            if (i and 65535 == 0) checkCancelled()
            if (bytes[i] != marker[0]) continue
            if (marker.indices.any { bytes[i + it] != marker[it] }) continue
            val window = String(bytes, i, minOf(256, bytes.size - i), Charsets.ISO_8859_1)
            val candidate = bannerPattern.find(window)?.groupValues?.get(1) ?: continue
            if (release.isEmpty()) {
                release = candidate
            } else if (release != candidate) {
                return ""
            }
        }
        return release
    }

    private fun legacyLz4(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 4
        while (offset < bytes.size) {
            checkCancelled()
            if (bytes.size - offset < 4) throw IOException("Truncated legacy LZ4 block")
            val size = u32(bytes, offset); offset += 4
            if (size <= 0 || size > 9 * 1024 * 1024 || size > bytes.size - offset) throw IOException("Invalid legacy LZ4 block size")
            val block = BlockLZ4CompressorInputStream(ByteArrayInputStream(bytes, offset, size.toInt())).use {
                boundedRead(it, minOf(8 * 1024 * 1024, MAX_KERNEL_BYTES - out.size()))
            }
            out.write(block)
            offset += size.toInt()
        }
        return out.toByteArray()
    }

    fun boundedRead(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
            if (count < 0) return out.toByteArray()
            if (count == 0) throw IOException("Decoder made no progress")
            if (count > limit - out.size()) throw IOException("Metadata decoding budget exceeded")
            out.write(buffer, 0, count)
        }
    }

    fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 255) or ((bytes[offset + 1].toLong() and 255) shl 8) or
            ((bytes[offset + 2].toLong() and 255) shl 16) or ((bytes[offset + 3].toLong() and 255) shl 24)

    private fun starts(bytes: ByteArray, vararg magic: Int) = bytes.size >= magic.size &&
        magic.indices.all { bytes[it].toInt() and 255 == magic[it] }

    fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Firmware metadata parsing interrupted")
    }

    inline fun <T> optional(block: () -> T): T? = try {
        checkCancelled()
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException("Firmware metadata parsing interrupted").also { it.initCause(e) }
    } catch (_: Exception) {
        checkCancelled()
        null
    }
}
