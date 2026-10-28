package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PayloadExtractorTest {
    @get:Rule val temp = TemporaryFolder()
    private class Memory(private val bytes: ByteArray) : PayloadSource {
        var reads = 0
        override val size = bytes.size.toLong()
        override fun fileName() = "payload.bin"
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            reads++
            if (offset < 0 || len > bytes.size - offset) throw IOException("EOF")
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
        }
        override fun close() {}
    }
    private fun extent(start: Long, blocks: Long = 1) = UpdateMetadata.Extent.newBuilder().setStartBlock(start).setNumBlocks(blocks).build()
    private fun op(type: UpdateMetadata.InstallOperation.Type, length: Long, vararg starts: Long) =
        UpdateMetadata.InstallOperation.newBuilder().setType(type).setDataOffset(0).setDataLength(length)
            .addAllDstExtents(starts.map { extent(it) }).build()
    private fun fixture(operation: UpdateMetadata.InstallOperation, source: Memory, name: String = "boot", target: Long = 8): Pair<Payload, UpdateMetadata.PartitionUpdate> {
        val part = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName(name)
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(target)).addOperations(operation).build()
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4).addPartitions(part).build()
        return Payload("payload.bin", PayloadHeader(2, 1, 0), manifest, 0, 4, source.size) to part
    }
    private fun extract(source: Memory, data: Pair<Payload, UpdateMetadata.PartitionUpdate>, folder: File = temp.newFolder()): PayloadExtractor.Result = runBlocking {
        val (payload, part) = data
        PayloadExtractor(workers = 2).extractPartition(payload, source, part, folder, false,
            ResumeState(File(folder, "boot.img.progress"), payloadResumeIdentity(payload, part.partitionName), part.operationsCount)) {}
    }
    @Test fun scattersAllExtentsInTheirDeclaredOrder() {
        val source = Memory("ABCDEFGH".toByteArray())
        val result = extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.REPLACE, 8, 1, 0), source))
        assertEquals("EFGHABCD", result.outputFile.readText())
    }
    @Test fun shortDecodedReplaceIsRejectedAndNotJournaled() {
        val source = Memory("ABCD".toByteArray())
        val folder = temp.newFolder()
        assertThrows(IOException::class.java) { extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.REPLACE, 4, 0, 1), source), folder) }
        assertFalse(File(folder, "boot.img").exists())
    }
    @Test fun deltaFailsBeforeSourceRead() {
        val source = Memory(ByteArray(8))
        assertThrows(IOException::class.java) { extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.SOURCE_COPY, 0, 0, 1), source)) }
        assertEquals(0, source.reads)
    }
    @Test fun zeroCreatesTheExactTargetWithoutReadingABlob() {
        val source = Memory(byteArrayOf())
        val result = extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.ZERO, 0, 0, 1), source))
        assertArrayEquals(ByteArray(8), result.outputFile.readBytes())
        assertEquals(0, source.reads)
    }
    @Test fun overlappingExtentPlanIsRejectedBeforeWriting() {
        val source = Memory(ByteArray(8))
        assertThrows(IOException::class.java) { extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.REPLACE, 8, 0, 0), source)) }
        assertEquals(0, source.reads)
    }
    @Test fun unsafePartitionNameIsRejected() {
        val source = Memory(ByteArray(8))
        assertThrows(IOException::class.java) { extract(source, fixture(op(UpdateMetadata.InstallOperation.Type.REPLACE, 8, 0, 1), source, "../boot")) }
        assertEquals(0, source.reads)
    }
    @Test fun outputLeafCannotEscapeItsParent() {
        assertEquals("boot.img", OutputPaths.child(temp.root, "../../boot.img").name)
        assertEquals("firmware", OutputPaths.archiveFolder(".."))
        assertEquals("OTA", OutputPaths.archiveFolder("OTA.ZIP"))
    }
}
