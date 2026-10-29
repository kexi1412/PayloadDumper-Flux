package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual end-to-end smoke test for the Meizu/StepAOS ZIP64 package.
 *
 * The archive is a standard A/B OTA: seven ZIP entries, a STORED `payload.bin`,
 * `CrAU` v2, and a 4 KiB block size. It is intentionally kept out of the default
 * unit test run because it needs the network.
 *
 * ```
 * FLUX_MEIZU_OTA_URL='https://…/update.zip' ./gradlew testDebugUnitTest --tests '*MeizuOtaSmokeTest*'
 * ```
 */
class MeizuOtaSmokeTest {

    @Test
    fun parsesMeizuZip64OtaEndToEnd() {
        val url = System.getenv(ENV_URL)?.trim().orEmpty()
        org.junit.Assume.assumeTrue("$ENV_URL is not set", url.isNotEmpty())
        val lines = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        HttpSource.open(url, client).use { source ->
            lines += "source.name=${source.fileName()}"
            lines += "source.size=${source.size}"
            val result = UniversalParser.parse(source, onProgress = { message -> lines += "progress=$message" })
            val info = result.archiveInfo
            lines += "archive.fileName=${info.fileName}"
            lines += "archive.fileSize=${info.fileSize}"
            lines += "archive.androidVersion=${info.androidVersion}"
            lines += "archive.securityPatch=${info.securityPatchLevel}"
            lines += "archive.kernelVersion=${info.kernelVersion}"
            lines += "archive.fingerprint=${info.fingerprint}"
            lines += "archive.partitionCount=${info.partitionCount}"
            lines += "archive.blockSize=${info.blockSize}"

            assertTrue("expected CrAU, got ${result::class.simpleName}", result is ParseResult.CrAU)
            assertEquals(6718923088L, info.fileSize)
            assertEquals(4096, info.blockSize)
            assertEquals(57, info.partitionCount)
            assertEquals("2025-12-05", info.securityPatchLevel)
            assertEquals("16", info.androidVersion)
            assertTrue(
                "fingerprint was '${info.fingerprint}'",
                info.fingerprint.contains("StepX/pandora/pandora:16/"),
            )
            assertTrue(
                "kernel was '${info.kernelVersion}'",
                info.kernelVersion.startsWith("6.12.58-android16"),
            )
        }

        val report = lines.joinToString("\n")
        println(report)
        val output = File("build/meizu-ota-result.txt")
        output.parentFile?.mkdirs()
        output.writeText(report)
    }

    @Test
    fun parsesMeizuFastThenReadsKernelSeparately() {
        val url = System.getenv(ENV_URL)?.trim().orEmpty()
        org.junit.Assume.assumeTrue("$ENV_URL is not set", url.isNotEmpty())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val lines = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "PayloadDumperFlux/${com.flux.payload.dumper.BuildConfig.VERSION_NAME} (Android)")
                    .build()
                calls.incrementAndGet()
                chain.proceed(request)
            }
            .eventListener(object : okhttp3.EventListener() {
                override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) { bytes.addAndGet(byteCount) }
            })
            .build()
        val runs = System.getenv("FLUX_MEIZU_RUNS")?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        try {
            repeat(runs) { index ->
                val start = System.nanoTime()
                val initialCalls = calls.get()
                val initialBytes = bytes.get()
                lines += "run=${index + 1}"
            HttpSource.open(url, client).use { source ->
                val parsed = UniversalParser.parse(source, includeKernel = false,
                    onProgress = { lines += "progress=$it" })
                val ready = parsed.archiveInfo
                lines += "ready.seconds=${(System.nanoTime() - start) / 1e9}"
                lines += "ready.httpCalls=${calls.get() - initialCalls}"
                lines += "ready.receivedBytes=${bytes.get() - initialBytes}"
                lines += "ready.partitions=${ready.partitionCount}"
                lines += "ready.android=${ready.androidVersion}"
                lines += "ready.patch=${ready.securityPatchLevel}"
                lines += "ready.fingerprint=${ready.fingerprint}"
                assertTrue(parsed is ParseResult.CrAU)
                assertEquals(6_718_923_088L, ready.fileSize)
                assertEquals(57, ready.partitionCount)
                assertEquals(4096, ready.blockSize)
                assertEquals("16", ready.androidVersion)
                assertEquals("2025-12-05", ready.securityPatchLevel)
                assertTrue(ready.fingerprint.startsWith("StepX/pandora/pandora:16/"))
                assertEquals("", ready.kernelVersion)
                val kernelStart = System.nanoTime()
                source.forMetadata().use { metadata ->
                    val enriched = FirmwareMetadataReader.readKernel(metadata, ready, (parsed as ParseResult.CrAU).payload)
                    lines += "kernel.seconds=${(System.nanoTime() - kernelStart) / 1e9}"
                    lines += "kernel.version=${enriched.kernelVersion}"
                    lines += "kernel.status=${if (enriched.kernelVersion.isEmpty()) "unavailable-within-budget" else "resolved"}"
                    assertEquals(ready.androidVersion, enriched.androidVersion)
                    assertEquals(ready.securityPatchLevel, enriched.securityPatchLevel)
                    assertEquals(ready.fingerprint, enriched.fingerprint)
                    if (enriched.kernelVersion.isNotEmpty())
                        assertTrue(enriched.kernelVersion.startsWith("6.12.58-android16"))
                }
                lines += "total.httpCalls=${calls.get() - initialCalls}"
                lines += "total.receivedBytes=${bytes.get() - initialBytes}"
            }
            }
        } finally {
            val report = lines.joinToString("\n")
            println(report)
            File("build/meizu-fast-result.txt").apply { parentFile?.mkdirs(); writeText(report) }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val ENV_URL = "FLUX_MEIZU_OTA_URL"
    }
}
