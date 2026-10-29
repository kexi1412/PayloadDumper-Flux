package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ProtocolException
import java.util.zip.GZIPOutputStream

class HttpSourceEncodingTest {
    private val payload = "0123456789ABCDEFGHIJ".toByteArray()

    private class RangeDispatcher(
        private val payload: ByteArray,
        private val encoding: String?,
        private val encodedSlice: (ByteArray) -> ByteArray = { it },
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range") ?: "bytes=0-0"
            val match = Regex("bytes=(\\d+)-(\\d+)").find(range) ?: error("Bad Range: $range")
            val first = match.groupValues[1].toInt()
            val last = match.groupValues[2].toInt()
            val slice = encodedSlice(payload.copyOfRange(first, last + 1))
            val response = MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $first-$last/${payload.size}")
                .setBody(Buffer().write(slice))
            if (encoding != null) response.setHeader("Content-Encoding", encoding)
            return response
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun readThrough(server: MockWebServer, offset: Long, len: Int): ByteArray {
        server.start()
        return HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use {
            assertEquals(payload.size.toLong(), it.size)
            it.readFully(offset, len)
        }
    }

    @Test fun gzipEncodedRangeIsInflatedToTheRequestedBytes() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "gzip", ::gzip)
            assertArrayEquals(payload.copyOfRange(4, 14), readThrough(server, 4, 10))
        }
    }

    @Test fun deflateEncodedRangeIsInflatedToTheRequestedBytes() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "deflate") { raw ->
                val out = ByteArrayOutputStream()
                java.util.zip.DeflaterOutputStream(out).use { it.write(raw) }
                out.toByteArray()
            }
            assertArrayEquals(payload.copyOfRange(0, 5), readThrough(server, 0, 5))
        }
    }

    @Test fun mislabeledIdentityBodyWithGzipHeaderIsAccepted() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "gzip")
            assertArrayEquals(payload.copyOfRange(2, 7), readThrough(server, 2, 5))
        }
    }

    @Test fun compressedRangeThatInflatesTooFarIsRejected() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "gzip") { raw -> gzip(raw + "EXTRA".toByteArray()) }
            assertThrows(ProtocolException::class.java) { readThrough(server, 0, 5) }
        }
    }

    @Test fun truncatedCompressedRangeRestartsAtTheSameArchiveOffset() {
        val body = ByteArray(65_536) { (it * 29 + 7).toByte() }
        val starts = mutableListOf<Int>()
        var truncated = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                    val first = match.groupValues[1].toInt()
                    val last = match.groupValues[2].toInt()
                    if (first >= 100) synchronized(starts) { starts += first }
                    val encoded = gzip(body.copyOfRange(first, last + 1))
                    val sent = if (!truncated && first >= 100) {
                        truncated = true
                        encoded.copyOf(maxOf(2, encoded.size / 2))
                    } else {
                        encoded
                    }
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                        .setHeader("Content-Encoding", "gzip")
                        .setBody(Buffer().write(sent))
                }
            }
            server.start()
            HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use { source ->
                assertArrayEquals(body.copyOfRange(100, 30_100), source.readFully(100, 30_000))
            }
        }
        val dataStarts = synchronized(starts) { starts.toList() }
        assertTrue("compressed retry did not restart at the same offset: $dataStarts", dataStarts.size >= 2)
        assertEquals(100, dataStarts[0])
        assertEquals(100, dataStarts[1])
    }

    @Test fun charsetMislabeledAsContentEncodingIsTreatedAsIdentity() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "utf-8")
            assertArrayEquals(payload.copyOfRange(3, 8), readThrough(server, 3, 5))
        }
    }

    @Test fun encodingListStillDecodesItsGzipMember() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "utf-8, gzip", ::gzip)
            assertArrayEquals(payload.copyOfRange(1, 6), readThrough(server, 1, 5))
        }
    }

    @Test fun truncatedRangeIsResumedAtTheFirstMissingByte() {
        val body = ByteArray(2048) { (it * 31).toByte() }
        val starts = mutableListOf<Int>()
        var truncated = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                    val first = match.groupValues[1].toInt()
                    val last = match.groupValues[2].toInt()
                    synchronized(starts) { starts += first }
                    if (!truncated && first >= 512 && last >= 700) {
                        truncated = true
                        val prefix = body.copyOfRange(first, first + 100)
                        return MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                            .setBody(Buffer().write(prefix))
                            // Keep the advertised length equal to the requested range while
                            // physically sending only the prefix, matching the affected CDN.
                            .setHeader("Content-Length", last - first + 1)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(first, last + 1)))
                }
            }
            server.start()
            HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use { source ->
                assertEquals(body.size.toLong(), source.size)
                val destination = ByteArray(1568) { 0x55.toByte() }
                source.readFully(512, destination, 17, 1536)
                assertArrayEquals(body.copyOfRange(512, 2048), destination.copyOfRange(17, 1553))
                assertTrue(destination.copyOfRange(0, 17).all { it == 0x55.toByte() })
                assertTrue(destination.copyOfRange(1553, destination.size).all { it == 0x55.toByte() })
            }
        }
        val dataStarts = synchronized(starts) { starts.filter { it >= 512 } }
        assertEquals(512, dataStarts.first())
        assertTrue("expected a suffix request after a partial body: $dataStarts", dataStarts.any { it > 512 })
    }

    @Test fun zeroByteTruncationFallsBackAndPersistsWorkingChunkLimit() {
        val body = ByteArray(400_000) { (it * 17 + 3).toByte() }
        val requestedLengths = mutableListOf<Int>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                    val first = match.groupValues[1].toInt()
                    val last = match.groupValues[2].toInt()
                    val length = last - first + 1
                    synchronized(requestedLengths) { requestedLengths += length }
                    val response = MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                    return if (length > 16 * 1024) {
                        response
                            .setHeader("Content-Length", length)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    } else {
                        response.setBody(Buffer().write(body.copyOfRange(first, last + 1)))
                    }
                }
            }
            server.start()
            HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use { source ->
                assertArrayEquals(body.copyOfRange(10, 90_010), source.readFully(10, 90_000))
                val beforeSecondRead = synchronized(requestedLengths) { requestedLengths.size }
                assertArrayEquals(body.copyOfRange(200_000, 250_000), source.readFully(200_000, 50_000))
                val secondReadLengths = synchronized(requestedLengths) {
                    requestedLengths.drop(beforeSecondRead)
                }
                assertTrue("learned limit was not reused: $secondReadLengths", secondReadLengths.all { it <= 16 * 1024 })
            }
        }
        val lengths = synchronized(requestedLengths) { requestedLengths.toList() }
        assertTrue("expected the initial large request: $lengths", lengths.any { it > 32 * 1024 })
        assertTrue("expected the 32 KiB fallback: $lengths", lengths.contains(32 * 1024))
        assertTrue("expected the 16 KiB fallback: $lengths", lengths.contains(16 * 1024))
    }

    @Test fun emptyRangeIsRetriedInsteadOfReturnedAsSuccess() {
        val body = ByteArray(1024) { it.toByte() }
        var empty = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                    val first = match.groupValues[1].toInt()
                    val last = match.groupValues[2].toInt()
                    if (!empty && first >= 256) {
                        empty = true
                        return MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                            .setBody(Buffer())
                    }
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(first, last + 1)))
                }
            }
            server.start()
            HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use { source ->
                assertArrayEquals(body.copyOfRange(256, 1024), source.readFully(256, 768))
            }
        }
    }
}
