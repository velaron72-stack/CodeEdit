package app.codeedit.data

import app.codeedit.core.util.MiniJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Everything needed to bring a tab back after a restart or process death. Pure data, no Android types. */
data class TabRecord(
    val id: String,
    val uri: String?,
    val name: String,
    val languageId: String? = null,   // null = auto-detect
    val anchorLine: Int = 0, val anchorCol: Int = 0, val headLine: Int = 0, val headCol: Int = 0,
    val topLine: Int = 0, val topOffsetPx: Float = 0f, val scrollX: Float = 0f,
    val zoomSp: Float? = null,
    val encoding: String = "UTF-8", val bom: Boolean = false, val eol: String = "LF",
    val mode: String = "edit",       // "view" | "edit"
    val wrap: Boolean? = null,
    val hasDraft: Boolean = false,
    val knownSize: Long = -1L, val knownModified: Long = 0L,
    val fromExternal: Boolean = false,
    val modified: Boolean = false,
)

data class SessionRecord(val activeId: String?, val tabs: List<TabRecord>, val recentOrder: List<String> = emptyList())

object SessionCodec {
    const val VERSION = 1

    fun encode(r: SessionRecord): ByteArray {
        val tabs = r.tabs.map { t ->
            linkedMapOf<String, Any?>(
                "id" to t.id, "uri" to t.uri, "name" to t.name, "lang" to t.languageId,
                "sel" to listOf(t.anchorLine, t.anchorCol, t.headLine, t.headCol),
                "scroll" to listOf(t.topLine, t.topOffsetPx.toDouble(), t.scrollX.toDouble()),
                "zoom" to t.zoomSp?.toDouble(), "enc" to t.encoding, "bom" to t.bom, "eol" to t.eol, "mode" to t.mode,
                "wrap" to t.wrap, "draft" to t.hasDraft, "size" to t.knownSize, "mtime" to t.knownModified,
                "ext" to t.fromExternal, "mod" to t.modified,
            )
        }
        val root = linkedMapOf<String, Any?>("version" to VERSION, "active" to r.activeId, "tabs" to tabs, "mru" to r.recentOrder)
        return MiniJson.write(root).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): SessionRecord? {
        return try {
            val root = MiniJson.asObject(MiniJson.parse(bytes, false)) ?: return null
            val ver = (root["version"] as? Number)?.toInt() ?: return null
            if (ver > VERSION) return null
            val tabs = ArrayList<TabRecord>()
            for (o in MiniJson.asArray(root["tabs"]) ?: emptyList()) {
                val m = MiniJson.asObject(o) ?: continue
                val id = m["id"] as? String ?: continue
                val sel = MiniJson.asArray(m["sel"])?.map { (it as? Number)?.toInt() ?: 0 } ?: listOf(0, 0, 0, 0)
                val sc = MiniJson.asArray(m["scroll"])?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: listOf(0.0, 0.0, 0.0)
                tabs += TabRecord(
                    id = id, uri = m["uri"] as? String, name = m["name"] as? String ?: id, languageId = m["lang"] as? String,
                    anchorLine = sel.getOrElse(0) { 0 }.coerceAtLeast(0), anchorCol = sel.getOrElse(1) { 0 }.coerceAtLeast(0),
                    headLine = sel.getOrElse(2) { 0 }.coerceAtLeast(0), headCol = sel.getOrElse(3) { 0 }.coerceAtLeast(0),
                    topLine = sc.getOrElse(0) { 0.0 }.toInt().coerceAtLeast(0), topOffsetPx = sc.getOrElse(1) { 0.0 }.toFloat().coerceAtLeast(0f),
                    scrollX = sc.getOrElse(2) { 0.0 }.toFloat().coerceAtLeast(0f),
                    zoomSp = (m["zoom"] as? Number)?.toFloat(),
                    encoding = m["enc"] as? String ?: "UTF-8", bom = m["bom"] as? Boolean ?: false, eol = m["eol"] as? String ?: "LF",
                    mode = if (m["mode"] == "view") "view" else "edit", wrap = m["wrap"] as? Boolean,
                    hasDraft = m["draft"] as? Boolean ?: false, knownSize = (m["size"] as? Number)?.toLong() ?: -1L,
                    knownModified = (m["mtime"] as? Number)?.toLong() ?: 0L, fromExternal = m["ext"] as? Boolean ?: false,
                    modified = m["mod"] as? Boolean ?: false,
                )
            }
            SessionRecord(root["active"] as? String, tabs, MiniJson.asArray(root["mru"])?.filterIsInstance<String>() ?: emptyList())
        } catch (_: Throwable) { null }
    }
}

/** File layout: <dir>/session.json (+ .bak of the previous good copy), <dir>/drafts/<id>.txt (UTF-8), <dir>/recent.json */
class SessionFiles(private val dir: File) {
    private val sessionFile = File(dir, "session.json")
    private val draftsDir = File(dir, "drafts")

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.flush(); try { it.fd.sync() } catch (_: Throwable) {} }
        try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    fun writeSession(r: SessionRecord) {
        val bytes = SessionCodec.encode(r)
        if (sessionFile.exists()) try { Files.copy(sessionFile.toPath(), File(dir, "session.json.bak").toPath(), StandardCopyOption.REPLACE_EXISTING) } catch (_: Throwable) {}
        atomicWrite(sessionFile, bytes)
    }

    fun readSession(): SessionRecord? {
        for (f in listOf(sessionFile, File(dir, "session.json.bak"))) {
            if (!f.isFile) continue
            val r = try { SessionCodec.decode(f.readBytes()) } catch (_: Throwable) { null }
            if (r != null) return r
        }
        return null
    }

    fun writeDraft(id: String, text: String) = atomicWrite(File(draftsDir, safe(id) + ".txt"), text.toByteArray(Charsets.UTF_8))
    fun readDraft(id: String): String? = try { File(draftsDir, safe(id) + ".txt").takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (_: IOException) { null }
    fun hasDraft(id: String): Boolean = File(draftsDir, safe(id) + ".txt").isFile
    fun deleteDraft(id: String) { File(draftsDir, safe(id) + ".txt").delete() }
    fun draftIds(): Set<String> = draftsDir.listFiles()?.filter { it.name.endsWith(".txt") }?.map { it.name.removeSuffix(".txt") }?.toSet() ?: emptySet()

    /** Deletes only drafts that no tab refers to. */
    fun pruneOrphanDrafts(keep: Set<String>) { for (id in draftIds()) if (id !in keep.map { safe(it) }) File(draftsDir, "$id.txt").delete() }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    // ---- recent files
    data class Recent(val uri: String, val name: String, val time: Long)

    fun readRecent(): List<Recent> = try {
        val f = File(dir, "recent.json")
        if (!f.isFile) emptyList() else (MiniJson.asArray(MiniJson.parse(f.readBytes(), false)) ?: emptyList()).mapNotNull {
            val m = MiniJson.asObject(it) ?: return@mapNotNull null
            Recent(m["uri"] as? String ?: return@mapNotNull null, m["name"] as? String ?: "", (m["time"] as? Number)?.toLong() ?: 0L)
        }
    } catch (_: Throwable) { emptyList() }

    fun addRecent(r: Recent, max: Int = 30): List<Recent> {
        val list = (listOf(r) + readRecent().filter { it.uri != r.uri }).take(max)
        writeRecent(list)
        return list
    }

    fun removeRecent(uri: String): List<Recent> { val l = readRecent().filter { it.uri != uri }; writeRecent(l); return l }

    private fun writeRecent(list: List<Recent>) =
        atomicWrite(File(dir, "recent.json"), MiniJson.write(list.map { linkedMapOf<String, Any?>("uri" to it.uri, "name" to it.name, "time" to it.time) }).toByteArray(Charsets.UTF_8))
}
