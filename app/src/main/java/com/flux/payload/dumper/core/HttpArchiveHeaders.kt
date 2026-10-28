package com.flux.payload.dumper.core

import java.net.URI
import java.net.URLDecoder

/** HTTP archive metadata is untrusted. Keep it separate from storage path construction. */
internal object HttpArchiveHeaders {
    data class ByteRange(val first: Long, val last: Long, val total: Long)

    fun contentRange(value: String?): ByteRange? {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return ByteRange(first, last, total).takeIf { first <= last && last < total }
    }

    fun fileName(disposition: String?, url: String): String {
        val fields = Regex("(?:^|;)\\s*(filename\\*?)\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
            .findAll(disposition.orEmpty()).associate { match ->
                match.groupValues[1].lowercase() to match.groupValues[2].ifEmpty { match.groupValues[3].trim() }
            }
        val extended = fields["filename*"]?.split('\'', limit = 3)?.takeIf { it.size == 3 }
        val encodedName = extended?.takeIf { it[0].equals("UTF-8", true) }?.let { decode(it[2]) }
        val plainName = fields["filename"]?.takeIf { it.isNotBlank() }
        val pathName = runCatching { URI(url).rawPath?.substringAfterLast('/')?.let(::decode) }.getOrNull()
        return safeName(encodedName ?: plainName ?: pathName.orEmpty())
    }

    private fun decode(value: String): String? = runCatching {
        // '+' is literal in an HTTP filename/path, not form-urlencoded whitespace.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrNull()

    fun safeName(value: String): String = value.replace('\\', '/').substringAfterLast('/')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_")
        .trim().trim('.')
        .take(240)
        .ifBlank { "payload.bin" }
}
