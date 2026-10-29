package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger

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
 * HTTP-backed source. Each [readFully] issues its own `Range` request, so reads are independent
 * and can run concurrently over OkHttp's connection pool — replacing the old single-cursor
 * `HttpUtil` singleton that made parallel network extraction impossible.
 */
class HttpSource private constructor(
    private val url: String,
    private val client: OkHttpClient,
    override val size: Long,
    private val fileName: String,
    private val deadlineNanos: Long? = null,
) : PayloadSource {

    private val preferredChunkBytes = AtomicInteger(CHUNK_BYTES)
    private val activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Small metadata requests must not inherit the extraction client's long idle timeout. */
    internal fun forParsing(timeoutMillis: Long = 30_000): HttpSource = forkForSmallReads(timeoutMillis)

    /** Separate call ownership, not another size probe or another extraction connection pool. */
    internal fun forMetadata(timeoutMillis: Long = 30_000): HttpSource = forkForSmallReads(timeoutMillis)

    private fun forkForSmallReads(timeoutMillis: Long): HttpSource {
        require(timeoutMillis in 1..86_400_000L)
        checkReadAllowed()
        return HttpSource(url, smallReadClient(client), size, fileName,
            System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)).also {
            it.preferredChunkBytes.set(preferredChunkBytes.get())
        }
    }

    private fun checkReadAllowed() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("HTTP archive read interrupted")
        if (closed.get()) throw IOException("HTTP archive source closed")
        if (deadlineNanos != null && deadlineNanos - System.nanoTime() <= 0) {
            throw java.net.SocketTimeoutException("Firmware metadata time budget exceeded")
        }
    }

    private fun pauseBeforeRetry(attempt: Int) {
        checkReadAllowed()
        val delay = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(RETRY_DELAY_MS * attempt)
        val remaining = deadlineNanos?.let { it - System.nanoTime() } ?: delay
        if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(minOf(delay, remaining))
        checkReadAllowed()
    }

    private inline fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T {
        checkReadAllowed()
        val call = client.newCall(request)
        deadlineNanos?.let { deadline ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException("Firmware metadata time budget exceeded")
            val configured = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMillis.toLong())
            call.timeout().timeout(if (configured > 0) minOf(configured, remaining) else remaining,
                java.util.concurrent.TimeUnit.NANOSECONDS)
        }
        activeCalls.add(call)
        try {
            // close() may have raced with registration; never leave that call running unowned.
            checkReadAllowed()
            return call.execute().use { read(it) }
        } finally {
            activeCalls.remove(call)
        }
    }

    override fun fileName(): String = fileName

    /**
     * HTTP identity is the URL plus length. A URL normally serves immutable content, but if it is
     * republished the size usually changes; the stored digest remains the backstop against a
     * same-size change, since a mismatched window is rejected rather than parsed.
     */
    override fun identity(): String = "http:$url:$size"

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        require(offset >= 0 && len >= 0 && offset <= size - len) { "Read outside remote archive" }
        require(bufOffset >= 0 && bufOffset <= buffer.size - len) { "Read outside destination buffer" }
        if (len == 0) return
        var pos = offset
        var left = len
        var emptyAttempts = 0
        var transportAttempts = 0
        var chunkLimit = preferredChunkBytes.get()
        // Some firmware CDNs close a Range response early while still advertising the full
        // Content-Range/Content-Length. Preserve a proven identity prefix and continue at the
        // first missing byte. If a response yields no bytes, fall back quickly to a small range
        // and remember that limit for every later read from this source; otherwise every boot
        // operation would repeat the same multi-minute failure ladder.
        while (left > 0) {
            checkReadAllowed()
            chunkLimit = minOf(chunkLimit, preferredChunkBytes.get())
            val chunk = minOf(left, chunkLimit)
            val end = pos + chunk - 1
            val req = Request.Builder().url(url)
                .header("Range", "bytes=$pos-$end")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-transform")
                .build()
            try {
                var read = 0
                execute(req) { resp ->
                    validateRange(resp, pos, end, size)
                    val body = resp.body ?: throw IOException("Empty body")
                    val decoded = decodeRangeBody(body, resp.header("Content-Encoding"), chunk.toLong())
                    if (!decoded.encoded && body.contentLength() > chunk.toLong()) {
                        throw java.net.ProtocolException("HTTP range body exceeds requested length")
                    }
                    read = readExact(
                        decoded.stream,
                        buffer,
                        bufOffset + (pos - offset).toInt(),
                        chunk,
                        pos,
                        resumable = !decoded.encoded,
                    )
                    if (decoded.encoded && decoded.stream.read() != -1) {
                        throw java.net.ProtocolException(
                            "Compressed HTTP range decoded beyond the requested $chunk bytes at $pos"
                        )
                    }
                }
                pos += read
                left -= read
                emptyAttempts = 0
                transportAttempts = 0
            } catch (e: ShortReadException) {
                checkReadAllowed()
                val got = if (e.resumable) e.read else 0
                if (got > 0) {
                    // A validated identity Range is ordered bytes beginning exactly at `pos`.
                    // Keep that prefix, then request only the missing suffix.
                    pos += got
                    left -= got
                    if (chunk > MIN_CHUNK_BYTES) {
                        chunkLimit = lowerChunkLimit(maxOf(MIN_CHUNK_BYTES, minOf(chunk, got)))
                    }
                    emptyAttempts = 0
                    transportAttempts = 0
                } else {
                    if (++emptyAttempts > MAX_EMPTY_RETRIES) throw e
                    // A failed four-byte magic read says nothing about the CDN's maximum
                    // range size. Only a genuinely large failed request can lower the limit.
                    if (chunk > MIN_CHUNK_BYTES) {
                        val fallback = if (chunk > FALLBACK_CHUNK_BYTES) FALLBACK_CHUNK_BYTES
                            else maxOf(MIN_CHUNK_BYTES, chunk / 2)
                        chunkLimit = lowerChunkLimit(fallback)
                    }
                    pauseBeforeRetry(emptyAttempts)
                }
            } catch (e: IOException) {
                checkReadAllowed()
                // A malformed range cannot be repaired by retrying it as though it were bytes 0..N.
                if (e is java.net.ProtocolException) throw e
                if (++transportAttempts >= MAX_TRANSPORT_RETRIES) throw e
                pauseBeforeRetry(transportAttempts)
            }
        }
    }

    private fun lowerChunkLimit(candidate: Int): Int {
        val bounded = candidate.coerceIn(MIN_CHUNK_BYTES, CHUNK_BYTES)
        while (true) {
            val current = preferredChunkBytes.get()
            if (current <= bounded) return current
            if (preferredChunkBytes.compareAndSet(current, bounded)) return bounded
        }
    }

    override fun close() {
        closed.set(true)
        activeCalls.forEach { it.cancel() }
    }

    companion object {
        private const val MAX_TRANSPORT_RETRIES = 3
        private const val MAX_EMPTY_RETRIES = 4
        private const val RETRY_DELAY_MS = 400L
        private const val CHUNK_BYTES = 256 * 1024
        private const val FALLBACK_CHUNK_BYTES = 32 * 1024
        private const val MIN_CHUNK_BYTES = 16 * 1024

        private class ShortReadException(
            val read: Int,
            val resumable: Boolean,
            message: String,
            cause: Throwable? = null,
        ) : IOException(message, cause)

        private const val SMALL_READ_TIMEOUT_MS = 5_000L

        private fun smallReadClient(client: OkHttpClient): OkHttpClient {
            val configured = client.readTimeoutMillis.toLong()
            val idle = if (configured > 0) minOf(configured, SMALL_READ_TIMEOUT_MS) else SMALL_READ_TIMEOUT_MS
            return client.newBuilder().readTimeout(idle, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        }

        /** Probe total size + filename without inheriting an extraction-length stall timeout. */
        fun open(url: String, client: OkHttpClient): HttpSource {
            val req = Request.Builder().url(url)
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-transform")
                .build()
            val probeClient = smallReadClient(client)
            for (attempt in 1..MAX_TRANSPORT_RETRIES) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("HTTP probe interrupted")
                val call = probeClient.newCall(req)
                val configured = client.callTimeoutMillis.toLong()
                call.timeout().timeout(if (configured > 0) minOf(configured, SMALL_READ_TIMEOUT_MS) else SMALL_READ_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS)
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    if (e is java.net.ProtocolException || Thread.currentThread().isInterrupted || attempt == MAX_TRANSPORT_RETRIES) throw e
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                    continue
                }
                response.use { resp ->
                    // Range validation failures are not transport failures: never retry bad bytes.
                    val range = validateRange(resp, 0, 0, null)
                    val name = HttpArchiveHeaders.fileName(resp.header("Content-Disposition"), resp.request.url.toString())
                    // Extraction retains the caller's original timeout policy and connection pool.
                    return HttpSource(resp.request.url.toString(), client, range.total, name)
                }
            }
            throw IOException("HTTP range probe failed")
        }

        private class DecodedBody(val stream: java.io.InputStream, val encoded: Boolean)

        /**
         * Some CDNs ignore `Accept-Encoding: identity` and compress the selected range on the
         * fly. That is still usable: each range is an independent gzip/deflate member, so it
         * inflates back to exactly the requested bytes. Static compression of the whole entity
         * is not usable, and the exact-length checks reject it instead of mixing bytes.
         */
        private fun decodeRangeBody(
            body: okhttp3.ResponseBody,
            encodingHeader: String?,
            expected: Long,
        ): DecodedBody {
            val raw = body.byteStream()
            val tokens = encodingHeader.orEmpty()
                .split(',')
                .map { it.substringBefore(';').trim().lowercase(java.util.Locale.ROOT) }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.all { it == "identity" }) return DecodedBody(raw, false)
            val buffered = java.io.BufferedInputStream(raw, 16 * 1024)
            // Real compression is only gzip or deflate. Anything else (for example a server
            // that wrongly sends "utf-8" in Content-Encoding) is a mislabel: ranges are
            // advertised as identity, so the body is treated as raw bytes and length-checked.
            return when {
                tokens.any { it == "gzip" || it == "x-gzip" } -> when {
                    peek16(buffered) == 0x1f8b -> DecodedBody(java.util.zip.GZIPInputStream(buffered), true)
                    // Mislabeled identity body: accept only when its length proves it is raw.
                    body.contentLength() == expected -> DecodedBody(buffered, false)
                    else -> throw java.net.ProtocolException(
                        "Server declared gzip for this HTTP range but sent a non-gzip body"
                    )
                }
                tokens.any { it == "deflate" } -> DecodedBody(java.util.zip.InflaterInputStream(buffered), true)
                else -> DecodedBody(buffered, false)
            }
        }

        private fun peek16(input: java.io.BufferedInputStream): Int {
            input.mark(2)
            val first = input.read()
            val second = input.read()
            input.reset()
            if (first < 0 || second < 0) return -1
            return (first shl 8) or second
        }

        private fun readExact(
            stream: java.io.InputStream,
            buffer: ByteArray,
            bufOffset: Int,
            len: Int,
            offset: Long,
            resumable: Boolean,
        ): Int {
            var read = 0
            while (read < len) {
                val n = try {
                    stream.read(buffer, bufOffset + read, len - read)
                } catch (e: IOException) {
                    throw ShortReadException(
                        read,
                        resumable,
                        "Short read: got $read of $len at $offset",
                        e,
                    )
                }
                if (n <= 0) {
                    throw ShortReadException(read, resumable, "Short read: got $read of $len at $offset")
                }
                read += n
            }
            return read
        }

        private fun validateRange(resp: okhttp3.Response, first: Long, last: Long, total: Long?): HttpArchiveHeaders.ByteRange {
            if (resp.code != 206) {
                if (resp.code == 200) throw java.net.ProtocolException("Server ignored HTTP Range; refusing incorrect archive bytes")
                throw IOException("HTTP ${resp.code} for range $first-$last")
            }
            val range = HttpArchiveHeaders.contentRange(resp.header("Content-Range"))
                ?: throw java.net.ProtocolException("Invalid or missing HTTP Content-Range")
            if (range.first != first || range.last != last || (total != null && range.total != total)) {
                throw java.net.ProtocolException("HTTP Content-Range does not match the requested archive bytes")
            }
            return range
        }
    }
}
