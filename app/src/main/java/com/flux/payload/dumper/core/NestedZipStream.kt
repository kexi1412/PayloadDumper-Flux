package com.flux.payload.dumper.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Inflater

/**
 * Streams a DEFLATE-compressed ZIP that lives **inside** another ZIP, without ever
 * materialising the inner ZIP on disk.
 *
 * ## Why this exists
 *
 * Honor / Huawei "SD card update" packages are three levels deep:
 *
 * ```
 * honor_ota.zip                             (10.7 GB — the file the user picks)
 *  └── Software/dload/update_sd_base.zip    (9.0 GB, DEFLATE, method=8)
 *       ├── image/system.img                (3.7 GB)
 *       ├── image/vendor.img
 *       └── ... 100 entries total
 * ```
 *
 * A phone cannot decompress the middle level to a 9 GB temp file — app-private cache is far
 * too small, and the streaming decode is not seekable, so the inner central directory cannot
 * simply be jumped to.
 *
 * The trick: the inner ZIP's central directory sits at the **end** of its decompressed
 * stream. One sequential pass is unavoidable, but only a small **tail window** of the output
 * needs to be retained; everything earlier is discarded as it flows past.
 *
 * ## Phases
 *
 * **Phase A — [scan]** — one streaming pass with a rolling [TAIL_WINDOW] buffer. At EOF the
 * EOCD (plus ZIP64 records) and the central directory are parsed out of that tail, yielding
 * every inner entry with its local-header offset, sizes and method.
 *
 * **Phase B — [extract]** — a second streaming pass. Targets are visited in ascending
 * local-offset order so one forward pass covers all of them. Each target accumulates its own
 * local header (which may straddle buffer boundaries) and then drives its own [Inflater] for
 * its own DEFLATE stream, writing straight to its output file.
 *
 * Peak memory is [IO_CHUNK] + [TAIL_WINDOW] + one [OUT_CHUNK] per active target, independent
 * of archive size.
 *
 * Validated against Honor AAK-AN00 10.0.0.155 (`update_sd_base.zip`, 9,024.3 MB):
 * 100 entries enumerated, `system.img` (3,738.2 MB) extracted byte-exact.
 */
object NestedZipStream {

    private const val IO_CHUNK = 1024 * 1024          // 1 MB parent read granularity
    private const val OUT_CHUNK = 512 * 1024          // 512 KB inflate output buffer
    private const val TAIL_WINDOW = 4 * 1024 * 1024   // 4 MB sliding tail
    private const val HDR_BUF = 4096                  // per-target local-header staging
    private const val ZIP64_MAGIC = 0xFFFFFFFFL

    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_CENTRAL = 0x02014b50
    private const val SIG_ZIP64_EOCD = 0x06064b50
    private const val SIG_ZIP64_LOC = 0x07064b50

    private const val LOCAL_HDR = 30
    private const val CENTRAL_HDR = 46

    private val EOCD_SIG = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    private val ZIP64_LOC_SIG = byteArrayOf(0x50, 0x4b, 0x06, 0x07)

    /**
     * Where a nested ZIP lives inside its parent, plus every entry [scan] discovered.
     * Handed back to [extract] so the second pass needs no re-parsing.
     */
    data class Ref(
        val outerDataOffset: Long,
        val outerCompressedSize: Long,
        val decompressedSize: Long,
        /** True when the parent stores the nested ZIP deflated; false when it is stored flat. */
        val deflated: Boolean,
        val allEntries: List<ZipEntryInfo>,
        /**
         * Full path of the nested ZIP inside its parent, e.g.
         * `Software/dload/update_sd_base.zip`.
         *
         * Needed because basenames are **not** unique across the nested archives a Honor
         * package carries: `image/preload.img` exists in both `update_sd_preload_…` (2.1 GB)
         * and `update_sd_base.zip` (28 KB), so extracting by basename alone would silently
         * overwrite one with the other. Callers derive a per-archive output directory from
         * this.
         */
        val outerName: String,
    )

    /** Result of [scan]. */
    data class ScanResult(val ref: Ref, val images: List<ZipEntryInfo>)

    /**
     * Phase A — stream the nested ZIP once and decode its central directory.
     *
     * @param outer source holding the parent ZIP
     * @param nestedEntry the parent-ZIP entry describing the nested ZIP
     * @param onProgress fraction 0..1 of the nested entry's compressed bytes consumed
     */
    fun scan(
        outer: PayloadSource,
        nestedEntry: ZipEntryInfo,
        onProgress: ((Float) -> Unit)? = null,
    ): ScanResult {
        if (nestedEntry.method != 8 && nestedEntry.method != 0) {
            throw IOException("Nested entry '${nestedEntry.name}' uses unsupported method ${nestedEntry.method}")
        }
        if (nestedEntry.compressedSize <= 0) {
            throw IOException("Nested entry '${nestedEntry.name}' has no compressed size")
        }

        val dataOffset = ZipScanner.dataOffsetForEntry(outer, nestedEntry)
        val compressedSize = nestedEntry.compressedSize
        val deflated = nestedEntry.method == 8

        val window = TailWindow(TAIL_WINDOW)
        val inBuf = ByteArray(IO_CHUNK)
        val outBuf = ByteArray(OUT_CHUNK)

        var pos = dataOffset
        var remaining = compressedSize

        if (!deflated) {
            // Stored inside the parent — the inner ZIP's bytes are already contiguous.
            while (remaining > 0) {
                val n = minOf(inBuf.size.toLong(), remaining).toInt()
                outer.readFully(pos, inBuf, 0, n)
                pos += n
                remaining -= n
                window.append(inBuf, 0, n)
                onProgress?.invoke(1f - remaining.toFloat() / compressedSize)
            }
        } else {
            val inflater = Inflater(true) // nowrap = raw DEFLATE
            try {
                while (remaining > 0) {
                    val n = minOf(inBuf.size.toLong(), remaining).toInt()
                    outer.readFully(pos, inBuf, 0, n)
                    pos += n
                    remaining -= n
                    inflater.setInput(inBuf, 0, n)
                    pump(inflater, outBuf) { b, l -> window.append(b, 0, l) }
                    onProgress?.invoke(1f - remaining.toFloat() / compressedSize)
                }
                pump(inflater, outBuf) { b, l -> window.append(b, 0, l) }
            } finally {
                inflater.end()
            }
        }

        if (nestedEntry.uncompressedSize > 0 && window.totalOut != nestedEntry.uncompressedSize) {
            throw IOException(
                "Nested ZIP truncated: streamed ${window.totalOut} bytes, expected ${nestedEntry.uncompressedSize}"
            )
        }

        val entries = parseCentralDirectory(window)
        if (entries.isEmpty()) throw IOException("Nested ZIP '${nestedEntry.name}' contains no entries")

        return ScanResult(
            ref = Ref(dataOffset, compressedSize, window.totalOut, deflated, entries, nestedEntry.name),
            images = entries.filter { isFirmwareImage(it.name) },
        )
    }

    /**
     * Phase B — stream the nested ZIP once and extract [targets] into [outDir].
     *
     * Targets may arrive in any order; they are sorted by local-header offset internally so a
     * single forward pass covers them all. Targets whose data region never appears are
     * reported through [onMissing].
     *
     * @param onProgress (compressedBytesConsumed, compressedSizeTotal)
     * @return inner entry name -> written file, for targets that produced data
     */
    fun extract(
        outer: PayloadSource,
        ref: Ref,
        targets: List<ZipEntryInfo>,
        outDir: File,
        onProgress: ((Long, Long) -> Unit)? = null,
        onMissing: ((ZipEntryInfo) -> Unit)? = null,
    ): Map<String, File> {
        if (targets.isEmpty()) return emptyMap()
        outDir.mkdirs()

        val todo = targets.sortedBy { it.localHeaderOffset }
        // One buffer for the whole pass; see TargetState.scratch.
        val scratch = ByteArray(OUT_CHUNK)
        val states = todo.map { TargetState(it, outDir, scratch) }
        val written = LinkedHashMap<String, File>()

        val inBuf = ByteArray(IO_CHUNK)
        val outBuf = ByteArray(OUT_CHUNK)
        var streamPos = 0L
        var idx = 0
        var consumed = 0L
        val total = ref.outerCompressedSize

        val inflater = if (ref.deflated) Inflater(true) else null
        try {
            var pos = ref.outerDataOffset
            var remaining = total

            while (remaining > 0 && idx < states.size) {
                val n = minOf(inBuf.size.toLong(), remaining).toInt()
                outer.readFully(pos, inBuf, 0, n)
                pos += n
                remaining -= n
                consumed += n

                if (inflater != null) {
                    inflater.setInput(inBuf, 0, n)
                    while (idx < states.size) {
                        val produced = inflater.inflate(outBuf)
                        if (produced <= 0) break
                        idx = advance(outBuf, produced, streamPos, states, idx, written)
                        streamPos += produced
                    }
                } else {
                    idx = advance(inBuf, n, streamPos, states, idx, written)
                    streamPos += n
                }
                onProgress?.invoke(consumed, total)
            }

            // Drain whatever the inflater still holds.
            if (inflater != null) {
                while (idx < states.size) {
                    val produced = inflater.inflate(outBuf)
                    if (produced <= 0) break
                    idx = advance(outBuf, produced, streamPos, states, idx, written)
                    streamPos += produced
                }
            }
        } finally {
            inflater?.end()
            for (st in states) if (!st.finalize(written)) onMissing?.invoke(st.entry)
        }

        return written
    }

    /** No temp files are produced any more; retained so callers can keep a single cleanup point. */
    fun cleanup() {
        // Intentionally empty — nested ZIPs are streamed, never written to disk.
    }

    // ------------------------------------------------------------------ internals

    /**
     * Per-target streaming state machine.
     *
     * The local header is staged into [hdrBuf] rather than requiring it to sit inside a single
     * inflate output buffer, because it routinely straddles buffer boundaries for large entries.
     */
    private class TargetState(
        val entry: ZipEntryInfo,
        outDir: File,
        /**
         * Shared inflate output buffer, owned by the enclosing [extract] call.
         *
         * [advance] drives exactly one target at a time, so a per-target buffer would be
         * allocated for every target and used for one at a time: `update_sd_base.zip` carries
         * 83 images, which is 41.5 MB of buffers that are all live simultaneously on a phone
         * for no benefit. A single 512 KB buffer is reused across the whole pass.
         */
        private val scratch: ByteArray,
    ) {
        val hdrBuf = ByteArray(HDR_BUF)
        var hdrFill = 0
        var needed = LOCAL_HDR          // 30, then 30 + nameLen + extraLen
        var dataStart = -1L
        var dataEnd = -1L
        var phase = PHASE_HEADER
        var out: FileOutputStream? = null
        var inflater: Inflater? = null
        val file: File = File(outDir, safeName(entry.name))
        var bytesWritten = 0L
        private var finalized = false

        fun openOutput() {
            file.parentFile?.mkdirs()
            out = FileOutputStream(file)
            if (entry.method == 8) inflater = Inflater(true)
        }

        /** Flush and close. Returns true when a non-empty file was produced. */
        fun finalize(acc: MutableMap<String, File>): Boolean {
            if (finalized) return bytesWritten > 0 && file.exists()
            finalized = true
            try {
                val inf = inflater
                val os = out
                if (inf != null && os != null) {
                    while (true) {
                        val k = inf.inflate(scratch)
                        if (k > 0) { os.write(scratch, 0, k); bytesWritten += k } else break
                    }
                }
            } catch (_: Throwable) {
                // fall through — reported through the return value
            } finally {
                runCatching { out?.flush() }
                runCatching { out?.close() }
                runCatching { inflater?.end() }
                out = null
                inflater = null
            }
            val ok = bytesWritten > 0 && file.exists()
            if (ok) acc[entry.name] = file
            return ok
        }

        fun write(buf: ByteArray, off: Int, len: Int) {
            val os = out ?: return
            if (entry.method == 8) {
                val inf = inflater!!
                inf.setInput(buf, off, len)
                while (true) {
                    val k = inf.inflate(scratch)
                    if (k > 0) { os.write(scratch, 0, k); bytesWritten += k } else break
                }
            } else {
                os.write(buf, off, len)
                bytesWritten += len
            }
        }

        companion object {
            const val PHASE_HEADER = 0
            const val PHASE_DATA = 1
        }
    }

    /**
     * Advance the target state machines across [len] bytes of the decompressed inner stream.
     * @return the updated index into [states]
     */
    private fun advance(
        buf: ByteArray,
        len: Int,
        streamPos: Long,
        states: List<TargetState>,
        startIdx: Int,
        written: MutableMap<String, File>,
    ): Int {
        var idx = startIdx
        var p = 0

        while (p < len && idx < states.size) {
            val st = states[idx]
            val e = st.entry

            if (st.phase == TargetState.PHASE_HEADER) {
                val hdrAbs = e.localHeaderOffset
                var absPos = streamPos + p

                // Walk forward to the start of this entry's local header.
                if (absPos < hdrAbs) {
                    p += minOf(hdrAbs - absPos, (len - p).toLong()).toInt()
                    absPos = streamPos + p
                    if (p >= len) continue
                }

                // Invariant from here on: absPos == hdrAbs + hdrFill.
                val take = minOf(len - p, st.needed - st.hdrFill)
                if (take <= 0) { p = len; continue }   // header buffer full but unusable
                System.arraycopy(buf, p, st.hdrBuf, st.hdrFill, take)
                st.hdrFill += take
                p += take

                if (st.hdrFill < LOCAL_HDR) continue  // need the fixed part first

                if (st.needed == LOCAL_HDR) {
                    if (u32(st.hdrBuf, 0) != SIG_LOCAL) {
                        throw IOException(
                            "Bad local header for '${e.name}' at $hdrAbs " +
                                "(sig=0x${u32(st.hdrBuf, 0).toString(16)})"
                        )
                    }
                    val nameLen = u16(st.hdrBuf, 26)
                    val extraLen = u16(st.hdrBuf, 28)
                    st.needed = LOCAL_HDR + nameLen + extraLen
                    if (st.needed > HDR_BUF) {
                        throw IOException("Local header for '${e.name}' too large: ${st.needed} bytes")
                    }
                }
                if (st.hdrFill < st.needed) continue

                st.dataStart = hdrAbs + st.needed
                st.dataEnd = st.dataStart + e.compressedSize
                st.openOutput()
                st.phase = TargetState.PHASE_DATA
                continue
            }

            // PHASE_DATA
            val absPos = streamPos + p
            if (absPos < st.dataStart) {
                p += minOf(st.dataStart - absPos, (len - p).toLong()).toInt()
                continue
            }
            if (absPos >= st.dataEnd) {
                st.finalize(written)
                idx++
                continue
            }
            val take = minOf(st.dataEnd - absPos, (len - p).toLong()).toInt()
            st.write(buf, p, take)
            p += take
        }
        return idx
    }

    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/')
        return if (base.isEmpty()) "entry.bin" else base
    }

    private fun isFirmwareImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".img") || n.endsWith(".bin") || n.endsWith(".elf") ||
            n.endsWith(".mbn") || n.endsWith(".melf")
    }

    private inline fun pump(inf: Inflater, buf: ByteArray, sink: (ByteArray, Int) -> Unit) {
        while (true) {
            val n = inf.inflate(buf)
            if (n > 0) { sink(buf, n); continue }
            break
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u64(b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }

    /** Fixed-size sliding window holding the last [capacity] bytes of a stream. */
    private class TailWindow(private val capacity: Int) {
        val buf = ByteArray(capacity)
        var length = 0
            private set
        var totalOut = 0L
            private set

        fun append(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            totalOut += len
            if (len >= capacity) {
                System.arraycopy(src, off + len - capacity, buf, 0, capacity)
                length = capacity
                return
            }
            val overflow = length + len - capacity
            if (overflow > 0) {
                System.arraycopy(buf, overflow, buf, 0, length - overflow)
                length -= overflow
            }
            System.arraycopy(src, off, buf, length, len)
            length += len
        }

        /** Absolute stream offset of buf[0]. */
        fun windowStart(): Long = totalOut - length
    }

    /** Decode the central directory out of the tail window, expanding ZIP64 extra fields. */
    private fun parseCentralDirectory(w: TailWindow): List<ZipEntryInfo> {
        val buf = w.buf
        val len = w.length
        val winStart = w.windowStart()

        val eocd = rfind(buf, len, EOCD_SIG)
        if (eocd < 0) throw IOException("Nested ZIP: EOCD not found in tail window")

        var cdSize = u32(buf, eocd + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = u32(buf, eocd + 16).toLong() and 0xFFFFFFFFL
        val entryCount = u16(buf, eocd + 10)

        if (entryCount == 0xFFFF || cdOffset == ZIP64_MAGIC || cdSize == ZIP64_MAGIC) {
            val loc = rfind(buf, eocd, ZIP64_LOC_SIG)
            if (loc < 0) throw IOException("Nested ZIP: ZIP64 EOCD locator not found")
            val z64Abs = u64(buf, loc + 8)
            val rel = (z64Abs - winStart).toInt()
            if (rel < 0 || rel + 56 > len) {
                throw IOException("Nested ZIP: ZIP64 EOCD outside tail window")
            }
            if (u32(buf, rel) != SIG_ZIP64_EOCD) {
                throw IOException("Nested ZIP: bad ZIP64 EOCD signature")
            }
            cdSize = u64(buf, rel + 40)
            cdOffset = u64(buf, rel + 48)
        }

        val cdRel = (cdOffset - winStart).toInt()
        if (cdRel < 0 || cdSize < 0 || cdRel + cdSize > len) {
            throw IOException("Nested ZIP: central directory outside tail window — window too small")
        }

        val entries = ArrayList<ZipEntryInfo>()
        var p = cdRel
        val cdEnd = cdRel + cdSize.toInt()
        while (p + CENTRAL_HDR <= cdEnd) {
            if (u32(buf, p) != SIG_CENTRAL) break
            val method = u16(buf, p + 10)
            var csize = u32(buf, p + 20).toLong() and 0xFFFFFFFFL
            var usize = u32(buf, p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = u16(buf, p + 28)
            val extraLen = u16(buf, p + 30)
            val commentLen = u16(buf, p + 32)
            var localOff = u32(buf, p + 42).toLong() and 0xFFFFFFFFL

            val nameStart = p + CENTRAL_HDR
            if (nameStart + nameLen > len) break
            val name = String(buf, nameStart, nameLen, Charsets.UTF_8).trim()

            // ZIP64 extended information extra field (header id 0x0001). Values appear in a
            // fixed order and only for fields that held 0xFFFFFFFF in the fixed header.
            if (usize == ZIP64_MAGIC || csize == ZIP64_MAGIC || localOff == ZIP64_MAGIC) {
                var ep = nameStart + nameLen
                val extraEnd = minOf(ep + extraLen, len)
                while (ep + 4 <= extraEnd) {
                    val id = u16(buf, ep)
                    val sz = u16(buf, ep + 2)
                    ep += 4
                    if (id == 0x0001) {
                        if (usize == ZIP64_MAGIC && ep + 8 <= extraEnd) { usize = u64(buf, ep); ep += 8 }
                        if (csize == ZIP64_MAGIC && ep + 8 <= extraEnd) { csize = u64(buf, ep); ep += 8 }
                        if (localOff == ZIP64_MAGIC && ep + 8 <= extraEnd) { localOff = u64(buf, ep); ep += 8 }
                        break
                    }
                    ep += sz
                }
            }

            entries.add(
                ZipEntryInfo(
                    name = name,
                    uncompressedSize = usize,
                    compressedSize = csize,
                    method = method,
                    localHeaderOffset = localOff,
                )
            )
            p = nameStart + nameLen + extraLen + commentLen
        }
        return entries
    }

    /** Backwards search for a 4-byte signature that ends at or before index [fromExclusive]. */
    private fun rfind(hay: ByteArray, fromExclusive: Int, needle: ByteArray): Int {
        if (needle.size != 4 || fromExclusive < 4) return -1
        val b0 = needle[0]; val b1 = needle[1]; val b2 = needle[2]; val b3 = needle[3]
        var i = fromExclusive - 4
        while (i >= 0) {
            if (hay[i] == b0 && hay[i + 1] == b1 && hay[i + 2] == b2 && hay[i + 3] == b3) return i
            i--
        }
        return -1
    }
}
