package app.codeedit.core.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Regular seekable file source. Positional reads are thread-safe. Owns the channel (closed in close()). */
class FileChannelSource(
    private val channel: FileChannel,
    override val lastModified: Long = 0L,
    private val onClose: (() -> Unit)? = null,
) : RandomAccessSource {
    private val fixedSize: Long = channel.size()
    override val size: Long get() = fixedSize
    override val isFinal: Boolean get() = true

    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= fixedSize) return -1
        val bb = ByteBuffer.wrap(dst, off, len)
        var total = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb, position + total)
            if (n <= 0) break
            total += n
        }
        return if (total == 0) -1 else total
    }

    override fun close() {
        try { channel.close() } catch (_: Throwable) {}
        try { onClose?.invoke() } catch (_: Throwable) {}
    }
}
