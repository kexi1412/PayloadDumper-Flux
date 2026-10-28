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
    /**
     * Unique key for this row, and the argument passed back to the ViewModel on extract.
     *
     * Not necessarily the short partition name: a Honor package keeps three nested archives
     * and they reuse basenames (`preload.img`, `version.img`, …), so nested entries are keyed
     * by an archive-qualified label. See [title].
     */
    val partitionName: String,
    val size: Long,            // uncompressed image size (new_partition_info.size)
    val compressedSize: Long,  // sum of operation data lengths in the payload
    val sha256: String,        // expected hash (hex), empty if absent
    val extractState: ExtractState = ExtractState.IDLE,
    val verifyState: VerifyState = VerifyState.NONE,
    val progress: Float = 0f,
    val message: String = "",
    /** Short label to show in the list; falls back to [partitionName] when empty. */
    val title: String = "",
    /** Full qualified name, shown as the subtitle so the source archive is discoverable. */
    val subtitle: String = "",
) {
    /** What the row's title line should render. */
    val displayTitle: String get() = title.ifBlank { partitionName }
}

data class ArchiveInfo(
    val fileName: String,
    val fileSize: Long,
    val securityPatchLevel: String,
    val partitionCount: Int,
    val blockSize: Int,
    /** Values describe the selected firmware, never the device running this application. */
    val androidVersion: String = "",
    val kernelVersion: String = "",
    val fingerprint: String = "",
)
