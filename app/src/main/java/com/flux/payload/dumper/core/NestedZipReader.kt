package com.flux.payload.dumper.core

import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.ZipException

/**
 * Reads entries from a ZIP that is itself embedded inside another ZIP,
 * without extracting the inner ZIP to disk.
 *
 * Honor SD-card update packages have this structure:
 *   honor_ota.zip
 *     └── Software/dload/update_sd_base.zip  (9 GB, DEFLATE)
 *           └── image/system.img, image/vendor.img, ... (100 entries)
 *
 * To avoid materializing a 9 GB temp file, this class streams the inner
 * ZIP's central directory from the outer ZIP's compressed entry data.
 *
 * Strategy:
 *  1. The outer ZIP entry is DEFLATE-compressed. We decompress it
 *     incrementally using [Inflater].
 *  2. The inner ZIP's EOCD is at the end of its decompressed data.
 *     Since we can't seek in a compressed stream, we decompress the
 *     entire inner ZIP to a temp file first (once), then scan normally.
 *
 * This is a pragmatic approach: the inner ZIP is ~9 GB, so we need
 * disk space for it. But we only do this once per parse, and extraction
 * afterwards reads directly from the temp file without re-decompression.
 */
object NestedZipReader {

    /**
     * Decompresses a ZIP entry that is itself a ZIP, writing it to a temp file,
     * then scans its central directory and returns all inner entries.
     *
     * @param outerSource the source backing the outer ZIP
     * @param outerEntry the entry info for the inner ZIP within the outer ZIP
     * @param tempDir where to write the decompressed inner ZIP
     * @return pair of (temp file, list of inner entries)
     */
    fun extractAndScan(
        outerSource: PayloadSource,
        outerEntry: ZipEntryInfo,
        tempDir: java.io.File,
    ): Pair<java.io.File, List<ZipEntryInfo>> {
        // Compute data offset in the outer source
        val dataOffset = ZipScanner.dataOffsetForEntry(outerSource, outerEntry)

        // Create temp file
        val tempFile = java.io.File(tempDir, "nested_${System.currentTimeMillis()}.zip")
        tempFile.deleteOnExit()

        // Decompress
        val rawInflater = Inflater()
        try {
            val inputBuf = ByteArray(256 * 1024)
            val outputBuf = ByteArray(512 * 1024)
            var srcPos = dataOffset
            val remaining = outerEntry.compressedSize

            java.io.FileOutputStream(tempFile).use { fos ->
                var consumed = 0L
                while (consumed < remaining) {
                    val toRead = minOf(inputBuf.size.toLong(), remaining - consumed).toInt()
                    outerSource.readFully(srcPos, inputBuf, 0, toRead)
                    rawInflater.setInput(inputBuf, 0, toRead)
                    srcPos += toRead
                    consumed += toRead

                    while (!rawInflater.needsInput()) {
                        val n = rawInflater.inflate(outputBuf)
                        if (n > 0) {
                            fos.write(outputBuf, 0, n)
                        } else break
                    }
                }
                // Flush remaining
                while (!rawInflater.finished()) {
                    val n = rawInflater.inflate(outputBuf)
                    if (n > 0) fos.write(outputBuf, 0, n)
                    else break
                }
            }
        } finally {
            rawInflater.end()
        }

        // Scan the extracted inner ZIP
        val innerSource = FileSource(tempFile)
        val entries = try {
            ZipScanner.scanEntries(innerSource)
        } finally {
            innerSource.close()
        }

        return tempFile to entries
    }
}
