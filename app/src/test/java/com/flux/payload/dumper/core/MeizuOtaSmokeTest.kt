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
 * The archive is a standard A/B OTA: six ZIP64 entries, a STORED `payload.bin`,
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
        if (url.isEmpty()) {
            println("MEIZU_OTA skipped: $ENV_URL is not set")
            return
        }
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
            val result = UniversalParser.parse(source) { message -> lines += "progress=$message" }
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

    private companion object {
        const val ENV_URL = "FLUX_MEIZU_OTA_URL"
    }
}
