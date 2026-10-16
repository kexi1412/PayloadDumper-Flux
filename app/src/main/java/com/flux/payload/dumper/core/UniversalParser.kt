package com.flux.payload.dumper.core

import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.PartitionInfo

/**
 * Result of parsing any supported archive format.
 */
sealed interface ParseResult {

    /** Google A/B OTA with a parseable CrAU payload. */
    data class CrAU(
        val payload: com.flux.payload.dumper.model.Payload,
    ) : ParseResult

    /** A set of directly extractable file entries (Honor/Huawei/ZIP/block OTA containers). */
    data class RawEntries(
        val archiveInfo: ArchiveInfo,
        val entries: List<RawEntry>,
    ) : ParseResult

    /** Where an entry's bytes come from. */
    sealed interface Origin {

        /**
         * The entry occupies a fixed byte range in the outer [PayloadSource].
         * Whether those bytes need inflating is given by [method].
         */
        data class OuterRange(
            val offset: Long,
            val compressedSize: Long,
            val uncompressedSize: Long,
            /** 0 = STORED (plain copy), 8 = DEFLATE. */
            val method: Int,
        ) : Origin

        /**
         * The entry lives inside a DEFLATE-compressed ZIP that is itself nested in the
         * outer source (Honor SD update layout). No temp file is written; the nested
         * stream is walked once and the entry's own deflate stream is inflated inline.
         */
        data class NestedZip(
            val ref: NestedZipStream.Ref,
            val inner: ZipEntryInfo,
        ) : Origin
    }

    /** One extractable file inside a non-CrAU archive. */
    data class RawEntry(
        val name: String,
        val displayName: String,
        val size: Long,
        val compressedSize: Long,
        val origin: Origin,
    )
}

/**
 * Universal archive parser.
 *
 * Detects the container, then produces either a CrAU [ParseResult.CrAU] or a flat list of
 * [ParseResult.RawEntry] items that can each be pulled out independently.
 *
 * **Honor / Huawei SD-card update layout** (verified against AAK-AN00 10.0.0.155):
 * ```
 * honor_ota.zip
 *  └── Software/dload/update_sd_base.zip     DEFLATE, 9.0 GB
 *       ├── SD_update.tag, UPT_VER.tag, ...  STORED
 *       └── image/system.img, vendor.img ... DEFLATE
 * ```
 * The middle level is never written to disk: [NestedZipStream] walks its deflate stream,
 * keeps a 4 MB tail window, parses the inner central directory out of it, and later
 * inflates just the requested inner entries. This is what makes a 10.7 GB package
 * parseable on a phone whose private cache could never hold 9 GB.
 */
object UniversalParser {

    /** Extensions we surface as extractable firmware images. */
    private val IMAGE_EXTENSIONS = listOf(".img", ".bin", ".elf", ".mbn", ".melf")

    /** Nested ZIPs that are worth descending into (firmware payloads, not app preloads). */
    private val NESTED_ZIP_HINTS = listOf("update_sd_base", "update_sd", "main_ota")

    /**
     * Parse [source].
     *
     * @param onProgress human-readable progress for long streaming operations
     */
    fun parse(source: PayloadSource, onProgress: ((String) -> Unit)? = null): ParseResult {
        return when (val format = FormatDetector.detect(source)) {
            is ArchiveFormat.CrAUPayload -> ParseResult.CrAU(PayloadParser.parse(source))

            is ArchiveFormat.UpdateApp -> parseRawUpdateApp(source)

            is ArchiveFormat.HonorSdUpdate -> parseHonor(source, format.entries, onProgress)

            is ArchiveFormat.BlockOta -> parseBlockOta(source)

            is ArchiveFormat.Unknown -> throw java.io.IOException(
                "Unrecognised archive — expected payload.bin (CrAU), an OTA ZIP, UPDATE.APP, or a Honor/Huawei SD update package"
            )
        }
    }

    // ---------------------------------------------------------------- Update.APP

    private fun parseRawUpdateApp(source: PayloadSource): ParseResult.RawEntries {
        val entries = UpdateAppParser.parse(source, 0L).mapIndexed { i, e ->
            val name = e.filename.ifBlank { "partition_$i" }
            ParseResult.RawEntry(
                name = name,
                displayName = name,
                size = e.size,
                compressedSize = e.size,
                origin = ParseResult.Origin.OuterRange(e.offset, e.size, e.size, 0),
            )
        }
        return ParseResult.RawEntries(
            archiveInfo = ArchiveInfo(
                fileName = source.fileName(),
                fileSize = source.size,
                securityPatchLevel = "—",
                partitionCount = entries.size,
                blockSize = 4096,
            ),
            entries = entries,
        )
    }

    // ---------------------------------------------------------------- Honor SD update

    private fun parseHonor(
        source: PayloadSource,
        outerEntries: List<ZipEntryInfo>,
        onProgress: ((String) -> Unit)?,
    ): ParseResult.RawEntries {
        val nestedZips = outerEntries.filter { entry ->
            entry.name.endsWith(".zip", ignoreCase = true) &&
                NESTED_ZIP_HINTS.any { entry.name.contains(it, ignoreCase = true) }
        }
        val updateAppEntry = outerEntries.find {
            it.name.contains("UPDATE.APP", ignoreCase = true)
        }
        val directImages = outerEntries.filter { isImage(it.name) }

        val raw = when {
            // Plain .img/.bin sitting directly in the outer ZIP.
            directImages.isNotEmpty() && nestedZips.isEmpty() -> {
                directImages.map { asOuterRange(source, it) }
            }

            // UPDATE.APP container inside the ZIP.
            updateAppEntry != null -> {
                val dataOffset = ZipScanner.dataOffsetForEntry(source, updateAppEntry)
                UpdateAppParser.parse(source, dataOffset).mapIndexed { i, e ->
                    val name = e.filename.ifBlank { "partition_$i" }
                    ParseResult.RawEntry(
                        name = name,
                        displayName = "${updateAppEntry.name}::$name",
                        size = e.size,
                        compressedSize = e.size,
                        origin = ParseResult.Origin.OuterRange(e.offset, e.size, e.size, 0),
                    )
                }
            }

            // Nested firmware ZIP — stream its central directory, never materialise it.
            nestedZips.isNotEmpty() -> parseNestedZips(source, nestedZips, onProgress)

            // Last resort: surface every sizeable entry.
            else -> outerEntries
                .filter { it.uncompressedSize > 1024 * 1024 }
                .map { asOuterRange(source, it) }
        }

        return ParseResult.RawEntries(
            archiveInfo = ArchiveInfo(
                fileName = source.fileName(),
                fileSize = source.size,
                securityPatchLevel = "—",
                partitionCount = raw.size,
                blockSize = 4096,
            ),
            entries = raw,
        )
    }

    /**
     * Walk each nested ZIP once to enumerate its contents, then expose its images as
     * [ParseResult.Origin.NestedZip] entries.
     */
    private fun parseNestedZips(
        source: PayloadSource,
        nestedZips: List<ZipEntryInfo>,
        onProgress: ((String) -> Unit)?,
    ): List<ParseResult.RawEntry> {
        val out = mutableListOf<ParseResult.RawEntry>()

        for (nested in nestedZips) {
            val label = nested.name.substringAfterLast('/')
            onProgress?.invoke("Reading $label (${fmtSize(nested.compressedSize)})…")

            val scan = NestedZipStream.scan(source, nested) { frac ->
                onProgress?.invoke("Reading $label — ${(frac * 100).toInt()}%")
            }

            // Small metadata .tag files are noise; show real firmware images only.
            val images = scan.ref.allEntries.filter { it.uncompressedSize > 0 && isImage(it.name) }
            val usable = images.ifEmpty { scan.ref.allEntries.filter { it.uncompressedSize > 0 } }

            usable.forEach { inner ->
                out.add(
                    ParseResult.RawEntry(
                        name = inner.name.substringAfterLast('/'),
                        displayName = "$label :: ${inner.name}",
                        size = inner.uncompressedSize,
                        compressedSize = inner.compressedSize,
                        origin = ParseResult.Origin.NestedZip(scan.ref, inner),
                    )
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Block OTA

    private fun parseBlockOta(source: PayloadSource): ParseResult.RawEntries {
        val entries = ZipScanner.scanEntries(source).filter {
            it.name.endsWith(".new.dat", ignoreCase = true) ||
                it.name.endsWith(".transfer.list", ignoreCase = true) ||
                it.name.endsWith(".new.dat.br", ignoreCase = true)
        }.map { asOuterRange(source, it) }

        return ParseResult.RawEntries(
            archiveInfo = ArchiveInfo(
                fileName = source.fileName(),
                fileSize = source.size,
                securityPatchLevel = "—",
                partitionCount = entries.size,
                blockSize = 4096,
            ),
            entries = entries,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun asOuterRange(source: PayloadSource, entry: ZipEntryInfo): ParseResult.RawEntry =
        ParseResult.RawEntry(
            name = entry.name.substringAfterLast('/'),
            displayName = entry.name,
            size = entry.uncompressedSize,
            compressedSize = entry.compressedSize,
            origin = ParseResult.Origin.OuterRange(
                offset = ZipScanner.dataOffsetForEntry(source, entry),
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                method = entry.method,
            ),
        )

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return IMAGE_EXTENSIONS.any { n.endsWith(it) }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Kept for API compatibility with callers that expected temp-file cleanup. */
    fun cleanupTempFiles() {
        NestedZipStream.cleanup()
    }
}
