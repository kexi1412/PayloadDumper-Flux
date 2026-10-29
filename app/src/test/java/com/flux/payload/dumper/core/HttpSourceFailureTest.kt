package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HttpSourceFailureTest {
    private val body = "0123456789"
    private val permanentStatuses = listOf(401, 403, 404, 410)
    private val temporaryStatuses = listOf(429, 500, 503)

    private fun response(first: Int, last: Int) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $first-$last/${body.length}")
        .setBody(body.substring(first, last + 1))

    private fun start(server: MockWebServer, path: String = "/ota"): String {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url(path).newBuilder().host("127.0.0.1").build().toString()
    }

    private fun assertStatus(failure: IOException, code: Int, retryable: Boolean) {
        assertTrue(HttpReadFailure.isRemoteReadFailure(failure))
        val details = HttpReadFailure.classify(failure)!!
        assertSame(failure, details.exception)
        assertEquals(HttpReadFailure.Kind.HTTP_STATUS, details.kind)
        assertEquals(code, details.statusCode)
        assertEquals(retryable, details.retryable)
    }

    @Test fun permanentProbeStatusesFailOnceAndKeepTheirStatus() {
        permanentStatuses.forEach { code ->
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
                }
                val url = start(server)
                val failure = assertThrows(IOException::class.java) { HttpSource.open(url, OkHttpClient()) }
                assertStatus(failure, code, false)
                assertEquals("HTTP $code for range 0-0", failure.message)
                assertEquals("unexpected retries for HTTP $code", 1, server.requestCount)
            }
        }
    }

    @Test fun permanentReadStatusesFailOnceAndKeepTheirStatus() {
        permanentStatuses.forEach { code ->
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        if (request.getHeader("Range") == "bytes=0-0") response(0, 0)
                        else MockResponse().setResponseCode(code)
                }
                HttpSource.open(start(server), OkHttpClient()).use { source ->
                    val failure = assertThrows(IOException::class.java) { source.readFully(3, 3) }
                    assertStatus(failure, code, false)
                    assertEquals("HTTP $code for range 3-5", failure.message)
                }
                assertEquals("unexpected retries for HTTP $code", 2, server.requestCount)
            }
        }
    }

    @Test fun temporaryProbeStatusesCanRecover() {
        temporaryStatuses.forEach { code ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                server.enqueue(response(0, 0))
                HttpSource.open(start(server), OkHttpClient()).use { assertEquals(body.length.toLong(), it.size) }
                assertEquals("probe was not retried for HTTP $code", 2, server.requestCount)
            }
        }
    }

    @Test fun temporaryReadStatusesAreBoundedAndKeepTheirFinalStatus() {
        temporaryStatuses.forEach { code ->
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        if (request.getHeader("Range") == "bytes=0-0") response(0, 0)
                        else MockResponse().setResponseCode(code)
                }
                HttpSource.open(start(server), OkHttpClient()).use { source ->
                    val failure = assertThrows(IOException::class.java) { source.readFully(3, 3) }
                    assertStatus(failure, code, true)
                    assertEquals("HTTP $code for range 3-5", failure.message)
                }
                assertEquals("wrong retry budget for HTTP $code", 4, server.requestCount)
            }
        }
    }

    @Test fun temporaryReadStatusesCanRecoverWithoutChangingTheRange() {
        temporaryStatuses.forEach { code ->
            MockWebServer().use { server ->
                server.enqueue(response(0, 0))
                server.enqueue(MockResponse().setResponseCode(code))
                server.enqueue(response(3, 5))
                HttpSource.open(start(server), OkHttpClient()).use { assertEquals("345", String(it.readFully(3, 3))) }
                assertEquals(3, server.requestCount)
                server.takeRequest()
                assertEquals("bytes=3-5", server.takeRequest().getHeader("Range"))
                assertEquals("bytes=3-5", server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test fun invalidRangeResponsesKeepProtocolTypeStatusAndReasonWithoutRetrying() {
        val failures = listOf(
            Triple(200, null, "Server ignored HTTP Range; refusing incorrect archive bytes"),
            Triple(206, null, "Invalid or missing HTTP Content-Range"),
            Triple(206, "bytes 0-2/10", "HTTP Content-Range does not match the requested archive bytes"),
            Triple(206, "bytes 3-5/11", "HTTP Content-Range does not match the requested archive bytes"),
        )
        failures.forEach { (code, contentRange, message) ->
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.getHeader("Range") == "bytes=0-0") return response(0, 0)
                        return MockResponse().setResponseCode(code).setBody("345").apply {
                            if (contentRange != null) setHeader("Content-Range", contentRange)
                        }
                    }
                }
                HttpSource.open(start(server), OkHttpClient()).use { source ->
                    val failure = assertThrows(ProtocolException::class.java) { source.readFully(3, 3) }
                    val details = HttpReadFailure.classify(failure)!!
                    assertEquals(HttpReadFailure.Kind.PROTOCOL, details.kind)
                    assertEquals(code, details.statusCode)
                    assertEquals(message, failure.message)
                    assertFalse(details.retryable)
                    assertSame(failure, details.exception)
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun requestErrorsKeepTheirOriginalExceptionTypeMessageAndInstance() {
        val failures = listOf(
            Triple(SocketTimeoutException("socket idle timeout"), HttpReadFailure.Kind.TIMEOUT, 3),
            // OkHttp's whole-call timeout is InterruptedIOException, not SocketTimeoutException.
            Triple(InterruptedIOException("call deadline"), HttpReadFailure.Kind.TIMEOUT, 3),
            Triple(ProtocolException("malformed HTTP response"), HttpReadFailure.Kind.PROTOCOL, 1),
            Triple(IOException("connection reset"), HttpReadFailure.Kind.TRANSPORT, 3),
        )
        failures.forEach { (original, kind, attempts) ->
            val reads = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().header("Range") != "bytes=0-0") {
                    reads.incrementAndGet()
                    throw original
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content").header("Content-Range", "bytes 0-0/10")
                    .body("0".toResponseBody()).build()
            }.build()
            HttpSource.open("https://firmware.invalid/download", client).use { source ->
                val thrown = assertThrows(IOException::class.java) { source.readFully(3, 3) }
                assertSame(original, thrown)
                assertEquals(kind, HttpReadFailure.classify(thrown)!!.kind)
                assertNull(HttpReadFailure.classify(thrown)!!.statusCode)
                assertEquals(attempts, reads.get())
                assertEquals(1, thrown.suppressed.size)
            }
        }
    }

    @Test fun exhaustedShortReadKeepsItsValidatedPrefixAndActualCause() {
        val ranges = mutableListOf<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = request.getHeader("Range")!!
                    synchronized(ranges) { ranges += range }
                    if (range == "bytes=0-0") return response(0, 0)
                    val first = if (range == "bytes=3-5") 3 else 5
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $first-5/10")
                        .setBody(if (first == 3) "34" else "")
                        .setHeader("Content-Length", 6 - first)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                }
            }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            HttpSource.open(start(server), client).use { source ->
                val destination = ByteArray(5) { 'x'.code.toByte() }
                val failure = assertThrows(IOException::class.java) { source.readFully(3, destination, 1, 3) }
                val details = HttpReadFailure.classify(failure)!!
                assertEquals(HttpReadFailure.Kind.SHORT_READ, details.kind)
                assertEquals(206, details.statusCode)
                assertTrue(failure.cause is IOException)
                assertEquals("Short read: got 0 of 1 at 5", failure.message)
                assertEquals("x34xx", String(destination))
            }
            assertEquals(listOf("bytes=3-5") + List(5) { "bytes=5-5" }, synchronized(ranges) { ranges.drop(1) })
            client.connectionPool.evictAll()
        }
    }

    @Test fun closedOrPreInterruptedSourceIsNotARemoteFailure() {
        MockWebServer().use { server ->
            server.enqueue(response(0, 0))
            val url = start(server)
            HttpSource.open(url, OkHttpClient()).use { source ->
                Thread.currentThread().interrupt()
                try {
                    val read = assertThrows(InterruptedIOException::class.java) { source.readFully(3, 3) }
                    val probe = assertThrows(InterruptedIOException::class.java) { HttpSource.open(url, OkHttpClient()) }
                    assertFalse(HttpReadFailure.isRemoteReadFailure(read))
                    assertFalse(HttpReadFailure.isRemoteReadFailure(probe))
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
                source.close()
                val closed = assertThrows(IOException::class.java) { source.readFully(3, 3) }
                assertFalse(HttpReadFailure.isRemoteReadFailure(closed))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun everyReadResolvesTheOriginalUrlInsteadOfReusingASingleUseRedirect() {
        val entryRequests = AtomicInteger()
        val unusedTokens = ConcurrentHashMap.newKeySet<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val requestUrl = request.requestUrl!!
                    if (requestUrl.encodedPath == "/download") {
                        val token = entryRequests.incrementAndGet().toString()
                        unusedTokens.add(token)
                        return MockResponse().setResponseCode(302)
                            .setHeader("Location", server.url("/signed/firmware.bin?token=$token"))
                    }
                    val token = requestUrl.queryParameter("token")
                    if (token == null || !unusedTokens.remove(token)) return MockResponse().setResponseCode(403)
                    val selected = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
                    return response(selected.groupValues[1].toInt(), selected.groupValues[2].toInt())
                }
            }
            val originalUrl = start(server, "/download")
            HttpSource.open(originalUrl, OkHttpClient()).use { source ->
                assertEquals("firmware.bin", source.fileName())
                assertEquals("http:$originalUrl:10", source.identity())
                assertEquals("345", String(source.readFully(3, 3)))
                source.forMetadata().use { assertEquals("67", String(it.readFully(6, 2))) }
                source.forParsing().use { assertEquals("0123", String(it.readFully(0, 4))) }
            }
            assertEquals(4, entryRequests.get())
            assertEquals(8, server.requestCount)
        }
    }
}
