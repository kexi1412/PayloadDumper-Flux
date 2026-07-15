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
        val payloadOffset = ZipLocator.findPayloadOffset(source)
        var p = payloadOffset

        val magic = source.readFully(p, 4); p += 4
        if (String(magic, StandardCharsets.US_ASCII) != MAGIC) {
            throw IOException("Invalid magic — not a valid payload.bin")
        }

        val version = source.readFully(p, 8).beLong(); p += 8
        if (version != FORMAT_VERSION) throw IOException("Unsupported payload version: $version")

        val manifestSize = source.readFully(p, 8).beLong(); p += 8
        val signatureSize = source.readFully(p, 4).beInt(); p += 4
        if (manifestSize <= 0 || manifestSize > Int.MAX_VALUE) {
            throw IOException("Implausible manifest size: $manifestSize")
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
