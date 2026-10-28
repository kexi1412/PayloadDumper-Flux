package com.flux.payload.dumper.core

import com.flux.payload.dumper.model.Payload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** Bind operation indices to the exact manifest, partition and effective block size. */
fun payloadResumeIdentity(payload: Payload, partitionName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("Flux payload resume v2\u0000".toByteArray(Charsets.UTF_8))
    val manifest = payload.manifest.toByteArray()
    val name = partitionName.toByteArray(Charsets.UTF_8)
    digest.update(ByteBuffer.allocate(4).putInt(manifest.size).array())
    digest.update(manifest)
    digest.update(ByteBuffer.allocate(4).putInt(name.size).array())
    digest.update(name)
    digest.update(ByteBuffer.allocate(4).putInt(payload.blockSize).array())
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/**
 * Identity-bound resume journal. Legacy unbound index lists are NEVER resumed.
 * Each fixed-size record has an inverse index and CRC, so incomplete/corrupt
 * records cannot be interpreted as a different completed operation.
 *
 * The extractor forces image writes before mark(); mark then flushes and syncs
 * the journal before publishing the in-memory index. This is ordered persistence,
 * not a guarantee against every filesystem, storage-controller or power failure.
 */
class ResumeState(
    private val sidecar: File,
    private val identity: String,
    private val operationCount: Int,
) {
    private val done = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private var imageSize: Long? = null

    init {
        require(identity.isNotBlank() && identity.toByteArray(Charsets.UTF_8).size <= 1024)
        require(operationCount in 0..MAX_OPERATIONS)
    }

    internal fun requireIdentity(expected: String, expectedOperations: Int) {
        if (identity != expected || operationCount != expectedOperations) {
            throw IOException("Resume state belongs to a different firmware or operation layout")
        }
    }

    /** Must run before opening/truncating the output, or a missing image could appear valid. */
    fun load(outputFile: File, expectedSize: Long) = synchronized(lock) {
        require(expectedSize >= 0)
        checkInterrupted()
        done.clear()
        imageSize = expectedSize
        val candidate = HashSet<Int>()
        val valid = if (!outputFile.isFile || outputFile.length() != expectedSize || !sidecar.isFile ||
            sidecar.length() > MAX_HEADER_BYTES + operationCount.toLong() * RECORD_BYTES) {
            false
        } else {
            try {
                DataInputStream(sidecar.inputStream().buffered()).use { input ->
                    if (input.readLong() != MAGIC || input.readUTF() != identity ||
                        input.readInt() != operationCount || input.readLong() != expectedSize) {
                        false
                    } else {
                        while (input.available() > 0) {
                            checkInterrupted()
                            val index = input.readInt()
                            val inverse = input.readInt()
                            val crc = input.readInt()
                            if (index !in 0 until operationCount || inverse != index.inv() || crc != recordCrc(index))
                                throw IOException("Invalid resume journal record")
                            candidate.add(index)
                        }
                        true
                    }
                }
            } catch (_: IOException) {
                checkInterrupted()
                false
            }
        }
        if (valid) done.addAll(candidate) else writeHeader(expectedSize)
    }

    fun isDone(index: Int): Boolean = index in 0 until operationCount && done.contains(index)

    fun completedIndices(): Set<Int> = done.toSet()

    /** Caller must have forced all bytes of this operation to the image first. */
    fun mark(index: Int) = synchronized(lock) {
        require(index in 0 until operationCount)
        checkInterrupted()
        check(imageSize != null) { "ResumeState.load(outputFile, expectedSize) must run before marking" }
        if (!done.contains(index)) {
            FileOutputStream(sidecar, true).use { file ->
                val out = DataOutputStream(file)
                out.writeInt(index)
                out.writeInt(index.inv())
                out.writeInt(recordCrc(index))
                out.flush()
                file.fd.sync()
            }
            done.add(index)
        }
    }

    /** Keep only an empty identity header; no old completed indices can be loaded on retry. */
    fun clear() = synchronized(lock) {
        checkInterrupted()
        done.clear()
        val size = imageSize
        if (size != null) {
            writeHeader(size)
        } else if (sidecar.exists()) {
            // An empty file is intentionally invalid and will be reinitialised by load().
            FileOutputStream(sidecar, false).use { it.fd.sync() }
        }
    }

    private fun writeHeader(size: Long) {
        val parent = sidecar.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create resume directory")
        FileOutputStream(sidecar, false).use { file ->
            val out = DataOutputStream(file)
            out.writeLong(MAGIC)
            out.writeUTF(identity)
            out.writeInt(operationCount)
            out.writeLong(size)
            out.flush()
            file.fd.sync()
        }
    }

    private fun recordCrc(index: Int): Int = CRC32().apply {
        update(ByteBuffer.allocate(8).putInt(index).putInt(index.inv()).array())
    }.value.toInt()

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Resume journal operation interrupted")
    }

    companion object {
        const val MAX_OPERATIONS = 1_000_000
        private const val MAGIC = 0x464C555852534D32L // FLUXRSM2
        private const val RECORD_BYTES = 12
        private const val MAX_HEADER_BYTES = 4096L
    }
}
