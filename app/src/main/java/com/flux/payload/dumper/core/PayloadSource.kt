package com.flux.payload.dumper.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * A random-access, **stateless** byte source.
 *
 * Every read specifies its own absolute [offset], so there is no shared cursor. This is the
 * single most important design decision of the engine: it is what makes concurrent extraction
 * from many worker coroutines correct. It mirrors `source_interface.h` in payload-dumper-c
 * (clone / read-at-offset / destroy), where each worker owned an independent read handle.
 */
interface PayloadSource : Closeable {
    val size: Long
    fun fileName(): String

    /** Read [len] bytes starting at [offset] into a freshly allocated array. Blocks until full. */
    fun readFully(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        readFully(offset, buf, 0, len)
        return buf
    }

    /** Read exactly [len] bytes at [offset] into [buffer] at [bufOffset]. Blocks until full. */
    fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int)

    /**
     * Cheap identity used to decide whether a cached parse of this source is still valid.
     *
     * Must be fast — it is consulted on every open, and a full-content hash would defeat the
     * purpose by reading the whole file. Name, length and last-modified time are what real
     * caches use: they change whenever the file is replaced or edited, which is exactly the
     * condition under which a cached directory would be wrong.
     *
     * Sources that cannot supply a modification time fall back to size plus a stable handle
     * description, which is conservative: it may miss a same-size in-place edit, but the
     * digest stored alongside the cache still rejects any window that does not match.
     */
    fun identity(): String = "${fileName()}:$size"
}

/**
 * File-backed source. Uses positional [FileChannel.read], which does not touch the channel's
 * own position and is safe to call from multiple threads simultaneously.
 */
class FileSource(private val file: File) : PayloadSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val size: Long = channel.size()
    override fun fileName(): String = file.name

    /** Adds last-modified time to the default identity, so an in-place edit invalidates the cache. */
    override fun identity(): String = "${file.name}:${file.length()}:${file.lastModified()}"

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        val bb = ByteBuffer.wrap(buffer, bufOffset, len)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw EOFException("Unexpected EOF at $pos (wanted ${offset + len})")
            pos += n
        }
    }

    override fun close() = channel.close()
}

/**
 * SAF (content://) document source. Opens the picked document once as a random-access
 * [ParcelFileDescriptor] and reads through positional [FileChannel.read], exactly like [FileSource]
 * — so concurrent extraction works and no `MANAGE_EXTERNAL_STORAGE` real-path resolution is needed.
 *
 * This is what makes an [android.content.Intent.ACTION_OPEN_DOCUMENT] pick "just work" on every
 * device: the URI is read directly instead of being fragile-mapped back to a `/sdcard` path
 * (the old GetContent + realPathFromUri flow only handled `primary:` document IDs).
 */
class UriSource private constructor(
    private val stream: ParcelFileDescriptor.AutoCloseInputStream,
    private val channel: FileChannel,
    override val size: Long,
    private val displayName: String,
    /**
     * Precomputed identity string. Built in [open] because deriving it needs a `ContentResolver`,
     * and [open] is the only place a `Context` is available — threading it through here avoids
     * reaching for a global application singleton from inside a data source.
     */
    private val identityKey: String,
) : PayloadSource {

    override fun fileName(): String = displayName

    /**
     * SAF documents need more than a name: the same `displayName` can refer to different files,
     * and the same document ID can be rewritten in place. The document URI is stable per file and
     * the provider's own LAST_MODIFIED is the equivalent of a filesystem mtime, so both go in.
     */
    override fun identity(): String = identityKey

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        if (len == 0) return
        val bb = ByteBuffer.wrap(buffer, bufOffset, len)
        var pos = offset
        var stalls = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw EOFException("Unexpected EOF at $pos (wanted ${offset + len})")
            if (n == 0) {
                // A seekable fd should never report 0 while bytes remain. Some SAF providers
                // hand back a pipe-like descriptor where positional reads make no progress;
                // without this guard `pos` never advances and the reader spins forever.
                if (++stalls > MAX_STALLS) {
                    throw IOException(
                        "Stalled reading $len byte(s) at $pos — the document provider does not " +
                            "support random access (read ${len - bb.remaining()} of $len)"
                    )
                }
                continue
            }
            stalls = 0
            pos += n
        }
    }

    // AutoCloseInputStream.close() closes both the FileChannel and the underlying descriptor.
    override fun close() { runCatching { stream.close() } }

    companion object {
        /** Positional reads that make no progress before the source is declared unusable. */
        private const val MAX_STALLS = 8

        fun open(context: Context, uri: Uri): UriSource {
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open document: $uri")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val channel = stream.channel

            // Prefer the descriptor's own length over the provider-reported statSize.
            //
            // Everything downstream is positional: the EOCD search reads the final 1 MB, and
            // every central-directory offset is validated against `size`. If statSize is wrong
            // — and providers do report a stale, truncated, or cached length for multi-gigabyte
            // files — the tail window lands in the wrong place, no EOCD is found, and the whole
            // parse fails with a message that looks like file corruption. `channel.size()` comes
            // from the descriptor itself, so it is the authoritative value. statSize is kept
            // only as a fallback for non-seekable descriptors that report 0.
            val channelSize = runCatching { channel.size() }.getOrDefault(-1L)
            val statSize = pfd.statSize
            val size = if (channelSize > 0L) channelSize else statSize

            if (size <= 0L) {
                runCatching { stream.close() }
                throw IOException("Empty or non-seekable document: $uri")
            }
            val name = queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                ?: "payload.bin"
            // Document URI (stable per file) plus the provider's own mtime, so rewriting a
            // document in place invalidates any cache keyed on it. 0 when the provider does not
            // expose LAST_MODIFIED; the cached payload digest remains the backstop.
            val modified = queryLastModified(context, uri)
            val identity = "uri:$uri:$size:$modified"
            return UriSource(stream, channel, size, name, identity)
        }

        private fun queryLastModified(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null,
                )
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
                ?: 0L
        }.getOrDefault(0L)

        private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
    }
}

/**
 * HTTP-backed source. Each [readFully] issues its own `Range` request, so reads are independent
 * and can run concurrently over OkHttp's connection pool — replacing the old single-cursor
 * `HttpUtil` singleton that made parallel network extraction impossible.
 */
class HttpSource private constructor(
    private val url: String,
    private val client: OkHttpClient,
    override val size: Long,
    private val fileName: String,
) : PayloadSource {

    override fun fileName(): String = fileName

    /**
     * HTTP identity is the URL plus length. A URL normally serves immutable content, but if it is
     * republished the size usually changes; the stored digest remains the backstop against a
     * same-size change, since a mismatched window is rejected rather than parsed.
     */
    override fun identity(): String = "http:$url:$size"

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        if (len == 0) return
        val end = offset + len - 1
        val req = Request.Builder().url(url).header("Range", "bytes=$offset-$end").build()
        var attempt = 0
        while (true) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for range $offset-$end")
                    val stream = resp.body?.byteStream() ?: throw IOException("Empty body")
                    var read = 0
                    while (read < len) {
                        val n = stream.read(buffer, bufOffset + read, len - read)
                        if (n < 0) throw EOFException("Short read: got $read of $len at $offset")
                        read += n
                    }
                }
                return
            } catch (e: IOException) {
                if (++attempt >= MAX_RETRIES) throw e
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
    }

    override fun close() { /* OkHttp responses are closed per-request */ }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 400L

        /** Probe total size + filename with a 1-byte ranged request, then build the source. */
        fun open(url: String, client: OkHttpClient): HttpSource {
            val req = Request.Builder().url(url).header("Range", "bytes=0-0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to open URL: HTTP ${resp.code}")
                val contentRange = resp.header("Content-Range")
                    ?: throw IOException("Server does not support range requests")
                val total = contentRange.substringAfter('/', "").toLongOrNull()
                    ?: throw IOException("Cannot determine remote file size")
                val name = fileNameFromHeaders(resp.header("Content-Disposition"), url)
                return HttpSource(url, client, total, name)
            }
        }

        private fun fileNameFromHeaders(disposition: String?, url: String): String {
            if (!disposition.isNullOrEmpty()) {
                disposition.split(";").forEach { part ->
                    val t = part.trim()
                    if (t.startsWith("filename=")) {
                        return t.substringAfter('=').trim('"', ' ')
                    }
                }
            }
            return runCatching {
                URI(url).path.substringAfterLast('/').ifEmpty { "payload.bin" }
            }.getOrDefault("payload.bin")
        }
    }
}
