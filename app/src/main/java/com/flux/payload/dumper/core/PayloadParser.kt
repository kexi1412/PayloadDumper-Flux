package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Parses the CrAU payload header and protobuf manifest from a [PayloadSource].
 *
 * Header layout (all integers big-endian):
 *   magic "CrAU" (4) | version u64 | manifest_size u64 | metadata_signature_size u32 |
 *   manifest[manifest_size] | metadata_signature[metadata_signature_size] | data...
 */
object PayloadParser {

    private const val MAGIC = "CrAU"
    private const val FORMAT_VERSION = 2L

    fun parse(source: PayloadSource): Payload {
        if (source.size < 24) throw IOException("Truncated payload header")
        val prefix = source.readFully(0, 24)
        val payloadOffset = ZipLocator.findPayloadOffset(source, prefix)
        if (payloadOffset < 0 || payloadOffset > source.size - 24) {
            throw IOException("Truncated payload header")
        }
        // Reuse the sniffed CrAU header: direct payloads still need only one fixed-header read.
        val header = java.nio.ByteBuffer.wrap(if (payloadOffset == 0L) prefix else source.readFully(payloadOffset, 24))
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { header.get(it) }
        if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
            throw IOException("Invalid magic — not a valid payload.bin")
        }
        val version = header.long
        if (version != FORMAT_VERSION) throw IOException("Unsupported payload version: $version")
        val manifestSize = header.long
        val signatureSize = header.int
        var p = payloadOffset + 24
        if (manifestSize <= 0 || manifestSize > 32L * 1024 * 1024) {
            throw IOException("Implausible manifest size: $manifestSize (maximum 32 MiB)")
        }
        if (signatureSize < 0 || p > source.size || manifestSize > source.size - p ||
            signatureSize.toLong() > source.size - p - manifestSize) {
            throw IOException("Payload metadata/signature extends beyond the archive")
        }

        val manifestBytes = source.readFully(p, manifestSize.toInt()); p += manifestSize
        p += signatureSize // skip metadata signature blob

        val manifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifestBytes)
        if (manifest.partitionsCount == 0) throw IOException("Manifest contains no partitions")

        return Payload(
            fileName = source.fileName(),
            header = PayloadHeader(version, manifestSize, signatureSize),
            manifest = manifest,
            dataOffset = p,
            blockSize = if (manifest.hasBlockSize()) manifest.blockSize else 4096,
            archiveSize = source.size,
        )
    }
}

/** Big-endian byte-array to Long (payload header integers are big-endian). */
internal fun ByteArray.beLong(): Long {
    var r = 0L
    for (b in this) r = (r shl 8) or (b.toLong() and 0xFF)
    return r
}

internal fun ByteArray.beInt(): Int = beLong().toInt()

internal fun com.google.protobuf.ByteString.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
