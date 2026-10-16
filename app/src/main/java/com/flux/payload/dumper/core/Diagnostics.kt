package com.flux.payload.dumper.core

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns an opaque parse failure into something that can be acted on.
 *
 * ## Why this exists
 *
 * The parse path used to run inside `runCatching { … }.onFailure { e ->
 * ParseState.Failed(e.message ?: getString(R.string.parse_failed)) }`. `runCatching` is
 * declared `catch (e: Throwable)`, so it swallows [OutOfMemoryError] and
 * [StackOverflowError] too — and those carry a **null** message. The result was that every
 * distinct device-side failure, including an allocation failure, rendered as the same
 * seven-character string, with the real cause discarded.
 *
 * [describe] therefore always names the throwable's class and walks the full cause chain,
 * and [dump] writes everything [probe] can measure about the archive next to the extracted
 * images, so a failure on a phone can be diagnosed without adb.
 */
object Diagnostics {

    private const val PK_END = 0x06054b50L
    private const val PK_CENTRAL = 0x02014b50L
    private const val PK_LOCAL = 0x04034b50L
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP64_LOC = 0x07064b50L
    private const val ZIP64_MAGIC = 0xFFFFFFFFL

    private const val TAIL = 1 shl 20
    private const val CENHDR = 46

    /** Was the failure an allocation failure? Those need different advice from a bad file. */
    fun isOutOfMemory(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is OutOfMemoryError) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /** Readable version of [t]: class name plus message, then the whole cause chain. */
    fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (depth > 0) sb.append("\n  caused by: ")
            sb.append(cur.javaClass.name)
            val m = cur.message
            if (m.isNullOrBlank()) sb.append(" (no message)") else sb.append(": ").append(m)
            cur = cur.cause
            depth++
        }
        if (isOutOfMemory(t)) {
            sb.append("\n  hint: the process ran out of heap, not a problem with the archive itself")
        }
        return sb.toString()
    }

    /** Installed version, so a screenshot identifies the build that produced it. */
    fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "Flux ${pi.versionName} (${pi.versionCode})"
    }.getOrElse { "Flux (version unknown)" }

    /**
     * Measure everything the ZIP readers rely on. Never throws: each step is guarded, so a
     * partially readable source still yields a useful report.
     */
    fun probe(source: PayloadSource): String = buildString {
        appendLine("file          : ${runCatching { source.fileName() }.getOrElse { "?" }}")
        appendLine("size          : ${source.size} bytes (${gb(source.size)})")

        val head = runCatching { source.readFully(0, minOf(64L, source.size).toInt()) }.getOrNull()
        if (head == null) {
            appendLine("first 16      : <unreadable — the picker may not grant random access>")
        } else {
            appendLine("first 16      : ${hex(head, 0, minOf(16, head.size))}")
            appendLine("magic         : ${magicOf(head)}")
        }

        val tailLen = minOf(TAIL.toLong(), source.size).toInt()
        val tailStart = source.size - tailLen
        val tail = runCatching { source.readFully(tailStart, tailLen) }.getOrNull()
        if (tail == null) {
            appendLine("tail          : <unreadable>")
            return@buildString
        }
        appendLine("tail window   : start=$tailStart len=$tailLen")
        appendLine("last 16       : ${hex(tail, tailLen - 16, 16)}")

        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocds = scanBackwards(tail, tailLen, PK_END, 6)
        appendLine("EOCD hits     : ${eocds.size} ${eocds}")
        if (eocds.isEmpty()) {
            appendLine("VERDICT       : no EOCD in the trailing 1 MB — the copy is truncated or is not a ZIP")
            return@buildString
        }

        val rel = eocds.first()
        // The signature search can land within 18 bytes of the window end (a false positive, or
        // a genuinely truncated file). Reading EOCD fields past that would throw
        // IndexOutOfBoundsException out of a function whose whole purpose is to not throw.
        if (rel + 22 > tailLen) {
            appendLine("VERDICT       : EOCD candidate at tail[$rel] is truncated inside the window")
            return@buildString
        }
        val entriesThis = shortAt(bb, rel + 8)
        val entriesTotal = shortAt(bb, rel + 10)
        val cdSize32 = intAt(bb, rel + 12)
        val cdOffset32 = intAt(bb, rel + 16)
        appendLine("chosen EOCD   : tail[$rel] (absolute ${tailStart + rel})")
        appendLine("entriesThis   : $entriesThis")
        appendLine("entriesTotal  : $entriesTotal")
        appendLine("cdSize32      : $cdSize32")
        appendLine("cdOffset32    : $cdOffset32 (0x${cdOffset32.toString(16)})")

        val needsZip64 = entriesThis == 0xFFFF || entriesTotal == 0xFFFF ||
            cdSize32 == ZIP64_MAGIC || cdOffset32 == ZIP64_MAGIC
        appendLine("zip64 required: $needsZip64")

        var cdOffset = cdOffset32
        var cdSize = cdSize32

        if (needsZip64) {
            val locRel = firstBackwards(tail, rel, ZIP64_LOC)
            if (locRel < 0) {
                appendLine("VERDICT       : ZIP64 required but no locator found before the EOCD")
                return@buildString
            }
            if (locRel + 20 > tailLen) {
                appendLine("VERDICT       : ZIP64 locator at tail[$locRel] is truncated inside the window")
                return@buildString
            }
            val z64Abs = longAt(bb, locRel + 8)
            appendLine("zip64 locator : tail[$locRel] -> z64 at $z64Abs")
            val z64Rel = (z64Abs - tailStart).toInt()
            if (z64Rel < 0 || z64Rel + 56 > tailLen) {
                appendLine("VERDICT       : ZIP64 EOCD record at $z64Abs is outside the trailing window")
                return@buildString
            }
            if (intAt(bb, z64Rel) != ZIP64_END) {
                appendLine("VERDICT       : bad ZIP64 EOCD signature 0x${intAt(bb, z64Rel).toString(16)}")
                return@buildString
            }
            cdSize = longAt(bb, z64Rel + 40)
            cdOffset = longAt(bb, z64Rel + 48)
            appendLine("zip64 eocd    : tail[$z64Rel] cdSize=$cdSize cdOffset=$cdOffset")
        }

        appendLine("cd size       : $cdSize")
        appendLine("cd offset     : $cdOffset")
        appendLine("cd end        : ${cdOffset + cdSize}")

        when {
            cdOffset < 0 || cdSize <= 0 ->
                appendLine("VERDICT       : central directory has no usable offset/size")

            cdOffset + cdSize > source.size ->
                appendLine(
                    "VERDICT       : central directory runs past EOF by " +
                        "${cdOffset + cdSize - source.size} bytes — the file is truncated " +
                        "or size was reported wrong"
                )

            cdSize > (1 shl 26) ->
                appendLine("VERDICT       : central directory implausibly large ($cdSize bytes)")

            else -> {
                val cd = runCatching { source.readFully(cdOffset, cdSize.toInt()) }.getOrNull()
                if (cd == null) {
                    appendLine("VERDICT       : central directory region is unreadable")
                    return@buildString
                }
                var count = 0
                var p = 0
                var badAt = -1
                while (p + CENHDR <= cd.size) {
                    if (intAt(ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN), p) != PK_CENTRAL) {
                        badAt = p
                        break
                    }
                    val nl = u16(cd, p + 28)
                    val el = u16(cd, p + 30)
                    val cl = u16(cd, p + 32)
                    count++
                    p += CENHDR + nl + el + cl
                }
                appendLine("cd entries    : parsed $count")
                if (badAt >= 0) {
                    appendLine("cd walk stop  : bad signature at cd[$badAt]")
                }
                appendLine("VERDICT       : central directory is intact and readable")
            }
        }
    }

    /**
     * Write [probe] plus the failure details to
     * `Download/PayloadDumper/diagnostics/parse-failure-<timestamp>.txt`, falling back to the
     * app-private folder when the public one is not writable yet.
     *
     * The fallback matters: a parse can fail *before* the user has granted storage access, and
     * losing the report in exactly that case would defeat the purpose of writing one.
     *
     * @return the written file, or null when even that is not possible
     */
    fun dump(context: Context, source: PayloadSource?, tag: String, err: Throwable): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "parse-failure-$stamp.txt"

        val candidates = listOfNotNull(
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PayloadDumper/diagnostics",
                )
            }.getOrNull(),
            // Always writable, so a report survives a missing storage permission.
            runCatching { File(context.filesDir, "diagnostics") }.getOrNull(),
        )

        for (dir in candidates) {
            val wrote = runCatching {
                if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                val out = File(dir, name)
                write(out, context, source, tag, err)
                out
            }.getOrNull()
            if (wrote != null) return wrote
        }
        return null
    }

    private fun write(
        out: File,
        context: Context,
        source: PayloadSource?,
        tag: String,
        err: Throwable,
    ) {
        out.bufferedWriter().use { w ->
            w.appendLine("Flux parse failure report")
            w.appendLine("time        : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            w.appendLine("app         : ${appVersion(context)}")
            w.appendLine("android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            w.appendLine("device      : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            w.appendLine("abi         : ${Build.SUPPORTED_ABIS.joinToString()}")
            w.appendLine()
            w.appendLine("stage       : $tag")
            val rt = Runtime.getRuntime()
            w.appendLine("heap max    : ${rt.maxMemory() / (1 shl 20)} MB")
            w.appendLine("heap used   : ${(rt.totalMemory() - rt.freeMemory()) / (1 shl 20)} MB")
            w.appendLine("heap total  : ${rt.totalMemory() / (1 shl 20)} MB")
            w.appendLine()
            w.appendLine("exception")
            w.appendLine("---------")
            w.appendLine(describe(err))
            w.appendLine()
            w.appendLine("stack")
            w.appendLine("-----")
            // Bounded: a report is meant to be screenshotted and read, not to bury the cause
            // under a hundred frames of Compose internals.
            err.stackTraceToString().lineSequence().take(60).forEach { w.appendLine(it) }
            w.appendLine()
            w.appendLine("archive")
            w.appendLine("-------")
            if (source == null) {
                w.appendLine("source      : <never opened>")
            } else {
                w.appendLine(probe(source))
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)

    private fun hex(b: ByteArray, off: Int, len: Int): String {
        if (off < 0 || len <= 0 || off + len > b.size) return "<out of range>"
        val sb = StringBuilder(len * 3)
        for (i in off until off + len) {
            // Mask explicitly: String.format("%02x", byte) is ambiguous for negative bytes and
            // would print ffffff80 instead of 80 for the high half of the range.
            val v = b[i].toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun magicOf(b: ByteArray): String {
        if (b.size < 4) return "<too short: ${b.size} byte(s)>"
        if (b[0] == 'C'.code.toByte() && b[1] == 'r'.code.toByte() &&
            b[2] == 'A'.code.toByte() && b[3] == 'U'.code.toByte()
        ) return "CrAU (Android OTA payload)"

        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return when (val m = bb.getInt(0).toLong() and 0xFFFFFFFFL) {
            PK_LOCAL -> "PK\\x03\\x04 (ZIP local header)"
            PK_CENTRAL -> "PK\\x01\\x02 (ZIP central directory)"
            PK_END -> "PK\\x05\\x06 (ZIP EOCD)"
            0x55AA55AAL -> "0x55AA55AA (Huawei UPDATE.APP)"
            else -> "0x${java.lang.Long.toHexString(m)} (unrecognised)"
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun shortAt(bb: ByteBuffer, off: Int): Int = bb.getShort(off).toInt() and 0xFFFF
    private fun intAt(bb: ByteBuffer, off: Int): Long = bb.getInt(off).toLong() and 0xFFFFFFFFL
    private fun longAt(bb: ByteBuffer, off: Int): Long = bb.getLong(off)

    private fun firstBackwards(hay: ByteArray, fromExclusive: Int, sig: Long): Int =
        scanBackwards(hay, fromExclusive, sig, 1).firstOrNull() ?: -1

    /** Positions of a 4-byte little-endian signature, searched backwards from [fromExclusive]. */
    private fun scanBackwards(hay: ByteArray, fromExclusive: Int, sig: Long, limit: Int): List<Int> {
        val b0 = (sig and 0xFF).toByte()
        val b1 = ((sig ushr 8) and 0xFF).toByte()
        val b2 = ((sig ushr 16) and 0xFF).toByte()
        val b3 = ((sig ushr 24) and 0xFF).toByte()
        val out = ArrayList<Int>(limit)
        var bound = fromExclusive
        while (out.size < limit) {
            var found = -1
            var i = bound - 4
            while (i >= 0) {
                if (hay[i] == b0 && hay[i + 1] == b1 && hay[i + 2] == b2 && hay[i + 3] == b3) {
                    found = i
                    break
                }
                i--
            }
            if (found < 0) break
            out.add(found)
            bound = found
        }
        return out
    }
}
