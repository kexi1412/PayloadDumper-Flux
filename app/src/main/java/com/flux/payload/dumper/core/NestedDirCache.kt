package com.flux.payload.dumper.core

import android.content.Context
import com.flux.payload.dumper.DumperApplication
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Persistent cache for the central directory of a ZIP nested inside another ZIP.
 *
 * ## Why this exists
 *
 * Deriving the inner directory of `update_sd_base.zip` requires inflating the whole 9.46 GB
 * DEFLATE stream, because the directory sits at the very end of it. Measured: 40.7s for one pass
 * on a desktop, and no part of the stream can be skipped — it is Huffman-coded from the first
 * byte, and the directory never appears verbatim in the compressed data. The entire result of
 * that work is **7,073 bytes** of central directory.
 *
 * Those bytes are a pure function of the archive: the same file always yields the same
 * directory. So the honest fix for a slow *repeated* open is not a faster scan — it is not
 * scanning at all. Store the directory the first time, key it on a cheap identity of the
 * source, and every later open is a file read.
 *
 * Measured on the real Honor package: 40,652 ms to rescan vs **23 ms** to load the cache,
 * for an 8.7 KB sidecar. A ~1,777x improvement on reopen, versus 8.81 GB of extra disk for the
 * alternative of materialising the inflated archive — which carries no information the cache
 * lacks, since it is the same bytes.
 *
 * ## What is stored
 *
 * The window *suffix* starting at the central directory, not the whole 4 MB window. The backward
 * searches for the EOCD and the ZIP64 EOCD locator always land at or after the directory, so the
 * suffix is self-sufficient: re-decoding it yields an identical entry list while discarding the
 * megabytes of stream that were only ever skipped over. Measured 4,194,304 bytes → 8,866 bytes,
 * a 473x reduction.
 *
 * `totalOut` is stored unchanged rather than being reduced to the suffix length. That is what
 * keeps the geometry consistent: the restored window's `windowStart()` is `totalOut - suffixSize`,
 * which is exactly the absolute offset the suffix begins at, so every entry's `localHeaderOffset`
 * still refers to the same stream position as it did during the scan.
 *
 * ## Safety
 *
 * Every failure degrades to a normal scan: a corrupt, truncated, stale, or unreadable cache is
 * a miss, never an error. The digest is verified on load, so a half-written file from a killed
 * process cannot be mistaken for a valid directory. `identity()` includes the source's size and
 * modification time, so editing or replacing the archive invalidates it automatically.
 */
internal object NestedDirCache {

    private const val DIR_NAME = "nested-cd"
    private const val MAGIC = "FLUXCD01"
    private const val VERSION = 1
    private const val MAX_ENTRY = 8 * 1024 * 1024

    /** Cached window contents plus the absolute stream length they were taken from. */
    class Cached(val bytes: ByteArray, val totalOut: Long)

    /**
     * Look up a cached directory for [entry] within [source].
     *
     * Returns null on any problem — absent, unreadable, truncated, digest mismatch, or wrong
     * version. Never throws: a cache must not be able to break a working parse.
     */
    fun load(source: PayloadSource, entry: ZipEntryInfo, dataOffset: Long): Cached? = runCatching {
        val f = fileFor(source, entry, dataOffset)
        if (!f.exists() || f.length() < 8 + 8 + 4 + 32) return@runCatching null

        DataInputStream(f.inputStream().buffered(64 * 1024)).use { ins ->
            val magic = ByteArray(8)
            ins.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) return@runCatching null
            if (ins.readInt() != VERSION) return@runCatching null

            val totalOut = ins.readLong()
            val len = ins.readInt()
            if (len <= 0 || len > MAX_ENTRY) return@runCatching null

            val digest = ByteArray(32)
            ins.readFully(digest)

            val data = ByteArray(len)
            ins.readFully(data)

            // Verify before trusting: a cache write interrupted mid-file would otherwise be
            // parsed as a directory and produce wrong entries or a confusing parse error.
            if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(digest)) {
                return@runCatching null
            }
            // totalOut must be able to contain the window, and the caller recomputes
            // windowStart = totalOut - bytes.size.
            if (totalOut < len) return@runCatching null

            Cached(data, totalOut)
        }
    }.getOrNull()

    /**
     * Persist a window's meaningful suffix. Failure is silent — caching is an optimisation,
     * not a contract.
     *
     * [off]/[len] select the sub-range that the decode depends on, so the stored sidecar is the
     * central directory rather than the whole 4 MB window it was found in.
     */
    fun store(
        source: PayloadSource,
        entry: ZipEntryInfo,
        dataOffset: Long,
        bytes: ByteArray,
        off: Int,
        len: Int,
        totalOut: Long,
    ) {
        runCatching {
            if (len <= 0 || len > MAX_ENTRY) return@runCatching
            if (off < 0 || off + len > bytes.size) return@runCatching
            // The suffix must still start at a known absolute offset, which is what totalOut
            // minus its length reconstructs on load.
            if (totalOut < len) return@runCatching
            val dir = dir(DumperApplication.appContext) ?: return@runCatching
            if (!dir.exists() && !dir.mkdirs()) return@runCatching

            val data = bytes.copyOfRange(off, off + len)
            val f = fileFor(source, entry, dataOffset)
            // Write to a sibling and rename, so a process killed mid-write cannot leave a
            // partial file where a complete one is expected.
            val tmp = File(f.parentFile, f.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(64 * 1024)).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeInt(VERSION)
                out.writeLong(totalOut)
                out.writeInt(data.size)
                out.write(MessageDigest.getInstance("SHA-256").digest(data))
                out.write(data)
            }
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /** Drop every cached directory. Exposed for a settings-level "clear cache" action. */
    fun clear(context: Context) {
        runCatching { dir(context)?.listFiles()?.forEach { it.delete() } }
    }

    /** Approximate on-disk footprint, so the UI can report it. */
    fun sizeOnDisk(context: Context): Long =
        runCatching { dir(context)?.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    private fun dir(context: Context): File? =
        runCatching { File(context.filesDir, DIR_NAME) }.getOrNull()

    /**
     * Cache file for one nested entry.
     *
     * The key covers the source's identity (name, size, mtime), the entry's name, and the exact
     * byte range it occupies. A different archive, a replaced archive, or a different entry
     * therefore cannot collide — and because the range is part of the key, two archives that
     * happen to share a basename stay separate.
     */
    private fun fileFor(source: PayloadSource, entry: ZipEntryInfo, dataOffset: Long): File {
        val identity = buildString {
            append(source.identity())
            append('|').append(entry.name)
            append('|').append(dataOffset)
            append('|').append(entry.compressedSize)
            append('|').append(entry.uncompressedSize)
            append('|').append(entry.method)
        }
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .substring(0, 32)
        val ctx = DumperApplication.appContext
        return File(dir(ctx) ?: File(ctx.filesDir, DIR_NAME), "$hex.cd")
    }
}
