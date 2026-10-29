package com.flux.payload.dumper.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Desktop storage adapter; the directory cache wire format matches Android FLUXCD01. */
internal object NestedDirCache {
    class Cached(val bytes: ByteArray, val totalOut: Long)
    private const val MAGIC = "FLUXCD01"
    private const val MAX_ENTRY = 8 * 1024 * 1024
    private fun root() = File(System.getProperty("flux.cacheDir")
        ?: File(System.getProperty("user.home"), ".payload-dumper-flux/cache").path, "nested-cd").canonicalFile
    private fun fileFor(source: PayloadSource, entry: ZipEntryInfo, dataOffset: Long): File {
        val identity = "${source.identity()}|${entry.name}|$dataOffset|${entry.compressedSize}|${entry.uncompressedSize}|${entry.method}"
        val hex = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
        val directory = root()
        return File(directory, "$hex.cd").canonicalFile.also { require(it.parentFile == directory) }
    }
    fun load(source: PayloadSource, entry: ZipEntryInfo, dataOffset: Long): Cached? = runCatching {
        val file = fileFor(source, entry, dataOffset)
        if (!file.isFile || file.length() < 56 || file.length() > MAX_ENTRY + 56L) return@runCatching null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            if (String(magic, Charsets.US_ASCII) != MAGIC || input.readInt() != 1) return@runCatching null
            val total = input.readLong()
            val length = input.readInt()
            if (length !in 1..MAX_ENTRY || total < length || file.length() != length + 56L) return@runCatching null
            val digest = ByteArray(32).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(digest)) return@runCatching null
            Cached(data, total)
        }
    }.getOrNull()
    fun store(source: PayloadSource, entry: ZipEntryInfo, dataOffset: Long, bytes: ByteArray, off: Int, len: Int, totalOut: Long) {
        runCatching {
            if (len !in 1..MAX_ENTRY || off < 0 || off > bytes.size - len || totalOut < len) return@runCatching
            val destination = fileFor(source, entry, dataOffset)
            val directory = destination.parentFile
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
            val temporary = Files.createTempFile(directory.toPath(), destination.name, ".tmp")
            try {
                val data = bytes.copyOfRange(off, off + len)
                DataOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
                    output.write(MAGIC.toByteArray(Charsets.US_ASCII)); output.writeInt(1)
                    output.writeLong(totalOut); output.writeInt(data.size)
                    output.write(MessageDigest.getInstance("SHA-256").digest(data)); output.write(data)
                }
                require(temporary.parent == directory.toPath() && destination.canonicalFile.parentFile == directory)
                try { Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally {
                if (temporary.parent == directory.toPath()) Files.deleteIfExists(temporary)
            }
        }
    }
}
