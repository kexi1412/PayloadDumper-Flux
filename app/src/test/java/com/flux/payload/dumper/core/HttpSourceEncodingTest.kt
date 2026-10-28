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

    @Test fun unsupportedEncodingIsRejectedWithAProtocolError() {
        MockWebServer().use { server ->
            server.dispatcher = RangeDispatcher(payload, "br")
            assertThrows(ProtocolException::class.java) { readThrough(server, 0, 3) }
        }
    }
}
