package com.flux.payload.dumper.core

import chromeos_update_engine.UpdateMetadata
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadRelinkRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val image = ByteArray(8192) { ((it * 13 + it / 4096) % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun rawPayload(): ByteArray {
        val partition = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("boot")
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(image.size.toLong())
                .setHash(ByteString.copyFrom(sha(image))))
        for (index in 0..1) {
            val data = image.copyOfRange(index * 4096, (index + 1) * 4096)
            partition.addOperations(UpdateMetadata.InstallOperation.newBuilder()
                .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
                .setDataOffset((index * 4096).toLong()).setDataLength(4096)
                .setDataSha256Hash(ByteString.copyFrom(sha(data)))
                .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(index.toLong()).setNumBlocks(1)))
        }
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder().setBlockSize(4096)
            .addPartitions(partition).build().toByteArray()
        return ByteBuffer.allocate(24).put("CrAU".toByteArray()).putLong(2)
            .putLong(manifest.size.toLong()).putInt(0).array() + manifest + image
    }
    private class Memory(private val bytes: ByteArray, private val name: String) : PayloadSource {
        val reads = mutableListOf<Pair<Long, Int>>()
        override val size = bytes.size.toLong()
        override fun fileName() = name
        override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
            reads += offset to len
            bytes.copyInto(buffer, bufOffset, offset.toInt(), offset.toInt() + len)
        }
        override fun close() {}
    }
    private fun zip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED; size = raw.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(raw) }.value
            }
            zip.putNextEntry(entry); zip.write(raw); zip.closeEntry()
        }
        return out.toByteArray()
    }
    @Test fun rawPayloadDoesNotRequireABinFileNameAndKeepsOneHeaderRead() {
        val raw = rawPayload()
        for (name in listOf("download", "signed-file-123", "misnamed.zip", "payload.bin")) {
            val source = Memory(raw, name)
            val payload = PayloadParser.parse(source)
            assertEquals("boot", payload.manifest.getPartitions(0).partitionName)
            assertEquals(1, source.reads.count { it.first == 0L })
            assertEquals(0L to 24, source.reads.first())
        }
    }
    @Test fun zipNamedBinIsStillLocatedByItsBytes() {
        val raw = rawPayload()
        val direct = PayloadParser.parse(Memory(raw, "download"))
        val wrapped = PayloadParser.parse(Memory(zip(raw), "payload.bin"))
        assertTrue(wrapped.dataOffset > direct.dataOffset)
        assertEquals(payloadResumeIdentity(direct, "boot"), payloadResumeIdentity(wrapped, "boot"))
    }
    @Test fun real403ThenExtensionlessReplacementResumesAndVerifiesImage() = runBlocking {
        val raw = rawPayload()
        val original = PayloadParser.parse(Memory(raw, "payload.bin"))
        val resumedRanges = CopyOnWriteArrayList<Long>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val first = range[0].toLong(); val last = range[1].toLong()
                if (request.path!!.startsWith("/old") && first >= original.dataOffset + 4096) {
                    return MockResponse().setResponseCode(403)
                }
                if (request.path!!.startsWith("/download")) resumedRanges += first
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $first-$last/${raw.size}")
                    .setBody(Buffer().write(raw, first.toInt(), (last - first + 1).toInt()))
            }
        }
        server.start()
        val client = OkHttpClient()
        try {
            val out = temp.newFolder("images")
            val journal = File(out, "boot.img.progress")
            val identity = payloadResumeIdentity(original, "boot")
            val extractor = PayloadExtractor(workers = 1)
            HttpSource.open(server.url("/old/payload.bin").toString(), client).use { source ->
                val resume = ResumeState(journal, identity, 2)
                try {
                    extractor.extractPartition(original, source, original.manifest.getPartitions(0), out, true, resume) {}
                    fail("Old URL must fail on the second operation")
                } catch (error: IOException) {
                    assertTrue(HttpReadFailure.isRemoteReadFailure(error))
                    assertEquals(403, HttpReadFailure.classify(error)?.statusCode)
                    assertTrue(resume.isDone(0))
                    assertFalse(resume.isDone(1))
                }
            }
            HttpSource.open(server.url("/download?renewed=1").toString(), client).use { freshSource ->
                val fresh = freshSource.forParsing().use { PayloadParser.parse(it) }
                assertArrayEquals(original.manifest.toByteArray(), fresh.manifest.toByteArray())
                val result = extractor.extractPartition(fresh, freshSource, fresh.manifest.getPartitions(0), out, true,
                    ResumeState(journal, identity, 2)) {}
                assertEquals(true, result.verified)
                assertArrayEquals(image, result.outputFile.readBytes())
                assertFalse("Committed first operation must not be downloaded again", resumedRanges.contains(fresh.dataOffset))
                assertTrue(resumedRanges.contains(fresh.dataOffset + 4096))
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
