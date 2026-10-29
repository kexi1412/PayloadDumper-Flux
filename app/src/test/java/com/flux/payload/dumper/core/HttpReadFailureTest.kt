package com.flux.payload.dumper.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class HttpReadFailureTest {
    @Test fun localIoHashAndDeltaFailuresAreNotRemoteEvenWhenTheirMessagesMentionHttp() {
        listOf(
            IOException("No space left on device writing an HTTP archive"),
            IOException("Operation blob SHA-256 mismatch"),
            IOException("Partition needs incremental (delta) data; only full OTA packages are supported"),
            IOException("Output truncated during SHA-256 verification"),
            ProtocolException("HTTP 403 is only text, not provenance"),
            SocketTimeoutException("local operation timed out"),
            InterruptedIOException("local operation interrupted"),
            IOException("HTTP wrapper", IOException("HTTP 410")),
        ).forEach { failure ->
            assertFalse(failure.toString(), HttpReadFailure.isRemoteReadFailure(failure))
            assertNull(HttpReadFailure.classify(failure))
        }
    }

    @Test fun markingPreservesExactExceptionMessageCauseAndFirstStatus() {
        val cause = IOException("socket reset")
        val failure = IOException("HTTP 403 for range 3-5", cause)
        assertSame(failure, HttpReadFailure.mark(failure, HttpReadFailure.Kind.HTTP_STATUS, 403))
        HttpReadFailure.mark(failure, HttpReadFailure.Kind.TRANSPORT)
        val details = HttpReadFailure.classify(failure)!!
        assertSame(failure, details.exception)
        assertSame(cause, failure.cause)
        assertEquals("HTTP 403 for range 3-5", failure.message)
        assertEquals(HttpReadFailure.Kind.HTTP_STATUS, details.kind)
        assertEquals(403, details.statusCode)
        assertFalse(details.retryable)
        assertEquals(1, failure.suppressed.size)
    }

    @Test fun classifierFollowsCauseWrappersButNotUnrelatedSuppressedFailures() {
        val remote = HttpReadFailure.mark(IOException("connection reset"), HttpReadFailure.Kind.TRANSPORT)
        val wrapped = IOException("Partition extraction failed", remote)
        assertTrue(HttpReadFailure.isRemoteReadFailure(wrapped))
        assertSame(remote, HttpReadFailure.classify(wrapped)!!.exception)
        val local = IOException("Output write failed").also { it.addSuppressed(remote) }
        assertFalse(HttpReadFailure.isRemoteReadFailure(local))
    }

    @Test fun cancellationWinsEvenWhenItWrapsAMarkedRemoteFailure() {
        val remote = HttpReadFailure.mark(SocketTimeoutException("timeout"), HttpReadFailure.Kind.TIMEOUT)
        val cancelled = CancellationException("worker cancelled").also { it.initCause(remote) }
        val interrupted = InterruptedException("worker interrupted").also { it.initCause(remote) }
        assertFalse(HttpReadFailure.isRemoteReadFailure(cancelled))
        assertFalse(HttpReadFailure.isRemoteReadFailure(IOException("wrapper", cancelled)))
        assertFalse(HttpReadFailure.isRemoteReadFailure(interrupted))
        val cancellationCause = IOException("read stopped", CancellationException("cancelled"))
        HttpReadFailure.mark(cancellationCause, HttpReadFailure.Kind.TRANSPORT)
        assertFalse(HttpReadFailure.isRemoteReadFailure(cancellationCause))
    }

    @Test(timeout = 1_000) fun cyclicCausesTerminateWithAndWithoutProvenance() {
        val first = IOException("first")
        val second = IOException("second")
        first.initCause(second)
        second.initCause(first)
        assertFalse(HttpReadFailure.isRemoteReadFailure(first))
        HttpReadFailure.mark(second, HttpReadFailure.Kind.TRANSPORT)
        assertSame(second, HttpReadFailure.classify(first)!!.exception)
    }
}
