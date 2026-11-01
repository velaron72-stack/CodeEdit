package app.codeedit.files

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.codeedit.core.io.FileChannelSource
import app.codeedit.core.io.FileSaveTarget
import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.SaveTarget
import app.codeedit.core.io.SpoolingSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executor

data class UriInfo(val name: String?, val size: Long, val lastModified: Long, val mime: String?)

/** SAF / file:// helpers. Nothing here needs a storage permission: access comes from the URI grant. */
object UriFiles {
    fun queryInfo(ctx: Context, uri: Uri): UriInfo {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return UriInfo(null, -1, 0, null))
            return UriInfo(f.name, if (f.exists()) f.length() else -1L, f.lastModified(), null)
        }
        var name: String? = null; var size = -1L; var mod = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!c.isNull(it)) name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { if (!c.isNull(it)) mod = c.getLong(it) }
                }
            }
        } catch (_: Throwable) { /* provider without query support: fall through with unknowns */ }
        val mime = try { ctx.contentResolver.getType(uri) } catch (_: Throwable) { null }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return UriInfo(name, size, mod, mime)
    }

    /** Opens for positional reads: seekable descriptors are read in place, everything else is spooled to a temp file in the background. */
    fun openSource(ctx: Context, uri: Uri, tempDir: File, executor: Executor?): RandomAccessSource {
        tempDir.mkdirs()
        if (uri.scheme == "file") {
            val f = File(uri.path ?: throw FileNotFoundException(uri.toString()))
            val raf = RandomAccessFile(f, "r")
            return FileChannelSource(raf.channel, f.lastModified()) { try { raf.close() } catch (_: Throwable) {} }
        }
        val info = queryInfo(ctx, uri)
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null && pfd.statSize >= 0) {
                val fis = FileInputStream(pfd.fileDescriptor)
                val ch = fis.channel
                val ok = try { ch.position(0); ch.size() >= 0 } catch (_: Throwable) { false }
                if (ok) {
                    val p = pfd
                    return FileChannelSource(ch, info.lastModified) { try { fis.close() } catch (_: Throwable) {}; try { p.close() } catch (_: Throwable) {} }
                }
            }
        } catch (_: FileNotFoundException) {
            try { pfd?.close() } catch (_: Throwable) {}
            throw FileNotFoundException(uri.toString())
        } catch (_: Throwable) { /* not a plain descriptor: use the stream */ }
        try { pfd?.close() } catch (_: Throwable) {}
        val input = ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        return SpoolingSource(input, tempDir, info.size, executor, info.lastModified) { try { input.close() } catch (_: Throwable) {} }
    }

    fun takePersistable(ctx: Context, uri: Uri, write: Boolean): Boolean {
        if (uri.scheme != "content") return false
        return try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            ctx.contentResolver.takePersistableUriPermission(uri, flags); true
        } catch (_: Throwable) { false }
    }

    fun canWrite(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return File(uri.path ?: return false).let { it.canWrite() || (!it.exists() && it.absoluteFile.parentFile?.canWrite() == true) }
        return try {
            ctx.checkCallingOrSelfUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        } catch (_: Throwable) { false }
    }

    /** Truncates the destination and fills it from [src]; throws if anything fails or the final size is wrong. */
    fun overwrite(ctx: Context, uri: Uri, src: File) {
        val n = src.length()
        if (uri.scheme == "file") { src.copyTo(File(uri.path!!), overwrite = true); return }
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "wt") ?: throw IOException("cannot open destination for writing")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { dst ->
                FileInputStream(src).use { it.copyTo(dst, 1 shl 20) }
                dst.flush()
                try { dst.fd.sync() } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            throw IOException("writing the destination failed: ${e.message}", e)
        }
        val after = queryInfo(ctx, uri).size
        if (after >= 0 && after != n) throw IOException("destination size $after differs from written $n")
    }

    fun saveTarget(ctx: Context, uri: Uri, tempDir: File, backup: Boolean): SaveTarget =
        if (uri.scheme == "file") FileSaveTarget(File(uri.path!!), backup) else UriSaveTarget(ctx, uri, tempDir)
}

/**
 * Save to a content:// document. The provider API gives no atomic replace, so the new content is first written
 * completely to a temp file; only then is the destination opened (truncating) and filled from it. If the final copy
 * fails the caller keeps the draft and the document stays "modified"; the temp file is kept for the retry window.
 */
class UriSaveTarget(private val ctx: Context, private val uri: Uri, private val tempDir: File) : SaveTarget {
    private var tmp: File? = null
    private var out: OutputStream? = null
    var bytes: Long = 0L; private set

    override fun openOutput(): OutputStream {
        tempDir.mkdirs()
        val t = File.createTempFile("save", ".tmp", tempDir)
        tmp = t
        val o = BufferedOutputStream(FileOutputStream(t), 256 * 1024)
        out = o
        return o
    }

    override fun commit() {
        val t = tmp ?: throw IOException("openOutput() was not called")
        try { out?.flush(); out?.close() } finally { out = null }
        bytes = t.length()
        UriFiles.overwrite(ctx, uri, t)
        t.delete(); tmp = null
    }

    override fun abort() {
        try { out?.close() } catch (_: Throwable) {}
        out = null
        try { tmp?.delete() } catch (_: Throwable) {}
        tmp = null
    }
}
