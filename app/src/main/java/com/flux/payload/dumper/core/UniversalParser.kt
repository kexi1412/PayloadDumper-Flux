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
        /** If non-null, data must be read from this file (not the original source). Used for nested ZIP extraction. */
        val sourceFile: java.io.File? = null,
    )
}

/**
 * Universal archive parser: tries CrAU first, falls back to Honor/Huawei ZIP scanning.
 *
 * This is the single entry point for [DumperViewModel.parse()] — it auto-detects
 * the format and returns a unified result.
 */
object UniversalParser {

    /** Temp files created for nested ZIP extraction — should be cleaned up after use. */
    private val nestedTempFiles = mutableListOf<java.io.File>()

    fun cleanupTempFiles() {
        nestedTempFiles.forEach { runCatching { it.delete() } }
        nestedTempFiles.clear()
    }

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

                // Look for nested firmware ZIPs (Honor SD update pattern):
                //   update_sd_base.zip (9 GB, contains image/*.img)
                //   update_sd_preload_*.zip (contains preinstalled apps)
                val nestedZips = entries.filter { entry ->
                    entry.name.endsWith(".zip", true) && (
                        entry.name.contains("update_sd_base", true) ||
                        entry.name.contains("update_sd", true) ||
                        entry.name.contains("main_ota", true)
                    )
                }

                // Also check for UPDATE.APP or direct .img entries
                val updateAppEntry = entries.find {
                    it.name.contains("UPDATE.APP", true) || it.name.contains("update.app", true)
                }
                val directImgs = entries.filter { it.name.endsWith(".img", true) || it.name.endsWith(".bin", true) }

                val rawEntries = when {
                    // Direct .img entries in the outer ZIP (no nesting)
                    directImgs.isNotEmpty() && nestedZips.isEmpty() -> {
                        directImgs.map { entry ->
                            ParseResult.RawEntry(
                                name = entry.name.substringAfterLast('/'),
                                displayName = entry.name,
                                offset = ZipScanner.dataOffsetForEntry(source, entry),
                                size = entry.uncompressedSize,
                                compressedSize = entry.compressedSize,
                                method = entry.method,
                            )
                        }
                    }

                    // UPDATE.APP inside the ZIP
                    updateAppEntry != null -> {
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
                    }

                    // Nested ZIPs — need to extract inner ZIP to temp and scan
                    nestedZips.isNotEmpty() -> {
                        val result = mutableListOf<ParseResult.RawEntry>()
                        for (nestedZip in nestedZips) {
                            val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "flux_nested")
                            tempDir.mkdirs()
                            val (tempFile, innerEntries) = NestedZipReader.extractAndScan(source, nestedZip, tempDir)
                            nestedTempFiles.add(tempFile)
                            // Compute data offsets for all inner entries using ONE FileSource
                            val innerSource = FileSource(tempFile)
                            try {
                                innerEntries.filter {
                                    it.name.endsWith(".img", true) ||
                                    it.name.endsWith(".bin", true) ||
                                    it.name.endsWith(".elf", true) ||
                                    it.name.endsWith(".mbn", true)
                                }.forEach { inner ->
                                    result.add(ParseResult.RawEntry(
                                        name = inner.name.substringAfterLast('/'),
                                        displayName = "${nestedZip.name.substringAfterLast('/')}::${inner.name}",
                                        offset = ZipScanner.dataOffsetForEntry(innerSource, inner),
                                        size = inner.uncompressedSize,
                                        compressedSize = inner.compressedSize,
                                        method = inner.method,
                                        nestedZip = true,
                                        sourceFile = tempFile,
                                    ))
                                }
                            } finally {
                                innerSource.close()
                            }
                        }
                        result
                    }

                    // Fallback: show all large entries
                    else -> {
                        entries.filter { it.uncompressedSize > 1024 * 1024 }
                            .map { entry ->
                                ParseResult.RawEntry(
                                    name = entry.name.substringAfterLast('/'),
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
