package app.codeedit.core.io

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Destination of a save. Protocol: openOutput() -> write everything -> close the stream -> commit().
 * On any failure call abort(); the previous destination content must stay untouched until commit().
 */
interface SaveTarget {
    fun openOutput(): OutputStream
    fun commit()
    fun abort()
}

/** Writes to a temp file next to the destination, fsyncs, then atomically renames over it (optionally keeping name.bak). */
class FileSaveTarget(val file: File, val keepBackup: Boolean = false) : SaveTarget {
    private var tmp: File? = null
    private var fos: FileOutputStream? = null
    private var out: OutputStream? = null

    override fun openOutput(): OutputStream {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for $file")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val t = File.createTempFile("." + file.name, ".tmp", dir)
        tmp = t
        val f = FileOutputStream(t)
        fos = f
        val o = BufferedOutputStream(f, 256 * 1024)
        out = o
        return o
    }

    override fun commit() {
        val t = tmp ?: throw IOException("openOutput() was not called")
        try {
            out?.flush()
            try { fos?.fd?.sync() } catch (_: Throwable) {}
            out?.close()
        } finally { out = null; fos = null }
        if (keepBackup && file.exists()) {
            try { Files.copy(file.toPath(), File(file.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING) } catch (_: Throwable) {}
        }
        try {
            Files.move(t.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(t.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        tmp = null
    }

    override fun abort() {
        try { out?.close() } catch (_: Throwable) {}
        out = null; fos = null
        try { tmp?.delete() } catch (_: Throwable) {}
        tmp = null
    }
}

data class SaveResult(val ok: Boolean, val bytesWritten: Long, val error: Throwable?)
