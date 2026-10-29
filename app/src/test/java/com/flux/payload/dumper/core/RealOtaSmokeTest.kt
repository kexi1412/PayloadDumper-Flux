package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual end-to-end smoke test against a real OTA link.
 *
 * Skipped unless [ENV_URL] is set, so normal unit-test runs stay offline and deterministic.
 * Run it from CI with the *actual* application parser, not a reimplementation:
 *
 * ```
 * FLUX_REAL_OTA_URL='https://…/ota.zip' ./gradlew testDebugUnitTest --tests '*RealOtaSmokeTest*'
 * ```
 */
class RealOtaSmokeTest {

    @Test
    fun parsesRealOtaEndToEnd() {
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
            assertEquals(8214889222L, info.fileSize)
            assertEquals(4096, info.blockSize)
            assertEquals(54, info.partitionCount)
            assertEquals("2025-09-01", info.securityPatchLevel)
            assertEquals("15", info.androidVersion)
            assertTrue(
                "fingerprint was '${info.fingerprint}'",
                info.fingerprint.contains("OnePlus/PJZ110/OP5D0DL1:15/"),
            )
            assertTrue(
                "kernel was '${info.kernelVersion}'",
                info.kernelVersion.startsWith("6.6.66-android15"),
            )
        }

        val report = lines.joinToString("\n")
        println(report)
        val output = File("build/real-ota-result.txt")
        output.parentFile?.mkdirs()
        output.writeText(report)
    }

    private companion object {
        const val ENV_URL = "FLUX_REAL_OTA_URL"
    }
}
