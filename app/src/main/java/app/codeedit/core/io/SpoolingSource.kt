package app.codeedit.core.io

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executor

/**
 * Non-seekable stream (e.g. a content:// provider that only offers openInputStream) copied progressively into a temp
 * file. Readers see bytes as soon as they are written: read() never blocks (returns 0 when nothing new yet and the copy
 * is still running); awaitData() blocks with a timeout. The temp file is deleted on close().
 */
class SpoolingSource(
    private val input: InputStream,
    tempDir: File,
    @Suppress("unused") expectedSize: Long = -1L,
    executor: Executor? = null,
    override val lastModified: Long = 0L,
    private val onClose: (() -> Unit)? = null,
) : RandomAccessSource {
    private val lock = Object()
    private val file: File = File.createTempFile("spool", ".tmp", tempDir)
    private val raf = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel
    @Volatile private var written = 0L
    @Volatile private var finished = false
    @Volatile private var cancelled = false
    @Volatile var error: Throwable? = null
        private set

    override val size: Long get() = written
    override val isFinal: Boolean get() = finished

    init {
        val task = Runnable { copyLoop() }
        if (executor != null) executor.execute(task) else Thread(task, "spool-copy").apply { isDaemon = true }.start()
    }

    private fun copyLoop() {
        val buf = ByteArray(256 * 1024)
        try {
            while (!cancelled) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val bb = ByteBuffer.wrap(buf, 0, n)
                var pos = written
                while (bb.hasRemaining()) pos += channel.write(bb, pos)
                synchronized(lock) { written = pos; lock.notifyAll() }
            }
        } catch (t: Throwable) {
            error = t
        } finally {
            try { input.close() } catch (_: Throwable) {}
            synchronized(lock) { finished = true; lock.notifyAll() }
        }
    }

    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val avail = written
        if (position >= avail) return if (finished && position >= written) -1 else 0
        val n = minOf(len.toLong(), avail - position).toInt()
        val bb = ByteBuffer.wrap(dst, off, n)
        var total = 0
        while (bb.hasRemaining()) {
            val r = channel.read(bb, position + total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    override fun awaitData(position: Long, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(lock) {
            while (written <= position && !finished) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                lock.wait(left)
            }
        }
        return true
    }

    fun cancel() { cancelled = true }

    override fun close() {
        cancelled = true
        try { input.close() } catch (_: Throwable) {}
        try { channel.close() } catch (_: Throwable) {}
        try { raf.close() } catch (_: Throwable) {}
        try { file.delete() } catch (_: Throwable) {}
        try { onClose?.invoke() } catch (_: Throwable) {}
    }
}
