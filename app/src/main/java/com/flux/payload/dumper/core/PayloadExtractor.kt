package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata.InstallOperation
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.flux.payload.dumper.model.Payload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Full-OTA extraction with a validated, non-overlapping positional write plan. */
class PayloadExtractor(private val workers: Int = defaultWorkers()) {
    init { require(workers > 0) }

    data class Result(val outputFile: File, val verified: Boolean?)
    private data class Range(val position: Long, val length: Long)
    private data class OperationPlan(
        val operation: InstallOperation,
        val ranges: List<Range>,
        val decodedSize: Long,
        val blobPosition: Long,
        val blobLength: Int,
    )
    private data class Plan(val operations: List<OperationPlan>, val targetSize: Long, val totalBytes: Long)

    suspend fun extractPartition(
        payload: Payload,
        source: PayloadSource,
        partition: PartitionUpdate,
        outputDir: File,
        verify: Boolean,
        resume: ResumeState,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        // Validate EVERY operation before reading a blob, creating an image or trusting indices.
        val plan = runInterruptible { validatePlan(payload, source, partition) }
        resume.requireIdentity(payloadResumeIdentity(payload, partition.partitionName), partition.operationsCount)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("Cannot create output directory")
        val outFile = OutputPaths.child(outputDir, "${partition.partitionName}.img")
        runInterruptible { resume.load(outFile, plan.targetSize) }

        RandomAccessFile(outFile, "rw").use { raf ->
            runInterruptible {
                // A fresh/mismatched resume must not retain old firmware bytes in uncovered holes.
                if (raf.length() != plan.targetSize) resume.clear()
                if (resume.completedIndices().isEmpty()) raf.setLength(0)
                raf.setLength(plan.targetSize)
            }
            val channel = raf.channel
            val initialDone = plan.operations.withIndex().filter { resume.isDone(it.index) }
                .sumOf { it.value.decodedSize }
            val done = AtomicLong(initialDone)
            val next = AtomicInteger(0)
            fun progress(bytes: Long) = (bytes.toDouble() / plan.totalBytes.coerceAtLeast(1L))
                .toFloat().coerceIn(0f, 0.99f)
            onProgress(progress(initialDone))

            // A bounded number of workers, not one coroutine/large allocation per manifest op.
            coroutineScope {
                List(minOf(workers, plan.operations.size)) {
                    launch {
                        while (true) {
                            ensureActive()
                            val index = next.getAndIncrement()
                            if (index >= plan.operations.size) break
                            if (resume.isDone(index)) continue
                            val bytes = runInterruptible {
                                val written = processOperation(source, plan.operations[index], channel)
                                checkInterrupted()
                                // One image force per mark (plus final force), intentionally not free.
                                channel.force(true)
                                resume.mark(index)
                                written
                            }
                            ensureActive()
                            onProgress(progress(done.addAndGet(bytes)))
                        }
                    }
                }.joinAll()
            }
            runInterruptible {
                channel.force(true)
                if (channel.size() != plan.targetSize) throw IOException("Output image length changed during extraction")
            }
        }

        val hash = if (partition.hasNewPartitionInfo()) partition.newPartitionInfo.hash.toByteArray() else byteArrayOf()
        val verified = if (verify && hash.isNotEmpty()) {
            runInterruptible { verifySha256(outFile, plan.targetSize, hash) }
        } else null
        // Hash mismatch returns false, but clears all completed indices so a retry rewrites data.
        runInterruptible { resume.clear() }
        if (verified != false) onProgress(1f)
        Result(outFile, verified)
    }

    private fun validatePlan(payload: Payload, source: PayloadSource, partition: PartitionUpdate): Plan {
        val name = partition.partitionName
        if (!PARTITION_NAME.matches(name)) throw IOException("Invalid partition name: '$name'")
        if (payload.blockSize <= 0) throw IOException("Invalid payload block size")
        val blockSize = payload.blockSize.toLong()
        if (payload.manifest.blockSize != payload.blockSize) throw IOException("Payload block size does not match manifest")
        val matching = payload.manifest.partitionsList.filter { it.partitionName == name }
        if (matching.size != 1 || matching.single() != partition) throw IOException("Partition does not match payload manifest")
        if (partition.operationsCount > ResumeState.MAX_OPERATIONS) throw IOException("Too many partition operations")
        if (source.size < 0 || payload.dataOffset < 0 || payload.dataOffset > source.size) throw IOException("Invalid payload data boundary")

        // minor_version describes update_engine compatibility, not whether this partition
        // can be reconstructed without an old image. Check the WHOLE operation plan first,
        // including source references on otherwise self-contained REPLACE/ZERO operations.
        for ((index, op) in partition.operationsList.withIndex()) {
            checkInterrupted()
            PayloadOperations.unsupportedReason(op)?.let { reason ->
                val type = if (op.hasType()) op.type.name else "unknown"
                throw IOException("Partition '$name' operation[$index] ($type): $reason")
            }
        }

        var allocSize = 0L
        var totalBytes = 0L
        val allRanges = ArrayList<Range>()
        val operations = partition.operationsList.map { op ->
            checkInterrupted()
            if (op.dstExtentsCount == 0) throw IOException("Operation has no destination extents")
            var decodedSize = 0L
            val ranges = op.dstExtentsList.map { ext ->
                if (!ext.hasStartBlock() || !ext.hasNumBlocks() || ext.startBlock < 0 || ext.numBlocks <= 0) {
                    throw IOException("Invalid destination extent")
                }
                val position = multiply(ext.startBlock, blockSize, "extent position")
                val length = multiply(ext.numBlocks, blockSize, "extent length")
                val end = add(position, length, "extent end")
                allocSize = maxOf(allocSize, end)
                decodedSize = add(decodedSize, length, "decoded length")
                Range(position, length).also { allRanges.add(it) }
            }
            totalBytes = add(totalBytes, decodedSize, "total output length")
            if (op.hasDstLength() && op.dstLength != decodedSize) throw IOException("Destination length does not match extents")
            if (op.dataOffset < 0 || op.dataLength < 0 || op.dataLength > MAX_OPERATION_BYTES) throw IOException("Operation blob exceeds 128 MiB or is invalid")
            val blobPosition = add(payload.dataOffset, op.dataOffset, "blob offset")
            val blobEnd = add(blobPosition, op.dataLength, "blob end")
            if (blobPosition > source.size || blobEnd > source.size) throw IOException("Operation blob extends beyond source")
            if (op.dataSha256Hash.size() != 0 && op.dataSha256Hash.size() != 32) throw IOException("Invalid operation SHA-256 length")
            val noBlob = op.type == InstallOperation.Type.ZERO || op.type == InstallOperation.Type.DISCARD
            if (noBlob) {
                if (op.dataLength != 0L) throw IOException("ZERO/DISCARD operation must not carry a blob")
                if (!op.dataSha256Hash.isEmpty && !MessageDigest.getInstance("SHA-256").digest(byteArrayOf())
                        .contentEquals(op.dataSha256Hash.toByteArray())) throw IOException("Empty operation blob SHA-256 mismatch")
            } else {
                if (decodedSize > MAX_OPERATION_BYTES) throw IOException("Operation decoded length exceeds 128 MiB")
                if (op.type == InstallOperation.Type.REPLACE && op.dataLength != decodedSize) throw IOException("REPLACE length does not match destination extents")
            }
            OperationPlan(op, ranges, decodedSize, blobPosition, op.dataLength.toInt())
        }
        val targetSize = if (partition.hasNewPartitionInfo() && partition.newPartitionInfo.hasSize()) {
            partition.newPartitionInfo.size
        } else allocSize
        if (targetSize < 0 || allocSize > targetSize) throw IOException("Destination extents exceed new partition size")
        val hashSize = if (partition.hasNewPartitionInfo()) partition.newPartitionInfo.hash.size() else 0
        if (hashSize != 0 && hashSize != 32) throw IOException("Invalid partition SHA-256 length")
        // Overlapping writes would be order-dependent and invalidate parallel/resumed extraction.
        var previousEnd = 0L
        for (range in allRanges.sortedBy { it.position }) {
            checkInterrupted()
            if (range.position < previousEnd) throw IOException("Overlapping destination extents")
            previousEnd = add(range.position, range.length, "extent end")
        }
        return Plan(operations, targetSize, totalBytes)
    }

    private fun processOperation(source: PayloadSource, plan: OperationPlan, channel: FileChannel): Long {
        checkInterrupted()
        val op = plan.operation
        if (op.type == InstallOperation.Type.ZERO || op.type == InstallOperation.Type.DISCARD) {
            for (range in plan.ranges) writeZeros(channel, range.position, range.length)
            return plan.decodedSize
        }
        val blob = source.readFully(plan.blobPosition, plan.blobLength)
        checkInterrupted()
        if (blob.size != plan.blobLength) throw IOException("Short operation blob read")
        if (!op.dataSha256Hash.isEmpty && !MessageDigest.getInstance("SHA-256").digest(blob)
                .contentEquals(op.dataSha256Hash.toByteArray())) throw IOException("Operation blob SHA-256 mismatch")
        val expected = plan.decodedSize.toInt()
        val decoded = when (op.type) {
            InstallOperation.Type.REPLACE -> blob
            InstallOperation.Type.REPLACE_BZ -> decodeBounded(BZip2CompressorInputStream(ByteArrayInputStream(blob), true), expected)
            InstallOperation.Type.REPLACE_XZ -> decodeBounded(XZCompressorInputStream(ByteArrayInputStream(blob), true, 128 * 1024), expected)
            else -> throw IOException("Unsupported operation ${op.type}")
        }
        if (decoded.size != expected) throw IOException("Decoded length ${decoded.size} does not match destination length $expected")
        // Only scatter a fully decoded, length-checked and hash-checked operation.
        var offset = 0
        for (range in plan.ranges) {
            writeAt(channel, range.position, decoded, offset, range.length.toInt())
            offset += range.length.toInt()
        }
        return plan.decodedSize
    }

    private fun decodeBounded(input: InputStream, expected: Int): ByteArray = input.use {
        val decoded = ByteArray(expected)
        var offset = 0
        while (offset < expected) {
            checkInterrupted()
            val n = it.read(decoded, offset, minOf(IO_CHUNK, expected - offset))
            if (n <= 0) throw IOException("Compressed operation ended before expected decoded length")
            offset += n
        }
        checkInterrupted()
        if (it.read() != -1) throw IOException("Compressed operation exceeds expected decoded length")
        decoded
    }

    private fun writeAt(channel: FileChannel, position: Long, data: ByteArray, offset: Int, len: Int) {
        val buffer = ByteBuffer.wrap(data, offset, len)
        var pos = position
        while (buffer.hasRemaining()) {
            checkInterrupted()
            val n = channel.write(buffer, pos)
            if (n <= 0) throw IOException("Output write made no progress")
            pos += n
        }
    }

    private fun writeZeros(channel: FileChannel, position: Long, length: Long) {
        val chunk = ByteArray(minOf(length, IO_CHUNK.toLong()).toInt())
        var remaining = length
        var pos = position
        while (remaining > 0) {
            checkInterrupted()
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            writeAt(channel, pos, chunk, 0, n)
            remaining -= n
            pos += n
        }
    }

    private fun verifySha256(file: File, size: Long, expected: ByteArray): Boolean {
        if (size < 0 || file.length() != size) throw IOException("Output length does not exactly match verification size")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_CHUNK)
            var remaining = size
            while (remaining > 0) {
                checkInterrupted()
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n <= 0) throw IOException("Output truncated during SHA-256 verification")
                digest.update(buffer, 0, n)
                remaining -= n
            }
            checkInterrupted()
            if (input.read() != -1) throw IOException("Output grew during SHA-256 verification")
        }
        if (file.length() != size) throw IOException("Output length changed during SHA-256 verification")
        return digest.digest().contentEquals(expected)
    }

    private fun add(a: Long, b: Long, label: String): Long {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw IOException("Invalid or overflowing $label")
        return a + b
    }
    private fun multiply(a: Long, b: Long, label: String): Long {
        if (a < 0 || b <= 0 || a > Long.MAX_VALUE / b) throw IOException("Invalid or overflowing $label")
        return a * b
    }
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Extraction interrupted")
    }

    companion object {
        private const val MAX_OPERATION_BYTES = 128L * 1024 * 1024
        private const val IO_CHUNK = 1 shl 16
        private val PARTITION_NAME = Regex("[A-Za-z0-9_-]+")
        fun defaultWorkers(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    }
}
