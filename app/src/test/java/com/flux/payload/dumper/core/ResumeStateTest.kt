package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PayloadHeader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

class ResumeStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun image(name: String = "system.img", size: Long = 16): File = temporary.newFile(name).also {
        RandomAccessFile(it, "rw").use { file -> file.setLength(size) }
    }
    private fun journal(name: String = "system.img.progress") = File(temporary.root, name)

    @Test fun legacyIndexListIsDiscardedAndHeaderInitialized() {
        val output = image()
        val sidecar = journal().also { it.writeText("0\n1\n") }
        val state = ResumeState(sidecar, "firmware-A", 3)
        state.load(output, 16)
        assertTrue(state.completedIndices().isEmpty())
        state.mark(2)
        val restored = ResumeState(sidecar, "firmware-A", 3)
        restored.load(output, 16)
        assertEquals(setOf(2), restored.completedIndices())
    }

    @Test fun differentIdentityNeverRestoresIndices() {
        val output = image()
        val sidecar = journal()
        ResumeState(sidecar, "firmware-A", 3).apply { load(output, 16); mark(1) }
        val other = ResumeState(sidecar, "firmware-B", 3)
        other.load(output, 16)
        assertFalse(other.isDone(1))
        val old = ResumeState(sidecar, "firmware-A", 3)
        old.load(output, 16)
        assertTrue(old.completedIndices().isEmpty())
    }

    @Test fun missingImageDoesNotRestoreAndDoesNotCreateImage() {
        val existing = image()
        val sidecar = journal()
        ResumeState(sidecar, "same", 2).apply { load(existing, 16); mark(0) }
        val missing = File(temporary.root, "missing.img")
        val state = ResumeState(sidecar, "same", 2)
        state.load(missing, 16)
        assertFalse(missing.exists())
        assertTrue(state.completedIndices().isEmpty())
    }

    @Test fun truncatedAndOversizedImageDiscardIndices() {
        for (size in listOf(8L, 17L)) {
            val output = image("image-$size.img")
            val sidecar = journal("image-$size.progress")
            ResumeState(sidecar, "same", 2).apply { load(output, 16); mark(0) }
            RandomAccessFile(output, "rw").use { it.setLength(size) }
            val state = ResumeState(sidecar, "same", 2)
            state.load(output, 16)
            assertTrue(state.completedIndices().isEmpty())
            assertEquals(size, output.length())
        }
    }

    @Test fun exactIdentitySizeAndCountRestore() {
        val output = image()
        val sidecar = journal()
        val state = ResumeState(sidecar, "same", 3)
        state.load(output, 16)
        state.mark(0); state.mark(2); state.mark(2)
        val restored = ResumeState(sidecar, "same", 3)
        restored.load(output, 16)
        assertEquals(setOf(0, 2), restored.completedIndices())
        assertFalse(restored.isDone(-1)); assertFalse(restored.isDone(3))
        assertThrows(IllegalArgumentException::class.java) { restored.mark(3) }
        assertThrows(IllegalArgumentException::class.java) { restored.mark(-1) }
    }

    @Test fun countChangeAndOutOfRangeRecordAreNeverResumed() {
        val output = image()
        val sidecar = journal()
        ResumeState(sidecar, "same", 3).apply { load(output, 16); mark(2) }
        val changed = ResumeState(sidecar, "same", 2)
        changed.load(output, 16)
        assertTrue(changed.completedIndices().isEmpty())
        // A structurally valid but out-of-range record must not introduce a done index.
        val index = 2
        val record = ByteBuffer.allocate(8).putInt(index).putInt(index.inv()).array()
        DataOutputStream(sidecar.outputStream().buffered()).use {
            it.writeLong(0x464C555852534D32L); it.writeUTF("same"); it.writeInt(2); it.writeLong(16)
            it.writeInt(index); it.writeInt(index.inv()); it.writeInt(CRC32().apply { update(record) }.value.toInt())
        }
        changed.load(output, 16)
        assertTrue(changed.completedIndices().isEmpty())
    }

    @Test fun persistenceFailureDoesNotPublishDone() {
        val output = image()
        val sidecar = journal()
        val state = ResumeState(sidecar, "same", 2)
        state.load(output, 16)
        // Replace only this test's own sidecar by a directory to make append fail on every OS.
        assertTrue(sidecar.delete()); assertTrue(sidecar.mkdir())
        assertThrows(IOException::class.java) { state.mark(0) }
        assertFalse(state.isDone(0))
    }

    @Test fun clearOnlyChangesOwnJournal() {
        val output = image()
        val sidecar = journal()
        val unrelated = temporary.newFile("other.progress").also { it.writeText("keep") }
        val bytes = output.readBytes()
        val state = ResumeState(sidecar, "same", 2).apply { load(output, 16); mark(0) }
        state.clear()
        assertEquals("keep", unrelated.readText())
        assertArrayEquals(bytes, output.readBytes())
        val restored = ResumeState(sidecar, "same", 2).apply { load(output, 16) }
        assertTrue(restored.completedIndices().isEmpty())
    }

    @Test fun identityBindsWholeManifestPartitionAndBlockSizeNotFileName() {
        fun op(offset: Long) = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE).setDataOffset(offset).build()
        fun payload(ops: List<UpdateMetadata.InstallOperation>): Payload {
            val partition = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("system").addAllOperations(ops).build()
            val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4).addPartitions(partition).build()
            return Payload("same.bin", PayloadHeader(2, manifest.serializedSize.toLong(), 0), manifest, 0, 4, 16)
        }
        val original = payload(listOf(op(0), op(4)))
        val identity = payloadResumeIdentity(original, "system")
        assertEquals(64, identity.length)
        assertEquals(identity, payloadResumeIdentity(original.copy(fileName = "new-link.bin"), "system"))
        assertNotEquals(identity, payloadResumeIdentity(payload(listOf(op(4), op(0))), "system"))
        assertNotEquals(identity, payloadResumeIdentity(original, "vendor"))
        assertNotEquals(identity, payloadResumeIdentity(original.copy(blockSize = 8), "system"))
        assertNotEquals(identity, payloadResumeIdentity(original.copy(manifest = original.manifest.toBuilder().setMaxTimestamp(42).build()), "system"))
    }
}
