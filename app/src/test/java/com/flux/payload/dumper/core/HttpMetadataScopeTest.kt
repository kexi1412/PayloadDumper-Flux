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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpMetadataScopeTest {
    private val body = ByteArray(400_000) { (it * 31 + 7).toByte() }

    private fun range(request: RecordedRequest): IntRange {
        val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
        return match.groupValues[1].toInt()..match.groupValues[2].toInt()
    }

    private fun response(range: IntRange, bytes: ByteArray = body.copyOfRange(range.first, range.last + 1)) =
        MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes ${range.first}-${range.last}/${body.size}")
            .setBody(Buffer().write(bytes))

    @Test fun closingMetadataCancelsOnlyItsOwnCallNotConcurrentExtraction() {
        val metadataArrived = CountDownLatch(1)
        val extractionArrived = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val selected = range(request)
                    if (selected.first == 10) {
                        metadataArrived.countDown()
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    if (selected.first == 100) {
                        extractionArrived.countDown()
                        releaseExtraction.await(5, TimeUnit.SECONDS)
                    }
                    return response(selected)
                }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            HttpSource.open(server.url("/ota.zip").toString(), client).use { parent ->
                parent.forMetadata(10_000).use { metadata ->
                    try {
                        val blocked = workers.submit<ByteArray> { metadata.readFully(10, 32) }
                        assertTrue(metadataArrived.await(2, TimeUnit.SECONDS))
                        val extracting = workers.submit<ByteArray> { parent.readFully(100, 64) }
                        assertTrue(extractionArrived.await(2, TimeUnit.SECONDS))
                        metadata.close()
                        releaseExtraction.countDown()
                        val failure = assertThrows(ExecutionException::class.java) { blocked.get(2, TimeUnit.SECONDS) }
                        assertTrue(failure.cause is IOException)
                        assertFalse(HttpReadFailure.isRemoteReadFailure(failure))
                        assertArrayEquals(body.copyOfRange(100, 164), extracting.get(2, TimeUnit.SECONDS))
                        assertArrayEquals(body.copyOfRange(500, 520), parent.readFully(500, 20))
                    } finally {
                        releaseExtraction.countDown()
                        metadata.close()
                        workers.shutdownNow()
                    }
                }
            }
            client.connectionPool.evictAll()
        }
    }

    @Test fun metadataDeadlineSpansSeveralSuccessfulReads() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val selected = range(request)
                    return response(selected).apply {
                        if (selected.first > 0) setBodyDelay(80, TimeUnit.MILLISECONDS)
                    }
                }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            HttpSource.open(server.url("/ota.zip").toString(), client).use { parent ->
                val start = System.nanoTime()
                parent.forMetadata(200).use { metadata ->
                    assertThrows(IOException::class.java) {
                        repeat(10) { metadata.readFully(100L + it * 16, 16) }
                    }
                }
                assertTrue("deadline was reset per call", elapsedMillis(start) < 3_000)
                assertArrayEquals(body.copyOfRange(50, 54), parent.readFully(50, 4))
            }
            client.connectionPool.evictAll()
        }
    }

    @Test fun metadataDeadlineAlsoBoundsRepeatedPartialBodies() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val selected = range(request)
                    if (selected.first == 0) return response(selected)
                    return response(selected, byteArrayOf(body[selected.first]))
                        .setHeader("Content-Length", selected.last - selected.first + 1)
                        .setBodyDelay(80, TimeUnit.MILLISECONDS)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            HttpSource.open(server.url("/ota.zip").toString(), client).use { parent ->
                val start = System.nanoTime()
                parent.forMetadata(200).use { metadata ->
                    assertThrows(IOException::class.java) { metadata.readFully(100, 32) }
                }
                assertTrue("partial progress extended the total deadline", elapsedMillis(start) < 3_000)
            }
            client.connectionPool.evictAll()
        }
    }

    @Test fun stalledSizeProbeIsRetriedWithoutReturningInvalidMetadata() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(response(0..0))
            server.enqueue(response(100..131))
            // This tests HttpSource's retry, not localhost IPv4/IPv6 fallback. A timed-out
            // route plus retryOnConnectionFailure(false) can select unbound ::1 on CI.
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val url = server.url("/ota.zip").newBuilder().host("127.0.0.1").build()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .readTimeout(1, TimeUnit.SECONDS).build()
            HttpSource.open(url.toString(), client).use { source ->
                assertEquals(body.size.toLong(), source.size)
                assertArrayEquals(body.copyOfRange(100, 132), source.readFully(100, 32))
            }
            assertEquals(3, server.requestCount)
            client.connectionPool.evictAll()
        }
    }

    @Test fun parsingDeadlineAndCloseLeaveTheExtractionSourceUsable() {
        MockWebServer().use { server ->
            server.enqueue(response(0..0))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(response(100..131))
            server.start()
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            HttpSource.open(server.url("/ota.zip").toString(), client).use { source ->
                val start = System.nanoTime()
                source.forParsing(200).use { parsing ->
                    assertThrows(IOException::class.java) { parsing.readFully(200, 32) }
                }
                assertTrue("parser inherited extraction's long wait", elapsedMillis(start) < 3_000)
                assertArrayEquals(body.copyOfRange(100, 132), source.readFully(100, 32))
            }
            client.connectionPool.evictAll()
        }
    }

    @Test fun emptyTinyHeaderDoesNotPoisonTheLargeRangeLimit() = tinyHeaderFailure(0)
    @Test fun partialTinyHeaderDoesNotPoisonTheLargeRangeLimit() = tinyHeaderFailure(2)

    private fun tinyHeaderFailure(prefix: Int) {
        val largeLengths = mutableListOf<Int>()
        var truncated = false
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val selected = range(request)
                    if (selected.first >= 100_000) synchronized(largeLengths) {
                        largeLengths += selected.last - selected.first + 1
                    }
                    if (!truncated && selected.first == 1024) {
                        truncated = true
                        return response(selected, body.copyOfRange(1024, 1024 + prefix))
                            .setHeader("Content-Length", 4)
                            .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                    return response(selected)
                }
            }
            server.start()
            val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
            HttpSource.open(server.url("/ota.zip").toString(), client).use { source ->
                assertArrayEquals(body.copyOfRange(1024, 1028), source.readFully(1024, 4))
                assertArrayEquals(body.copyOfRange(100_000, 300_000), source.readFully(100_000, 200_000))
            }
            assertEquals("tiny-read failure permanently shrank later ranges", listOf(200_000), largeLengths)
            client.connectionPool.evictAll()
        }
    }

    private fun elapsedMillis(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
}
