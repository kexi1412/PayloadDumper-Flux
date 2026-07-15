package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata.InstallOperation
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.flux.payload.dumper.model.Payload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Extracts partitions from a payload with genuine parallelism.
 *
 * Contrast with the old Compose implementation, whose global `Mutex` serialised every operation
 * and whose singleton HTTP cursor made concurrency impossible. Here each operation is an
 * independent task: it reads its blob at an absolute offset (safe concurrent reads via
 * [PayloadSource]) and writes the result at absolute file positions (safe concurrent writes via
 * [FileChannel]). Up to [workers] operations run at once. This mirrors the thread-pool model of
 * payload-dumper-c, including scattering decompressed data across every `dst_extent`.
 */
class PayloadExtractor(private val workers: Int = defaultWorkers()) {

    data class Result(
        val outputFile: File,
        /** null = not requested/possible, true = hash matched, false = mismatch. */
        val verified: Boolean?,
    )

    suspend fun extractPartition(
        payload: Payload,
        source: PayloadSource,
        partition: PartitionUpdate,
        outputDir: File,
        verify: Boolean,
        resume: ResumeState,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        if (!outputDir.exists()) outputDir.mkdirs()
        val outFile = File(outputDir, "${partition.partitionName}.img")
        val blockSize = payload.blockSize.toLong()
        val ops = partition.operationsList

        val allocSize = ops.asSequence()
            .flatMap { it.dstExtentsList.asSequence() }
            .maxOfOrNull { (it.startBlock + it.numBlocks) * blockSize } ?: 0L
        val totalForProgress = allocSize.coerceAtLeast(1L)

        // Bytes already written by a previous (interrupted) attempt — seeds the progress bar.
        val initialDone = ops.withIndex()
            .filter { resume.isDone(it.index) }
            .sumOf { opBytes(it.value, blockSize) }

        RandomAccessFile(outFile, "rw").use { raf ->
            if (raf.length() != allocSize) raf.setLength(allocSize) // keep partial data on resume
            val channel = raf.channel
            val done = AtomicLong(initialDone)
            val sem = Semaphore(workers)
            onProgress((initialDone.toFloat() / totalForProgress).coerceIn(0f, 1f))

            coroutineScope {
                ops.mapIndexed { index, op ->
                    async {
                        if (resume.isDone(index)) return@async
                        sem.withPermit {
                            val bytes = processOperation(payload, source, partition.partitionName, op, channel, blockSize)
                            resume.mark(index)
                            val soFar = done.addAndGet(bytes)
                            onProgress((soFar.toFloat() / totalForProgress).coerceIn(0f, 1f))
                        }
                    }
                }.awaitAll()
            }
            channel.force(true)
        }

        // Reaching here means every op finished — verify, then drop the resume sidecar.
        val verified = if (verify && partition.hasNewPartitionInfo() && partition.newPartitionInfo.hash.size() > 0) {
            verifySha256(outFile, partition.newPartitionInfo.size, partition.newPartitionInfo.hash.toByteArray())
        } else null

        resume.clear()
        onProgress(1f)
        Result(outFile, verified)
    }

    private fun opBytes(op: InstallOperation, blockSize: Long): Long =
        op.dstExtentsList.sumOf { it.numBlocks } * blockSize

    private fun processOperation(
        payload: Payload,
        source: PayloadSource,
        partitionName: String,
        op: InstallOperation,
        channel: FileChannel,
        blockSize: Long,
    ): Long {
        // ZERO / DISCARD carry no data — just clear the destination blocks.
        if (op.type == InstallOperation.Type.ZERO || op.type == InstallOperation.Type.DISCARD) {
            var total = 0L
            for (ext in op.dstExtentsList) {
                val len = ext.numBlocks * blockSize
                writeZeros(channel, ext.startBlock * blockSize, len)
                total += len
            }
            return total
        }

        val expected = (op.dstExtentsList.sumOf { it.numBlocks } * blockSize)
        val blob = source.readFully(payload.dataOffset + op.dataOffset, op.dataLength.toInt())

        val decoded: ByteArray = when (op.type) {
            InstallOperation.Type.REPLACE -> Decompressor.replace(blob)
            InstallOperation.Type.REPLACE_BZ -> Decompressor.bz2(blob)
            InstallOperation.Type.REPLACE_XZ -> Decompressor.xz(blob)
            InstallOperation.Type.SOURCE_COPY,
            InstallOperation.Type.SOURCE_BSDIFF,
            InstallOperation.Type.BROTLI_BSDIFF,
            InstallOperation.Type.PUFFDIFF,
            InstallOperation.Type.ZUCCHINI,
            InstallOperation.Type.LZ4DIFF_BSDIFF,
            InstallOperation.Type.LZ4DIFF_PUFFDIFF,
            InstallOperation.Type.BSDIFF,
            InstallOperation.Type.MOVE ->
                throw IOException("Partition '$partitionName' needs incremental (delta) op ${op.type}; only full OTA packages are supported")
            else -> Decompressor.sniffAndDecode(blob, expected.toInt())
        }

        // Scatter the decoded bytes across every destination extent, in order.
        var srcPos = 0
        var total = 0L
        for (ext in op.dstExtentsList) {
            if (srcPos >= decoded.size) break
            val len = minOf((ext.numBlocks * blockSize).toInt(), decoded.size - srcPos)
            writeAt(channel, ext.startBlock * blockSize, decoded, srcPos, len)
            srcPos += len
            total += len
        }
        return total
    }

    private fun writeAt(channel: FileChannel, position: Long, data: ByteArray, offset: Int, len: Int) {
        val bb = ByteBuffer.wrap(data, offset, len)
        var pos = position
        while (bb.hasRemaining()) pos += channel.write(bb, pos)
    }

    private fun writeZeros(channel: FileChannel, position: Long, length: Long) {
        val chunk = ByteArray(minOf(length, ZERO_CHUNK).toInt())
        var remaining = length
        var pos = position
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            val bb = ByteBuffer.wrap(chunk, 0, n)
            while (bb.hasRemaining()) pos += channel.write(bb, pos)
            remaining -= n
        }
    }

    private fun verifySha256(file: File, size: Long, expected: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                if (n < 0) break
                digest.update(buf, 0, n)
                remaining -= n
            }
        }
        return digest.digest().contentEquals(expected)
    }

    companion object {
        private const val ZERO_CHUNK = 1L shl 20
        fun defaultWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
