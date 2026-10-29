package com.flux.payload.dumper.core

/**
 * A bounded, parse-scoped cache of exact reads. A cached tail also serves its contained
 * central directory. It never prefetches image data or owns/closes the extraction source.
 */
internal class ParseReadCache(
    private val source: PayloadSource,
    private val byteBudget: Int = 1024 * 1024,
) : PayloadSource {
    private val ranges = linkedMapOf<Long, ByteArray>()
    private var cachedBytes = 0

    init { require(byteBudget >= 0) }

    override val size: Long get() = source.size
    override fun fileName(): String = source.fileName()
    override fun identity(): String = source.identity()

    override fun readFully(offset: Long, buffer: ByteArray, bufOffset: Int, len: Int) {
        require(offset >= 0 && len >= 0 && offset <= size - len) { "Read outside archive" }
        require(bufOffset >= 0 && bufOffset <= buffer.size - len) { "Read outside destination buffer" }
        FirmwareMetadata.checkCancelled()
        if (len == 0) return
        synchronized(ranges) {
            for ((start, bytes) in ranges) {
                val relative = offset - start
                if (relative >= 0 && relative <= bytes.size.toLong() - len) {
                    bytes.copyInto(buffer, bufOffset, relative.toInt(), relative.toInt() + len)
                    return
                }
            }
        }
        // Keep only parser-sized reads, not boot/image operations. Insert only AFTER a full,
        // validated source read; a failed or partially filled buffer is never cacheable.
        if (len > minOf(byteBudget, 256 * 1024)) {
            source.readFully(offset, buffer, bufOffset, len)
            return
        }
        val bytes = source.readFully(offset, len)
        synchronized(ranges) {
            ranges.remove(offset)?.let { cachedBytes -= it.size }
            while (ranges.isNotEmpty() && (cachedBytes > byteBudget - len || ranges.size >= 64)) {
                val first = ranges.entries.iterator()
                cachedBytes -= first.next().value.size
                first.remove()
            }
            ranges[offset] = bytes
            cachedBytes += len
        }
        bytes.copyInto(buffer, bufOffset)
    }

    override fun close() {
        synchronized(ranges) {
            ranges.clear()
            cachedBytes = 0
        }
    }
}
