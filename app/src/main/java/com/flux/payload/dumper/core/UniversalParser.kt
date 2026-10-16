package com.flux.payload.dumper.core

import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.PartitionInfo

/**
 * Result of parsing any supported archive format.
 *
 * Either a CrAU payload (with manifest + dataOffset) or a list of raw entries
 * (from Honor/Huawei containers) that can be directly copied out.
 */
sealed interface ParseResult {

    data class CrAU(
        val payload: com.flux.payload.dumper.model.Payload,
    ) : ParseResult

    /**
     * Raw file entries inside a ZIP or UPDATE.APP — each entry is a partition image
     * that can be extracted by simple byte-range copying (no decompression/patching needed).
     */
    data class RawEntries(
        val archiveInfo: ArchiveInfo,
        val entries: List<RawEntry>,
    ) : ParseResult

    /** One extractable file inside a non-CrAU archive. */
    data class RawEntry(
        val name: String,
        val displayName: String,
        val offset: Long,
        val size: Long,
        val compressedSize: Long,
        val method: Int,     // 0=STORED, 8=DEFLATED
        val nestedZip: Boolean = false,  // entry is itself a ZIP containing more partitions
    )
}

/**
 * Universal archive parser: tries CrAU first, falls back to Honor/Huawei ZIP scanning.
 *
 * This is the single entry point for [DumperViewModel.parse()] — it auto-detects
 * the format and returns a unified result.
 */
object UniversalParser {

    fun parse(source: PayloadSource): ParseResult {
        val format = FormatDetector.detect(source)

        return when (format) {
            is ArchiveFormat.CrAUPayload -> {
                ParseResult.CrAU(com.flux.payload.dumper.core.PayloadParser.parse(source))
            }

            is ArchiveFormat.UpdateApp -> {
                // Raw UPDATE.APP — parse entries directly
                val entries = UpdateAppParser.parse(source, 0L)
                val rawEntries = entries.mapIndexed { i, e ->
                    val name = e.filename.ifBlank { "partition_$i" }
                    ParseResult.RawEntry(
                        name = name,
                        displayName = name,
                        offset = e.offset,
                        size = e.size,
                        compressedSize = e.size,
                        method = 0,
                    )
                }
                ParseResult.RawEntries(
                    archiveInfo = ArchiveInfo(
                        fileName = source.fileName(),
                        fileSize = source.size,
                        securityPatchLevel = "—",
                        partitionCount = rawEntries.size,
                        blockSize = 4096,
                    ),
                    entries = rawEntries,
                )
            }

            is ArchiveFormat.HonorSdUpdate -> {
                // Scan ZIP entries — find firmware images
                val entries = format.entries

                // Look for UPDATE.APP inside the ZIP
                val updateAppEntry = entries.find {
                    it.name.contains("UPDATE.APP", true) || it.name.contains("update.app", true)
                }

                val rawEntries = if (updateAppEntry != null) {
                    // Parse UPDATE.APP from within the ZIP
                    val dataOffset = ZipScanner.dataOffsetForEntry(source, updateAppEntry)
                    val updateEntries = UpdateAppParser.parse(source, dataOffset)
                    updateEntries.mapIndexed { i, e ->
                        val name = e.filename.ifBlank { "partition_$i" }
                        ParseResult.RawEntry(
                            name = name,
                            displayName = "${updateAppEntry.name}::$name",
                            offset = e.offset,
                            size = e.size,
                            compressedSize = e.size,
                            method = 0,
                        )
                    }
                } else {
                    // No UPDATE.APP — filter for interesting entries
                    val interesting = entries.filter { entry ->
                        entry.name.endsWith(".img", true) ||
                        entry.name.endsWith(".bin", true) ||
                        entry.name.endsWith(".new.dat", true) ||
                        entry.name.endsWith(".transfer.list", true) ||
                        (entry.name.endsWith(".zip", true) && entry.name.contains("update_sd", true))
                    }

                    if (interesting.isEmpty()) {
                        // Fall back to all entries with reasonable size
                        entries.filter { it.uncompressedSize > 1024 * 1024 } // > 1 MB
                            .map { entry ->
                                ParseResult.RawEntry(
                                    name = entry.name,
                                    displayName = entry.name,
                                    offset = ZipScanner.dataOffsetForEntry(source, entry),
                                    size = entry.uncompressedSize,
                                    compressedSize = entry.compressedSize,
                                    method = entry.method,
                                    nestedZip = entry.name.endsWith(".zip", true),
                                )
                            }
                    } else {
                        interesting.map { entry ->
                            ParseResult.RawEntry(
                                name = entry.name,
                                displayName = entry.name,
                                offset = ZipScanner.dataOffsetForEntry(source, entry),
                                size = entry.uncompressedSize,
                                compressedSize = entry.compressedSize,
                                method = entry.method,
                                nestedZip = entry.name.endsWith(".zip", true),
                            )
                        }
                    }
                }

                ParseResult.RawEntries(
                    archiveInfo = ArchiveInfo(
                        fileName = source.fileName(),
                        fileSize = source.size,
                        securityPatchLevel = "—",
                        partitionCount = rawEntries.size,
                        blockSize = 4096,
                    ),
                    entries = rawEntries,
                )
            }

            is ArchiveFormat.BlockOta -> {
                // Traditional block OTA — not yet supported for extraction
                val entries = ZipScanner.scanEntries(source)
                ParseResult.RawEntries(
                    archiveInfo = ArchiveInfo(
                        fileName = source.fileName(),
                        fileSize = source.size,
                        securityPatchLevel = "—",
                        partitionCount = entries.size,
                        blockSize = 4096,
                    ),
                    entries = entries.filter {
                        it.name.endsWith(".new.dat", true) || it.name.endsWith(".transfer.list", true)
                    }.map { entry ->
                        ParseResult.RawEntry(
                            name = entry.name,
                            displayName = entry.name,
                            offset = ZipScanner.dataOffsetForEntry(source, entry),
                            size = entry.uncompressedSize,
                            compressedSize = entry.compressedSize,
                            method = entry.method,
                        )
                    },
                )
            }

            is ArchiveFormat.Unknown -> {
                throw java.io.IOException("Unknown archive format — expected payload.bin, OTA ZIP, UPDATE.APP, or Honor SD update")
            }
        }
    }
}
