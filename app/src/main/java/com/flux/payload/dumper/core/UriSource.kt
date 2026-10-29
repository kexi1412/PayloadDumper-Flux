package com.flux.payload.dumper.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
