package app.codeedit.core.io

import java.io.Closeable

/**
 * Positional byte source. Implementations: FileChannelSource (regular files, SAF descriptors that are seekable),
 * SpoolingSource (non-seekable streams copied progressively into a temp file; size grows until isFinal).
 * Thread-safe: read() may be called concurrently.
 */
interface RandomAccessSource : Closeable {
    /** Current size in bytes; may grow while !isFinal. */
    val size: Long
    val isFinal: Boolean
    /** Reads up to len bytes at position; returns bytes read, or -1 at/after end of a final source. May return fewer. */
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int
    /** Last-modified millis if known, else 0. */
    val lastModified: Long
    /**
     * Blocks up to timeoutMs until more than `position` bytes exist or the source is final. Returns true when a read at
     * `position` can make progress (data available or final). Default: always ready (regular files).
     */
    fun awaitData(position: Long, timeoutMs: Long): Boolean = true
}
