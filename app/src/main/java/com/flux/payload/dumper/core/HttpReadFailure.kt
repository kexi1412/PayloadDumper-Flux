package com.flux.payload.dumper.core

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/**
 * Provenance of an error raised at HttpSource's request/response boundary, not a guess based on
 * an IOException's type or text. Local output, hash and delta errors never receive this marker.
 *
 * The marker is suppressed on the original exception so callers keep its exact type, message
 * and cause (notably ProtocolException, SocketTimeoutException and resumable short reads).
 */
object HttpReadFailure {
    enum class Kind { HTTP_STATUS, PROTOCOL, SHORT_READ, TIMEOUT, TRANSPORT }

    data class Details(
        val kind: Kind,
        val statusCode: Int?,
        /** The original exception at the HTTP boundary, even when the caller wrapped it. */
        val exception: IOException,
    ) {
        val retryable: Boolean
            get() = when (kind) {
                Kind.HTTP_STATUS -> statusCode == 408 || statusCode == 429 || statusCode in 500..599
                Kind.PROTOCOL -> false
                else -> true
            }
    }

    private class Origin(val kind: Kind, val statusCode: Int?) :
        Throwable("HTTP read origin: $kind", null, false, false)

    /** Only HTTP boundary code may attach provenance; it must exclude cancellation first. */
    internal fun <T : IOException> mark(error: T, kind: Kind, statusCode: Int? = null): T {
        if (error.suppressed.none { it is Origin }) error.addSuppressed(Origin(kind, statusCode))
        return error
    }

    fun isRemoteReadFailure(error: Throwable): Boolean = classify(error) != null

    fun classify(error: Throwable): Details? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var result: Details? = null
        while (current != null && seen.add(current)) {
            // Cancellation can wrap the error that happened while a worker was being stopped.
            if (current is CancellationException || current is InterruptedException) return null
            val origin = current.suppressed.firstOrNull { it is Origin } as? Origin
            if (result == null && origin != null && current is IOException) {
                result = Details(origin.kind, origin.statusCode, current)
            }
            // Do not search arbitrary suppressed exceptions: a local write/close failure may
            // suppress an earlier remote error, without itself being repairable by a new URL.
            current = current.cause
        }
        return result
    }
}
