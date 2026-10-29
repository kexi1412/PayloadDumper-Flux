package com.flux.payload.dumper.core

import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.PartitionInfo
import java.io.IOException

/**
 * Result of parsing any supported archive format.
 */
sealed interface ParseResult {
    val archiveInfo: ArchiveInfo

    /** Google A/B OTA with a parseable CrAU payload. */
    data class CrAU(
        val payload: com.flux.payload.dumper.model.Payload,
        override val archiveInfo: ArchiveInfo,
    ) : ParseResult

    /** A set of directly extractable file entries (Honor/Huawei/ZIP/block OTA containers). */
    data class RawEntries(
        override val archiveInfo: ArchiveInfo,
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
     * @param includeKernel false for a remote interactive parse: publish the result first,
     *   then call [FirmwareMetadataReader.readKernel] on a separately owned HTTP metadata source.
     * @param onProgress human-readable progress for long streaming operations
     * @param onPartial receives an intermediate, complete-so-far entry list whenever a nested
     *   archive finishes being scanned. Scanning a Honor package means inflating 11.5 GB across
     *   three archives, so the caller can show results for the first one long before the last
     *   one lands instead of staring at a progress line. Always fires at least once, and the
     *   final invocation matches the returned [ParseResult.RawEntries] exactly.
     */
    fun parse(
        source: PayloadSource,
        includeKernel: Boolean = true,
        onProgress: ((String) -> Unit)? = null,
        onPartial: ((List<ParseResult.RawEntry>) -> Unit)? = null,
    ): ParseResult {
        val remote = if (!includeKernel && source is HttpSource) source.forParsing() else null
        return try {
            ParseReadCache(remote ?: source).use { input ->
                val format = FormatDetector.detect(input)
                if (remote != null && format !is ArchiveFormat.CrAUPayload) {
                    // Honor's nested DEFLATE ZIP may legitimately stream gigabytes just to
                    // discover entries. Do not apply a small-CrAU-metadata deadline to that work.
                    remote.close()
                    ParseReadCache(source).use { streaming ->
                        parseDetected(streaming, format, includeKernel, onProgress, onPartial)
                    }
                } else {
                    parseDetected(input, format, includeKernel, onProgress, onPartial)
                }
            }
        } finally {
            remote?.close()
        }
    }

    private fun parseDetected(
        input: PayloadSource,
        format: ArchiveFormat,
        includeKernel: Boolean,
        onProgress: ((String) -> Unit)?,
        onPartial: ((List<ParseResult.RawEntry>) -> Unit)?,
    ): ParseResult {
        val parsed = when (format) {
            is ArchiveFormat.CrAUPayload -> {
                val payload = PayloadParser.parse(input, input.readFully(0, 4))
                ParseResult.CrAU(payload, ArchiveInfo(
                    fileName = input.fileName(),
                    fileSize = input.size,
                    securityPatchLevel = "",
                    partitionCount = payload.manifest.partitionsCount,
                    blockSize = payload.blockSize,
                ))
            }

            is ArchiveFormat.UpdateApp -> parseRawUpdateApp(input)

            is ArchiveFormat.HonorSdUpdate -> parseHonor(input, format.entries, onProgress, onPartial)

            is ArchiveFormat.BlockOta -> parseBlockOta(input)

            is ArchiveFormat.Unknown -> throw java.io.IOException(
                "Unrecognised archive — expected payload.bin (CrAU), an OTA ZIP, UPDATE.APP, or a Honor/Huawei SD update package"
            )
        }
        onProgress?.invoke("Reading firmware metadata…")
        val metadata = FirmwareMetadataReader.read(input, parsed.archiveInfo, (parsed as? ParseResult.CrAU)?.payload, includeKernel)
        return when (parsed) {
            is ParseResult.CrAU -> parsed.copy(archiveInfo = metadata)
            is ParseResult.RawEntries -> parsed.copy(archiveInfo = metadata)
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
        onPartial: ((List<ParseResult.RawEntry>) -> Unit)?,
    ): ParseResult.RawEntries {
        val nestedZips = outerEntries.filter { entry ->
            entry.name.endsWith(".zip", ignoreCase = true) &&
                NESTED_ZIP_HINTS.any { entry.name.contains(it, ignoreCase = true) }
        }
        val updateAppEntry = outerEntries.find {
            it.name.contains("UPDATE.APP", ignoreCase = true)
        }
        val directImages = outerEntries.filter { isImage(it.name) }

        // Set when the nested branch runs, because that branch publishes from inside
        // parseNestedZips as each archive finishes. Keyed off the branch actually taken rather
        // than off `nestedZips.isEmpty()`: a package carrying both UPDATE.APP and nested ZIPs
        // takes the UPDATE.APP branch, and a nestedZips-based guard would then publish nothing.
        var published = false
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
            nestedZips.isNotEmpty() -> {
                published = true
                parseNestedZips(source, nestedZips, onProgress, onPartial)
            }

            // Last resort: surface every sizeable entry.
            else -> outerEntries
                .filter { it.uncompressedSize > 1024 * 1024 }
                .map { asOuterRange(source, it) }
        }

        // Every non-nested branch resolves in one shot, so publish here to keep the contract
        // that onPartial fires at least once and its final call matches the returned result.
        if (!published) onPartial?.invoke(raw)

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
     *
     * ## Failure policy
     *
     * Streaming an inner archive means inflating it end to end, and a Honor SD package hides
     * **11.5 GB** of that behind three nested ZIPs (`update_sd_base.zip` alone is 9.4 GB).
     * Enumerating it is by far the most expensive and most failure-prone step of a parse, so it
     * must not be able to destroy the whole result: a single hiccup in the smallest archive
     * would otherwise hide two perfectly readable ones. Each archive is therefore isolated, and
     * one that cannot be streamed is instead surfaced as a normal, directly extractable row for
     * the `.zip` itself.
     *
     * That fallback is not a consolation prize — it is a working path. `update_sd_base.zip` is
     * itself a plain ZIP, so extracting it and re-opening it in the app lists all 83 images
     * without any nested-streaming at all. Whatever goes wrong during the streamed pass, the
     * user still reaches their firmware.
     */
    private fun parseNestedZips(
        source: PayloadSource,
        nestedZips: List<ZipEntryInfo>,
        onProgress: ((String) -> Unit)?,
        onPartial: ((List<ParseResult.RawEntry>) -> Unit)?,
    ): List<ParseResult.RawEntry> {
        val out = mutableListOf<ParseResult.RawEntry>()

        for (nested in nestedZips) {
            val label = nested.name.substringAfterLast('/')
            onProgress?.invoke("Reading $label (${fmtSize(nested.compressedSize)})…")

            val scan = try {
                NestedZipStream.scan(source, nested, percentReporter(label, onProgress))
            } catch (e: Throwable) {
                // An allocation failure leaves the process unusable, so propagating it is
                // honest. Anything else is specific to this one archive.
                if (Diagnostics.isOutOfMemory(e)) throw e

                // Fall back to the nested ZIP as a row of its own, carrying the reason it could
                // not be streamed so the failure is visible rather than silently swallowed.
                onProgress?.invoke("$label — listed as a file (${Diagnostics.describe(e).lineSequence().first()})")
                out.add(asOuterRange(source, nested))
                onPartial?.invoke(out.toList())
                continue
            }

            // Small metadata .tag files are noise; show real firmware images only.
            val images = scan.ref.allEntries.filter { it.uncompressedSize > 0 && isImage(it.name) }
            val usable = images.ifEmpty { scan.ref.allEntries.filter { it.uncompressedSize > 0 } }

            if (usable.isEmpty()) {
                onProgress?.invoke("$label — no images inside, listed as a file")
                out.add(asOuterRange(source, nested))
                onPartial?.invoke(out.toList())
                continue
            }

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

            // Publish this archive's rows immediately. The three nested archives are scanned
            // one after another and each costs real time (the largest alone is ~11s of inflate),
            // so withholding every row until the last one lands leaves the user watching a
            // progress line with nothing to show for it. Emitting per archive turns one long
            // wait into three short ones and lets extraction start on the first results while
            // the rest are still being read.
            onPartial?.invoke(out.toList())
        }

        return out
    }

    /**
     * Progress callback that emits only when the whole-percent figure changes.
     *
     * The raw callback fires once per 1 MB read, which is ~9,000 emissions for the 9.4 GB
     * inner archive; each one reallocates a string and re-renders a Compose card on the main
     * thread. Percent granularity is all the UI shows anyway.
     */
    private fun percentReporter(
        label: String,
        onProgress: ((String) -> Unit)?,
    ): (Float) -> Unit {
        var last = -1
        return { frac ->
            val pct = (frac * 100).toInt().coerceIn(0, 100)
            if (pct != last) {
                last = pct
                onProgress?.invoke("Reading $label — $pct%")
            }
        }
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
