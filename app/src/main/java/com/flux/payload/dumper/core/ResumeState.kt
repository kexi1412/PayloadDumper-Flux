package com.flux.payload.dumper.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Records which operation indices of a partition have been fully written, so an interrupted
 * extraction (typically an **expired OTA link** during a slow, large-partition download over the
 * network) can resume instead of restarting from zero.
 *
 * Correctness relies on operations being idempotent: each op writes to a fixed set of
 * `dst_extents`, so re-running a not-yet-marked op simply overwrites the same region. Backed by a
 * `<partition>.img.progress` sidecar (append-only) so resume also survives an app restart.
 * Thread-safe for concurrent [mark] from many workers.
 */
class ResumeState(private val sidecar: File) {

    private val done = ConcurrentHashMap.newKeySet<Int>()
    private val appendLock = Any()

    fun load() {
        done.clear()
        if (sidecar.exists()) runCatching {
            sidecar.forEachLine { line -> line.trim().toIntOrNull()?.let(done::add) }
        }
    }

    fun isDone(index: Int): Boolean = done.contains(index)

    fun completedIndices(): Set<Int> = done.toSet()

    fun mark(index: Int) {
        if (done.add(index)) {
            synchronized(appendLock) { runCatching { sidecar.appendText("$index\n") } }
        }
    }

    fun clear() {
        done.clear()
        runCatching { sidecar.delete() }
    }
}
