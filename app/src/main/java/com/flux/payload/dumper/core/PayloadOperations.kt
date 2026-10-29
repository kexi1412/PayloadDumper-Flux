package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata.InstallOperation

/** Operation semantics, shared by extraction and best-effort boot metadata reconstruction. */
internal object PayloadOperations {
    /** Null only when the operation has a supported decoder and requires no old image. */
    fun unsupportedReason(operation: InstallOperation): String? {
        if (!operation.hasType()) return "missing or unknown operation type"
        when (operation.type) {
            InstallOperation.Type.REPLACE, InstallOperation.Type.REPLACE_BZ,
            InstallOperation.Type.REPLACE_XZ, InstallOperation.Type.ZERO,
            InstallOperation.Type.DISCARD -> Unit
            InstallOperation.Type.MOVE, InstallOperation.Type.BSDIFF,
            InstallOperation.Type.SOURCE_COPY, InstallOperation.Type.SOURCE_BSDIFF,
            InstallOperation.Type.BROTLI_BSDIFF, InstallOperation.Type.PUFFDIFF,
            InstallOperation.Type.ZUCCHINI, InstallOperation.Type.LZ4DIFF_BSDIFF,
            InstallOperation.Type.LZ4DIFF_PUFFDIFF ->
                return "incremental (delta) operation requires old/source partition data"
            else -> return "unsupported operation type; self-contained decoding is not known"
        }
        if (operation.srcExtentsCount != 0) return "references src_extents; old/source partition data is required"
        // src_length is used only by BSDIFF/SOURCE_BSDIFF, rejected by type above.
        // A value on a self-contained operation does not introduce a source dependency.
        if (!operation.srcSha256Hash.isEmpty) return "references src_sha256_hash; old/source partition data is required"
        return null
    }
}
