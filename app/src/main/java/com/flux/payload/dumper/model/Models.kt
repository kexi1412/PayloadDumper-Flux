package com.flux.payload.dumper.model

import chromeos_update_engine.UpdateMetadata

/** Parsed CrAU payload header (big-endian on-disk). */
data class PayloadHeader(
    val fileFormatVersion: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Int,
)

/**
 * A parsed payload, ready for extraction. [dataOffset] is the absolute offset in the
 * backing source where operation data blobs begin, so every read is positional and
 * therefore safe to issue concurrently from many workers.
 */
data class Payload(
    val fileName: String,
    val header: PayloadHeader,
    val manifest: UpdateMetadata.DeltaArchiveManifest,
    val dataOffset: Long,
    val blockSize: Int,
    val archiveSize: Long,
)

enum class ExtractState { IDLE, RUNNING, PAUSED, DONE, ERROR }
enum class VerifyState { NONE, VERIFYING, PASSED, FAILED, SKIPPED }

/** One selectable/extractable partition row in the UI. */
data class PartitionInfo(
    val partitionName: String,
    val size: Long,            // uncompressed image size (new_partition_info.size)
    val compressedSize: Long,  // sum of operation data lengths in the payload
    val sha256: String,        // expected hash (hex), empty if absent
    val extractState: ExtractState = ExtractState.IDLE,
    val verifyState: VerifyState = VerifyState.NONE,
    val progress: Float = 0f,
    val message: String = "",
)

data class ArchiveInfo(
    val fileName: String,
    val fileSize: Long,
    val securityPatchLevel: String,
    val partitionCount: Int,
    val blockSize: Int,
)
