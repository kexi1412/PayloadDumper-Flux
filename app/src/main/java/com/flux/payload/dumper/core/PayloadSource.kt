package com.flux.payload.dumper.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
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
}

/**
 * File-backed source. Uses positional [FileChannel.read], which does not touch the channel's
 * own position and is safe to call from multiple threads simultaneously.
 */
class FileSource(private val file: File) : PayloadSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val size: Long = channel.size()
    override fun fileName(): String = file.name

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
) : PayloadSource {

    override fun fileName(): String = displayName

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        val bb = ByteBuffer.wrap(buffer, bufOffset, len)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) throw EOFException("Unexpected EOF at $pos (wanted ${offset + len})")
            pos += n
        }
    }

    // AutoCloseInputStream.close() closes both the FileChannel and the underlying descriptor.
    override fun close() { runCatching { stream.close() } }

    companion object {
        fun open(context: Context, uri: Uri): UriSource {
            val resolver = context.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open document: $uri")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val channel = stream.channel
            val size = if (pfd.statSize >= 0) pfd.statSize else channel.size()
            if (size <= 0L) { runCatching { stream.close() }; throw IOException("Empty or non-seekable document: $uri") }
            val name = queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                ?: "payload.bin"
            return UriSource(stream, channel, size, name)
        }

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
