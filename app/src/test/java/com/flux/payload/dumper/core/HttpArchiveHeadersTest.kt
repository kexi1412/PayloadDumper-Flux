package com.flux.payload.dumper.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.ProtocolException

class HttpArchiveHeadersTest {
    @Test fun rfc5987FilenameTakesPriorityAndPreservesPlus() {
        assertEquals("测试+OTA.zip", HttpArchiveHeaders.fileName(
            "attachment; filename=ota.zip; filename*=UTF-8''%E6%B5%8B%E8%AF%95+OTA.zip", "https://x/ignored.bin",
        ))
    }
    @Test fun quotedSemicolonAndUrlEncodingAreDecoded() {
        assertEquals("OTA;full.zip", HttpArchiveHeaders.fileName("attachment; filename=\"OTA;full.zip\"", "https://x/"))
        assertEquals("test file+v2.zip", HttpArchiveHeaders.fileName(null, "https://x/test%20file+v2.zip?token=secret"))
    }
    @Test fun remoteFilenameCannotEscapeOutputDirectory() {
        assertEquals("boot.img", HttpArchiveHeaders.fileName("filename=../../boot.img", "https://x/"))
        assertEquals("ota.zip", HttpArchiveHeaders.safeName("C:\\files\\ota.zip"))
        assertEquals("payload.bin", HttpArchiveHeaders.safeName(".."))
    }
    @Test fun invalidExtendedEncodingFallsBackToPlainName() {
        assertEquals("ota.zip", HttpArchiveHeaders.fileName("filename=ota.zip; filename*=UTF-8''%XX", "https://x/"))
    }
    @Test fun largeArchiveSizeRemains64Bit() {
        assertEquals(12_000_000_000L, HttpArchiveHeaders.contentRange("bytes 0-0/12000000000")?.total)
    }
    @Test fun rejectsInvalidRanges() {
        listOf(null, "bytes 0-0/*", "bytes 2-1/5", "bytes 0-5/5", "bytes -1-1/5", "bytes 0-0/0", "bytes 0-0/999999999999999999999").forEach {
            assertNull(it, HttpArchiveHeaders.contentRange(it))
        }
    }
    @Test fun positionedReadRequestsAndReceivesExactBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/8")
                .setHeader("Content-Disposition", "filename=OTA.zip").setBody("0"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/8").setBody("345"))
            server.start()
            HttpSource.open(server.url("/firmware").toString(), OkHttpClient()).use { source ->
                assertEquals("OTA.zip", source.fileName())
                assertEquals(8L, source.size)
                assertEquals("345", String(source.readFully(3, 3)))
            }
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
            val request = server.takeRequest()
            assertEquals("bytes=3-5", request.getHeader("Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }
    @Test fun refusesServerThatIgnoresRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("all bytes"))
            server.start()
            assertThrows(ProtocolException::class.java) { HttpSource.open(server.url("/ota").toString(), OkHttpClient()) }
        }
    }
    @Test fun refusesChangedArchiveOrWrongOffset() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/8").setBody("0"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/8").setBody("012"))
            server.start()
            HttpSource.open(server.url("/ota").toString(), OkHttpClient()).use { source ->
                assertThrows(ProtocolException::class.java) { source.readFully(3, 3) }
            }
        }
    }
}
