package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        var truncated = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val match = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                    val first = match.groupValues[1].toInt()
                    val last = match.groupValues[2].toInt()
                    if (!truncated && first >= 512 && last >= 700) {
                        truncated = true
                        return MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                            .setBody(Buffer().write(body.copyOfRange(first, first + 100)))
                    }
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-$last/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(first, last + 1)))
                }
            }
            server.start()
            HttpSource.open(server.url("/firmware.zip").toString(), OkHttpClient()).use { source ->
                assertEquals(body.size.toLong(), source.size)
                assertArrayEquals(body.copyOfRange(512, 2048), source.readFully(512, 1536))
            }
        }
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
