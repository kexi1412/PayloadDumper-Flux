package com.flux.payload.dumper.core

/** Platform-neutral failure descriptions used by the shared parsers. */
object Diagnostics {
    fun isOutOfMemory(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(8) {
            if (current is OutOfMemoryError) return true
            current = current?.cause
        }
        return false
    }

    fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (depth > 0) append("\n  caused by: ")
            append(current.javaClass.name)
            append(": ").append(current.message ?: "no message")
            current = current.cause
            depth++
        }
        if (isOutOfMemory(error)) append("\n  hint: insufficient JVM heap")
    }
}
