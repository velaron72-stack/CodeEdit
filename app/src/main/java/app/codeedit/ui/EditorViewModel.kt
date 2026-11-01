package app.codeedit.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.codeedit.BuildConfig
import app.codeedit.CodeEditApp
import app.codeedit.core.doc.Coalesce
import app.codeedit.core.doc.DocInfo
import app.codeedit.core.doc.DocMode
import app.codeedit.core.doc.DocumentListener
import app.codeedit.core.doc.DocumentOpener
import app.codeedit.core.doc.DocumentSaver
import app.codeedit.core.doc.EditDocument
import app.codeedit.core.doc.Eol
import app.codeedit.core.doc.LineStore
import app.codeedit.core.doc.OpenOptions
import app.codeedit.core.doc.OpenResult
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.doc.TextEncoding
import app.codeedit.core.doc.ViewDocument
import app.codeedit.core.edit.EditorSession
import app.codeedit.core.io.RandomAccessSource
import app.codeedit.core.io.SaveResult
import app.codeedit.core.io.TextCodec
import app.codeedit.data.K
import app.codeedit.data.Lang
import app.codeedit.data.Schema
import app.codeedit.data.SessionFiles
import app.codeedit.data.SessionRecord
import app.codeedit.data.Settings
import app.codeedit.data.TabRecord
import app.codeedit.data.tr
import app.codeedit.editor.EditorListener
import app.codeedit.editor.EditorView
import app.codeedit.files.UriFiles
import app.codeedit.files.UriInfo
import app.codeedit.highlight.PLAIN_TEXT_ID
import app.codeedit.highlight.ThemeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MB = 1024L * 1024L

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    val app: CodeEditApp = application as CodeEditApp
    internal val files: SessionFiles get() = app.sessionFiles
    val highlight get() = app.highlight

    // ------------------------------------------------------------------ observable state
    val tabs = mutableStateListOf<Tab>()
    var activeId by mutableStateOf<String?>(null); private set
    val active: Tab? get() = tabs.firstOrNull { it.id == activeId }
    var settings by mutableStateOf(Settings()); private set
    var ready by mutableStateOf(false); private set
    var screen by mutableStateOf<Screen>(Screen.Editor)
    var dialog by mutableStateOf<DialogState?>(null)
    var message by mutableStateOf<UiMessage?>(null); private set
    var progress by mutableStateOf<ProgressState?>(null)
    var recent by mutableStateOf<List<SessionFiles.Recent>>(emptyList()); private set
    var syntaxTheme by mutableStateOf<ThemeInfo?>(null); private set
    var pendingSaveAs by mutableStateOf<SaveAsRequest?>(null)
    var cursorLine by androidx.compose.runtime.mutableIntStateOf(1); private set
    var cursorCol by androidx.compose.runtime.mutableIntStateOf(1); private set
    var selectionLength by androidx.compose.runtime.mutableIntStateOf(0); private set

    /** Live editor view (set by the UI host while it is attached). */
    var editor: EditorView? = null
    val search = SearchController(this)

    private val readyGate = CompletableDeferred<Unit>()
    private val diskExec = Executors.newSingleThreadExecutor { r -> Thread(r, "disk").apply { isDaemon = true } }
    private var launched = false
    private val mru = ArrayList<String>()
    private val persistRunnable = Runnable { persistNow() }

    init {
        viewModelScope.launch { app.settingsRepo.flow.collect { applySettings(it) } }
        viewModelScope.launch {
            val s = app.settingsRepo.current()
            applySettings(s)
            recent = withContext(Dispatchers.IO) { files.readRecent() }
            if (s.bool(K.F_RESTORE)) {
                val rec = withContext(Dispatchers.IO) { files.readSession() }
                if (rec != null) restore(rec)
            }
            ready = true
            readyGate.complete(Unit)
        }
    }

    // ------------------------------------------------------------------ settings
    private fun applySettings(s: Settings) {
        val old = settings
        settings = s
        Lang.init(Locale.getDefault().language, s.str(K.LANGUAGE))
        highlight?.maxTokenizeLineChars = s.int(K.HL_MAX_LINE)
        if (old.bool(K.HL_ENABLED) != s.bool(K.HL_ENABLED) || old.int(K.HL_MAX_MB) != s.int(K.HL_MAX_MB)) {
            for (t in tabs) if (t.session != null) attachHighlighter(t)
        }
        editor?.relayout()
    }

    fun setSetting(key: String, value: Any) { viewModelScope.launch { app.settingsRepo.set(key, value) } }

    /** Syntax theme for the current UI darkness. */
    fun ensureSyntaxTheme(dark: Boolean) {
        val hs = highlight ?: return
        val id = settings.str(if (dark) K.HL_DARK else K.HL_LIGHT)
        if (syntaxTheme?.id == id) return
        syntaxTheme = hs.setTheme(id)
        editor?.invalidate()
    }

    fun pickSyntaxTheme(id: String, dark: Boolean) { setSetting(if (dark) K.HL_DARK else K.HL_LIGHT, id) }

    // ------------------------------------------------------------------ messages
    fun toast(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) { message = UiMessage(text, actionLabel, action) }

    // ------------------------------------------------------------------ launch / intents
    /** Called by the activity once per VM instance with its launch intent, and for every onNewIntent. */
    fun onIntent(intent: Intent?, fresh: Boolean) {
        if (intent == null) return
        if (fresh) { if (launched) return; launched = true }
        viewModelScope.launch { readyGate.await(); handleIntent(intent) }
    }

    private fun handleIntent(i: Intent) {
        val uris = ArrayList<Uri>()
        var text: String? = null
        when (i.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> { i.data?.let { uris += it } }
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
                if (uris.isEmpty()) text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
            }
            else -> return
        }
        if (uris.isEmpty()) i.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris += it } }
        screen = Screen.Editor
        val persistable = (i.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        if (uris.isNotEmpty()) openUris(uris, external = true, persist = persistable, write = i.action == Intent.ACTION_EDIT || (i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        else if (!text.isNullOrEmpty()) newDocument(text, name = tr("Из другого приложения.txt", "Shared text.txt"), external = true)
    }

    // ------------------------------------------------------------------ session restore / persist
    private fun restore(rec: SessionRecord) {
        for (r in rec.tabs) {
            val t = Tab(r.id)
            t.record = r
            t.uri = r.uri?.let { Uri.parse(it) }
            t.name = r.name
            t.mode = if (r.mode == "view") DocMode.VIEW else DocMode.EDIT
            t.manualLanguage = r.languageId
            t.fromExternal = r.fromExternal
            t.restoredDirty = r.modified && r.hasDraft
            t.modified = t.restoredDirty
            t.hasDraft = r.hasDraft
            t.knownSize = r.knownSize; t.knownModified = r.knownModified
            t.encodingLabel = TextEncoding(r.encoding, r.bom).label; t.eolLabel = r.eol
            tabs += t
        }
        mru.clear(); mru.addAll(rec.recentOrder.filter { id -> tabs.any { it.id == id } })
        val a = rec.activeId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
        diskExec.execute { files.pruneOrphanDrafts(rec.tabs.map { it.id }.toSet()) }
        if (a != null) activate(a)
    }

    private fun recordOf(t: Tab): TabRecord {
        val s = t.session
        val base = t.record
        if (s == null) {
            val r = base ?: TabRecord(t.id, t.uri?.toString(), t.name)
            return r.copy(uri = t.uri?.toString(), name = t.name, languageId = t.manualLanguage, hasDraft = t.hasDraft, modified = t.dirty,
                fromExternal = t.fromExternal, knownSize = t.knownSize, knownModified = t.knownModified, mode = if (t.mode == DocMode.VIEW) "view" else "edit")
        }
        val sel = s.selection
        return TabRecord(
            id = t.id, uri = t.uri?.toString(), name = t.name, languageId = t.manualLanguage,
            anchorLine = sel.anchor.line, anchorCol = sel.anchor.col, headLine = sel.head.line, headCol = sel.head.col,
            topLine = s.topLine, topOffsetPx = s.topOffsetPx, scrollX = s.scrollX,
            zoomSp = s.fontSizeSp.takeIf { !it.isNaN() }, encoding = s.encoding.charsetName, bom = s.encoding.bom, eol = s.eol.name,
            mode = if (s.mode == DocMode.VIEW) "view" else "edit", wrap = s.wrapOverride, hasDraft = t.hasDraft,
            knownSize = t.knownSize, knownModified = t.knownModified, fromExternal = t.fromExternal, modified = t.dirty,
        )
    }

    fun persistSoon() { app.main.removeCallbacks(persistRunnable); app.main.postDelayed(persistRunnable, 900) }

    private fun snapshot(): SessionRecord = SessionRecord(activeId, tabs.map { recordOf(it) }, mru.toList())

    fun persistNow() {
        app.main.removeCallbacks(persistRunnable)
        if (!ready) return
        val rec = snapshot()
        diskExec.execute { try { files.writeSession(rec) } catch (_: Throwable) {} }
    }

    /** Blocks briefly (bounded) so that state is on disk before the app goes away. */
    fun flushNow() {
        if (!ready) return
        app.main.removeCallbacks(persistRunnable)
        for (t in tabs) if (t.session != null && t.dirty) writeDraft(t, sync = false)
        val rec = snapshot()
        try { diskExec.submit { try { files.writeSession(rec) } catch (_: Throwable) {} }.get(3, TimeUnit.SECONDS) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ activation, loading
    fun activate(id: String) {
        val t = tabs.firstOrNull { it.id == id } ?: return
        if (activeId != id) { search.onTabChanged(); activeId = id }
        t.lastUsed = System.nanoTime()
        mru.remove(id); mru.add(0, id)
        if (t.state == TabState.UNLOADED) load(t)
        enforceMemoryLimit()
        if (t.session != null) checkExternal(t)
        persistSoon()
    }

    private sealed class LoadResult {
        class Ok(val store: LineStore, val info: DocInfo, val source: RandomAccessSource?, val fromDraft: Boolean, val stamp: UriInfo?, val extChanged: Boolean) : LoadResult()
        class Binary(val reason: String) : LoadResult()
        class Fail(val error: Throwable) : LoadResult()
    }

    fun load(tab: Tab, forceMode: DocMode? = null, forceEncoding: TextEncoding? = null, ignoreDraft: Boolean = false) {
        if (tab.state == TabState.LOADING) return
        tab.state = TabState.LOADING; tab.error = null
        val s = settings
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { loadBlocking(tab, s, forceMode, forceEncoding, ignoreDraft) }
            when (res) {
                is LoadResult.Ok -> install(tab, res)
                is LoadResult.Binary -> { tab.state = TabState.FAILED; tab.error = res.reason; dialog = DialogState.Binary(tab, res.reason) }
                is LoadResult.Fail -> { tab.state = TabState.FAILED; tab.error = describe(res.error) }
            }
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.io.FileNotFoundException -> tr("Файл недоступен или удалён", "File is unavailable or deleted")
        is SecurityException -> tr("Нет доступа к файлу (разрешение отозвано)", "No access to the file (permission revoked)")
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun loadBlocking(tab: Tab, s: Settings, forceMode: DocMode?, forceEncoding: TextEncoding?, ignoreDraft: Boolean): LoadResult {
        val rec = tab.record
        val uri = tab.uri
        try {
            if (!ignoreDraft && (tab.hasDraft || rec?.hasDraft == true)) {
                val text = files.readDraft(tab.id)
                if (text != null) {
                    val enc = TextEncoding(rec?.encoding ?: "UTF-8", rec?.bom ?: false)
                    val eol = try { Eol.valueOf(rec?.eol ?: "LF") } catch (_: Throwable) { Eol.LF }
                    val info = DocInfo(DocMode.EDIT, enc, eol, false, text.length.toLong(), text.endsWith("\n"))
                    val doc = DocumentOpener.fromText(text, info)
                    val st = if (uri != null) runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() else null
                    val changed = st != null && rec != null && rec.knownSize >= 0 &&
                        ((st.size >= 0 && st.size != rec.knownSize) || (st.lastModified != 0L && rec.knownModified != 0L && st.lastModified != rec.knownModified))
                    return LoadResult.Ok(doc, info, null, true, st, changed)
                }
            }
            if (uri == null) {
                val eol = when (s.str(K.F_EOL)) { "crlf" -> Eol.CRLF; "cr" -> Eol.CR; else -> Eol.LF }
                val (doc, info0) = DocumentOpener.newDocument("", eol)
                val encName = s.str(K.F_ENC)
                val info = if (encName != "auto") info0.copy(encoding = TextEncoding(encName, false)) else info0
                return LoadResult.Ok(doc, info, null, false, null, false)
            }
            val source = UriFiles.openSource(app, uri, app.tempDir, app.ioPool)
            val opts = OpenOptions(forceMode = forceMode, forceEncoding = forceEncoding, editMaxBytes = s.int(K.F_EDIT_MB) * MB, indexExecutor = app.ioPool)
            val stamp = runCatching { UriFiles.queryInfo(app, uri) }.getOrNull()
            return when (val r = DocumentOpener.open(source, tab.name, opts)) {
                is OpenResult.Ok -> {
                    if (r.info.mode == DocMode.EDIT) { try { source.close() } catch (_: Throwable) {}; LoadResult.Ok(r.doc, r.info, null, false, stamp, false) }
                    else LoadResult.Ok(r.doc, r.info, source, false, stamp, false)
                }
                is OpenResult.Binary -> { try { source.close() } catch (_: Throwable) {}; LoadResult.Binary(r.reason) }
                is OpenResult.Failure -> { try { source.close() } catch (_: Throwable) {}; LoadResult.Fail(r.error) }
            }
        } catch (t: Throwable) { return LoadResult.Fail(t) }
    }

    private fun install(tab: Tab, r: LoadResult.Ok) {
        if (tabs.none { it === tab }) { try { r.store.close() } catch (_: Throwable) {}; return }   // closed while loading
        val rec = tab.record
        val session = EditorSession(tab.id, r.store, r.info)
        if (rec != null) {
            session.encoding = TextEncoding(rec.encoding, rec.bom)
            session.eol = try { Eol.valueOf(rec.eol) } catch (_: Throwable) { r.info.eol }
            if (!r.fromDraft && rec.encoding != r.info.encoding.charsetName) session.encoding = r.info.encoding
            session.selection = session.clampSelection(Selection(Pos(rec.anchorLine, rec.anchorCol), Pos(rec.headLine, rec.headCol)))
            session.topLine = rec.topLine.coerceIn(0, (r.store.lineCount - 1).coerceAtLeast(0)); session.topOffsetPx = rec.topOffsetPx; session.scrollX = rec.scrollX
            session.fontSizeSp = rec.zoomSp ?: Float.NaN
            session.wrapOverride = rec.wrap
        }
        tab.session = session
        tab.mode = r.info.mode
        tab.state = TabState.READY
        tab.restoredDirty = r.fromDraft && (tab.restoredDirty || rec?.modified == true)
        tab.hasDraft = r.fromDraft
        tab.draftVersion = (session.editable?.contentVersion ?: -1L)
        if (r.stamp != null) { tab.knownSize = if (r.fromDraft) tab.knownSize else r.stamp.size; tab.knownModified = if (r.fromDraft) tab.knownModified else r.stamp.lastModified }
        if (r.extChanged) tab.externalChanged = true
        tab.encodingLabel = session.encoding.label; tab.eolLabel = session.eol.name
        tab.lineCount = r.store.lineCount; tab.indexProgress = r.store.indexProgress
        tab.modified = tab.dirty
        r.store.addListener(object : DocumentListener {
            override fun onIndexProgress(lineCount: Int, complete: Boolean) {
                app.main.post { tab.indexProgress = r.store.indexProgress; tab.lineCount = r.store.lineCount }
            }
        })
        tab.record = null
        resolveLanguage(tab)
        attachHighlighter(tab)
        tab.initialText?.let { txt ->
            tab.initialText = null
            session.editable?.let { d ->
                d.replace(Pos(0, 0), Pos(0, 0), EditDocument.normalizeEol(txt), null, null, Coalesce.NONE)
                session.selection = Selection.ZERO
                tab.modified = tab.dirty
            }
        }
        tab.sessionVersion++
        if (tab.restoredDirty && tab.externalChanged) toast(tr("«${tab.name}»: файл на диске изменился после черновика", "\"${tab.name}\": the file changed on disk after the draft"))
    }

    // ------------------------------------------------------------------ language / highlight
    private fun resolveLanguage(tab: Tab) {
        val s = tab.session ?: return
        val hs = highlight
        val manual = tab.manualLanguage
        val lang = when {
            hs == null -> null
            manual != null -> hs.languageById(manual)
            else -> hs.detectLanguage(tab.name, try { s.store.getLine(0).take(300) } catch (_: Throwable) { null })
        }
        tab.languageId = if (manual == PLAIN_TEXT_ID) PLAIN_TEXT_ID else lang?.id ?: PLAIN_TEXT_ID
        tab.languageName = if (tab.languageId == PLAIN_TEXT_ID) "Plain Text" else lang?.name ?: "Plain Text"
        s.languageId = tab.languageId
    }

    fun attachHighlighter(tab: Tab) {
        val s = tab.session ?: return
        s.highlighter?.close(); s.highlighter = null
        tab.highlightNote = null
        val hs = highlight
        if (hs == null) { tab.highlightNote = app.highlightError ?: tr("Пакет грамматик недоступен", "Grammar package unavailable"); return }
        if (!settings.bool(K.HL_ENABLED) || tab.languageId == PLAIN_TEXT_ID) return
        if (s.info.sizeBytes > settings.int(K.HL_MAX_MB) * MB) { tab.highlightNote = tr("Файл больше лимита подсветки", "File exceeds the highlight size limit"); return }
        val h = hs.createHighlighter(s.store, tab.languageId)
        if (h == null) tab.highlightNote = if (hs.engineAvailable) tr("Для языка нет грамматики", "No grammar for this language") else tr("Нативный движок не загружен", "Native engine not loaded")
        s.highlighter = h
        editor?.invalidate()
    }

    fun setLanguage(tab: Tab, languageId: String?) {
        tab.manualLanguage = languageId
        resolveLanguage(tab); attachHighlighter(tab); persistSoon()
    }

    // ------------------------------------------------------------------ opening
    fun openUris(uris: List<Uri>, external: Boolean = false, persist: Boolean = true, write: Boolean = true) {
        viewModelScope.launch {
            for (uri in uris) {
                if (persist) UriFiles.takePersistable(app, uri, write)
                val existing = tabs.firstOrNull { it.uri == uri }
                if (existing != null) {
                    if (external) existing.fromExternal = true
                    activate(existing.id)
                    if (existing.state == TabState.FAILED) { existing.state = TabState.UNLOADED; load(existing) }
                    continue
                }
                if (!makeRoomForTab()) return@launch
                val info = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() }
                val t = Tab(newId())
                t.uri = uri
                t.name = info?.name ?: uri.lastPathSegment ?: "file"
                t.fromExternal = external
                t.knownSize = info?.size ?: -1L; t.knownModified = info?.lastModified ?: 0L
                tabs += t
                activate(t.id)
                addRecent(uri, t.name)
            }
            screen = Screen.Editor
        }
    }

    private fun makeRoomForTab(): Boolean {
        val max = settings.int(K.T_MAX)
        if (tabs.size < max) return true
        if (settings.str(K.T_LIMIT) == "closeOldest") {
            val victim = tabs.filter { !it.dirty && it.id != activeId }.minByOrNull { it.lastUsed }
            if (victim != null) { closeNow(victim); return true }
        }
        toast(tr("Достигнут лимит вкладок ($max)", "Tab limit reached ($max)"))
        return false
    }

    fun newDocument(text: String = "", name: String? = null, external: Boolean = false) {
        if (!makeRoomForTab()) return
        val t = Tab(newId())
        t.name = name ?: tr("Без имени ${tabs.count { it.uri == null } + 1}.txt", "Untitled ${tabs.count { it.uri == null } + 1}.txt")
        t.fromExternal = external
        tabs += t
        activate(t.id)
        if (text.isNotEmpty()) t.initialText = text
        screen = Screen.Editor
    }

    fun addRecent(uri: Uri, name: String) {
        diskExec.execute { try { val l = files.addRecent(SessionFiles.Recent(uri.toString(), name, System.currentTimeMillis())); app.main.post { recent = l } } catch (_: Throwable) {} }
    }

    fun removeRecent(uri: String) {
        diskExec.execute { try { val l = files.removeRecent(uri); app.main.post { recent = l } } catch (_: Throwable) {} }
    }

    fun openFolder(tree: Uri) {
        UriFiles.takePersistable(app, tree, true)
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, tree)?.name ?: tree.lastPathSegment ?: "folder"
        addRecent(tree, "$name/")
        screen = Screen.Folder(tree, name)
    }

    fun openRecent(r: SessionFiles.Recent) {
        val uri = Uri.parse(r.uri)
        if (r.name.endsWith("/")) { screen = Screen.Folder(uri, r.name.removeSuffix("/")) } else openUris(listOf(uri), persist = false)
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // ------------------------------------------------------------------ memory management
    private fun enforceMemoryLimit() {
        val limit = settings.int(K.T_MEM)
        val loaded = tabs.filter { it.session != null && it.id != activeId }
        if (loaded.size + 1 <= limit) return
        val victims = loaded.sortedBy { it.lastUsed }.take(loaded.size + 1 - limit)
        for (v in victims) unload(v)
    }

    /** Frees the document of a background tab; unsaved edits go to the draft first, so nothing is lost. */
    private fun unload(tab: Tab) {
        val s = tab.session ?: return
        if (tab.id == activeId) return
        if (tab.dirty) writeDraft(tab, sync = true)
        tab.record = recordOf(tab)
        tab.search?.close(); tab.search = null
        tab.session = null
        s.close()
        tab.state = TabState.UNLOADED
        tab.restoredDirty = tab.restoredDirty || tab.record?.modified == true
        tab.sessionVersion++
    }

    // ------------------------------------------------------------------ drafts / autosave
    fun onEditorContentChanged() {
        val t = active ?: return
        t.modified = t.dirty
        scheduleAutosave(t)
        search.onDocumentEdited()
        persistSoon()
    }

    private fun scheduleAutosave(t: Tab) {
        if (settings.str(K.F_AUTOSAVE) == "off" || t.draftScheduled) return
        t.draftScheduled = true
        val len = t.session?.editable?.length ?: 0
        val base = settings.int(K.F_AUTOSAVE_SEC) * 1000L
        val delay = if (len > 8_000_000) maxOf(base, 30_000L) else base
        app.main.postDelayed({ t.draftScheduled = false; autosaveTick(t) }, delay)
    }

    private fun autosaveTick(t: Tab) {
        if (t.session == null || !t.dirty || tabs.none { it === t }) return
        writeDraft(t, sync = false)
        if (settings.str(K.F_AUTOSAVE) == "file" && t.uri != null && t.editable && !t.externalChanged && !t.saving) save(t, silent = true)
    }

    private fun writeDraft(t: Tab, sync: Boolean) {
        val doc = t.session?.editable ?: return
        val ver = doc.contentVersion
        if (ver == t.draftVersion && t.hasDraft) return
        val text = doc.fullText()
        t.draftVersion = ver; t.hasDraft = true
        val job = Runnable {
            try { files.writeDraft(t.id, text) }
            catch (e: Throwable) { app.main.post { t.draftVersion = -1; t.hasDraft = files.hasDraft(t.id); toast(tr("Не удалось записать черновик: ${e.message}", "Could not write the draft: ${e.message}")) } }
        }
        if (sync) { try { diskExec.submit(job).get(10, TimeUnit.SECONDS) } catch (_: Throwable) {} } else diskExec.execute(job)
    }

    private fun deleteDraft(t: Tab) {
        t.hasDraft = false; t.draftVersion = -1
        diskExec.execute { files.deleteDraft(t.id) }
    }

    // ------------------------------------------------------------------ saving
    fun saveActive(then: (() -> Unit)? = null) { active?.let { save(it, false, false, then) } }

    fun changedSince(t: Tab, st: UriInfo): Boolean =
        (st.size >= 0 && t.knownSize >= 0 && st.size != t.knownSize) || (st.lastModified != 0L && t.knownModified != 0L && st.lastModified != t.knownModified)

    fun save(tab: Tab, silent: Boolean = false, force: Boolean = false, then: (() -> Unit)? = null) {
        val s = tab.session ?: return
        val doc = s.editable
        if (doc == null) { if (!silent) toast(tr("Файл открыт только для просмотра — включите редактирование", "File is read-only (fast view) — enable editing first")); return }
        val uri = tab.uri
        if (uri == null) { if (!silent) pendingSaveAs = SaveAsRequest(tab, then); return }
        if (tab.saving) return
        tab.saving = true
        viewModelScope.launch {
            try {
                if (!force) {
                    val st = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() }
                    if (st != null && changedSince(tab, st)) {
                        tab.externalChanged = true
                        if (!silent) dialog = DialogState.ExternalOverwrite(tab, then)
                        return@launch
                    }
                }
                val version = doc.contentVersion
                val text = doc.fullText()
                val enc = s.encoding; val eol = s.eol
                val finalNl = settings.bool(K.F_FINAL_NL); val backup = settings.bool(K.F_BACKUP)
                val name = tab.name
                val res: SaveResult = withContext(Dispatchers.IO) {
                    try {
                        if (backup) makeBackup(uri, name)
                        val snap = EditDocument.fromString(text)
                        DocumentSaver.save(snap, enc, eol, finalNl, UriFiles.saveTarget(app, uri, app.tempDir, false))
                    } catch (t: Throwable) { SaveResult(false, 0, t) }
                }
                if (res.ok) {
                    val st = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() }
                    if (doc.contentVersion == version) { doc.markSaved(); tab.restoredDirty = false; deleteDraft(tab) }
                    tab.knownSize = st?.size ?: res.bytesWritten; tab.knownModified = st?.lastModified ?: 0L
                    tab.modified = tab.dirty; tab.externalChanged = false
                    addRecent(uri, tab.name)
                    persistSoon()
                    if (!silent) toast(tr("Сохранено", "Saved"))
                    then?.invoke()
                } else {
                    writeDraft(tab, sync = false)
                    val msg = res.error?.message ?: res.error?.javaClass?.simpleName ?: "?"
                    val unenc = res.error is app.codeedit.core.doc.UnencodableException
                    if (silent) toast(tr("Автосохранение не удалось: $msg", "Autosave failed: $msg"))
                    else dialog = DialogState.Error(
                        tr("Не удалось сохранить", "Save failed"),
                        if (unenc) tr("В тексте есть символы, которые нельзя записать в кодировке ${enc.label}. Смените кодировку (меню → Кодировка) или сохраните как UTF-8. Исходный файл не изменён.",
                            "The text contains characters that ${enc.label} cannot represent. Change the encoding (menu → Encoding) or save as UTF-8. The original file is untouched.")
                        else msg + "\n\n" + tr("Изменения сохранены в черновике.", "Your changes are kept in a draft."),
                        tr("Повторить", "Retry"), { save(tab, false, true, then) })
                }
            } finally { tab.saving = false }
        }
    }

    private fun makeBackup(uri: Uri, name: String) {
        try {
            val dir = File(app.filesDir, "backups").also { it.mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
            val input = (if (uri.scheme == "file") java.io.FileInputStream(File(uri.path!!)) else app.contentResolver.openInputStream(uri)) ?: return
            input.use { ins -> File(dir, "$safe.$ts.bak").outputStream().use { o -> ins.copyTo(o, 256 * 1024) } }
            dir.listFiles { f -> f.name.startsWith("$safe.") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        } catch (_: Throwable) { /* a missing backup must not block saving */ }
    }

    fun onSaveAsResult(req: SaveAsRequest, uri: Uri?) {
        pendingSaveAs = null
        if (uri == null) return
        val tab = req.tab
        UriFiles.takePersistable(app, uri, true)
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() }
            tab.uri = uri
            tab.name = info?.name ?: tab.name
            tab.knownSize = -1; tab.knownModified = 0
            resolveLanguage(tab); attachHighlighter(tab)
            save(tab, false, true, req.then)
        }
    }

    // ------------------------------------------------------------------ external modification
    fun checkExternal(tab: Tab) {
        val uri = tab.uri ?: return
        if (tab.state != TabState.READY || tab.saving) return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() } ?: return@launch
            if (st.size < 0 && st.lastModified == 0L) return@launch
            if (changedSince(tab, st) && !tab.externalChanged) {
                if (!tab.dirty) {
                    reloadFromDisk(tab)
                    toast(tr("«${tab.name}» обновлён с диска", "\"${tab.name}\" reloaded from disk"))
                } else tab.externalChanged = true
            }
        }
    }

    fun onResume() { for (t in tabs) if (t.session != null) checkExternal(t) }

    fun reloadFromDisk(tab: Tab) {
        val s = tab.session
        tab.record = if (s != null) recordOf(tab) else tab.record
        tab.search?.close(); tab.search = null
        s?.close(); tab.session = null
        deleteDraft(tab)
        tab.restoredDirty = false; tab.externalChanged = false; tab.modified = false
        tab.state = TabState.UNLOADED
        tab.sessionVersion++
        tab.record = tab.record?.copy(hasDraft = false, modified = false)
        load(tab, ignoreDraft = true)
    }

    fun keepMine(tab: Tab) {
        val uri = tab.uri ?: return
        tab.externalChanged = false
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { UriFiles.queryInfo(app, uri) }.getOrNull() }
            if (st != null) { tab.knownSize = st.size; tab.knownModified = st.lastModified }
        }
    }

    // ------------------------------------------------------------------ closing / ordering
    fun requestClose(tab: Tab) {
        if (tab.dirty && settings.bool(K.T_CONFIRM)) dialog = DialogState.ConfirmClose(listOf(tab), false) else closeNow(tab)
    }

    fun requestCloseMany(list: List<Tab>, all: Boolean) {
        if (list.isEmpty()) return
        if (list.any { it.dirty } && settings.bool(K.T_CONFIRM)) dialog = DialogState.ConfirmClose(list, all) else list.forEach { closeNow(it) }
    }

    fun closeNow(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        if (activeId == tab.id) search.onTabChanged()
        tab.search?.close(); tab.search = null
        tab.session?.close(); tab.session = null
        deleteDraft(tab)
        tabs.removeAt(idx)
        mru.remove(tab.id)
        if (activeId == tab.id) {
            val next = if (settings.str(K.T_CLOSE) == "recent") mru.firstOrNull { id -> tabs.any { it.id == id } }
            else (tabs.getOrNull(idx) ?: tabs.getOrNull(idx - 1))?.id
            activeId = null
            if (next != null) activate(next) else editor?.bind(null)
        }
        persistSoon()
    }

    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        tabs.add(to, tabs.removeAt(from)); persistSoon()
    }

    fun saveThenClose(tabsToClose: List<Tab>) {
        val dirty = tabsToClose.filter { it.dirty }
        if (dirty.isEmpty()) { tabsToClose.forEach { closeNow(it) }; return }
        var remaining = dirty.size
        for (t in dirty) {
            save(t, false, false) { remaining--; if (remaining == 0) tabsToClose.forEach { closeNow(it) } }
        }
    }

    // ------------------------------------------------------------------ editor listener
    val editorListener = object : EditorListener {
        override fun onSelectionChanged() {
            active?.session?.selection?.let { sel ->
                val base = settings.str(K.LN_BASE).toIntOrNull() ?: 1
                cursorLine = sel.head.line + base; cursorCol = sel.head.col + 1
                selectionLength = if (sel.isEmpty) 0 else if (sel.start.line == sel.end.line) sel.end.col - sel.start.col else -1
            }
            persistSoon()
        }
        override fun onContentChanged() { onEditorContentChanged() }
        override fun onFontSizeChanged(sp: Float) { persistSoon() }
        override fun onRequestFind() { search.open(false) }
        override fun onRequestReplace() { search.open(true) }
        override fun onRequestSave() { saveActive() }
        override fun onRequestGoto() { dialog = DialogState.Goto }
        override fun onEditBlocked() { active?.let { if (it.mode == DocMode.VIEW) dialog = DialogState.EnableEdit(it) } }
        override fun onMessage(text: String) { toast(text) }
        override fun onScrollChanged() { persistSoon() }
    }

    // ------------------------------------------------------------------ modes / encodings
    /** Fast-view -> full editing (reads the whole file into memory). */
    fun switchToEdit(tab: Tab, confirmed: Boolean = false) {
        val s = tab.session ?: return
        if (s.mode == DocMode.EDIT) return
        val size = s.info.sizeBytes
        if (!confirmed && size > 64 * MB) { dialog = DialogState.ConfirmSwitchToEdit(tab, size / MB); return }
        val vd = s.store as? ViewDocument ?: return
        progress = ProgressState(tr("Загрузка для редактирования…", "Loading for editing…"))
        val src = vd.source
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { DocumentOpener.openForEdit(src, s.info, OpenOptions(forceMode = DocMode.EDIT)) }
            progress = null
            when (r) {
                is OpenResult.Ok -> {
                    val old = s.store
                    s.replaceStore(r.doc, r.info)
                    try { old.close() } catch (_: Throwable) {}
                    tab.mode = DocMode.EDIT; tab.lineCount = r.doc.lineCount
                    tab.indexProgress = 1f
                    resolveLanguage(tab); attachHighlighter(tab)
                    tab.sessionVersion++
                    toast(tr("Режим редактирования включён", "Editing enabled"))
                }
                is OpenResult.Failure -> dialog = DialogState.Error(tr("Не удалось включить редактирование", "Cannot enable editing"),
                    (r.error.message ?: r.error.javaClass.simpleName) + "\n" + tr("Файл остаётся в режиме просмотра.", "The file stays in view mode."))
                is OpenResult.Binary -> {}
            }
        }
    }

    fun switchToView(tab: Tab) {
        if (tab.dirty) { toast(tr("Сначала сохраните изменения", "Save your changes first")); return }
        tab.record = recordOf(tab).copy(mode = "view")
        reopen(tab, DocMode.VIEW, null)
    }

    fun reopenWithEncoding(tab: Tab, enc: TextEncoding) {
        if (tab.dirty) { toast(tr("Сначала сохраните изменения", "Save your changes first")); return }
        tab.record = recordOf(tab)
        reopen(tab, if (tab.mode == DocMode.VIEW) DocMode.VIEW else null, enc)
    }

    private fun reopen(tab: Tab, mode: DocMode?, enc: TextEncoding?) {
        tab.search?.close(); tab.search = null
        tab.session?.close(); tab.session = null
        deleteDraft(tab)
        tab.restoredDirty = false
        tab.state = TabState.UNLOADED; tab.sessionVersion++
        load(tab, forceMode = mode, forceEncoding = enc, ignoreDraft = true)
    }

    /** Closes the tab's document, runs [rewrite] (returns an error text or null) and reloads the file. */
    suspend fun reloadAfterRewrite(tab: Tab, rewrite: suspend () -> String?) {
        tab.record = recordOf(tab)
        tab.search?.close(); tab.search = null
        tab.session?.close(); tab.session = null
        tab.state = TabState.UNLOADED; tab.sessionVersion++
        val err = rewrite()
        if (err != null) dialog = DialogState.Error(tr("Не удалось записать результат", "Could not write the result"), err)
        load(tab, ignoreDraft = true)
    }

    fun openAnyway(tab: Tab) {
        tab.state = TabState.UNLOADED
        tab.record = null
        load(tab, forceEncoding = TextEncoding.UTF8, ignoreDraft = true)
    }

    // ------------------------------------------------------------------ misc actions used by menus
    fun applyFontStep(delta: Float) {
        val s = active?.session ?: return
        val cur = if (s.fontSizeSp.isNaN()) settings.float(K.FONT_SIZE) else s.fontSizeSp
        s.fontSizeSp = (cur + delta).coerceIn(settings.float(K.FONT_MIN), settings.float(K.FONT_MAX))
        editor?.relayout(); persistSoon()
    }

    fun resetZoom() { active?.session?.fontSizeSp = Float.NaN; editor?.relayout(); persistSoon() }

    fun toggleWrap() {
        val t = active ?: return
        val s = t.session ?: return
        s.wrapOverride = !(s.wrapOverride ?: settings.bool(K.WRAP))
        editor?.relayout(); persistSoon()
    }

    fun isWrapping(): Boolean = active?.session?.wrapOverride ?: settings.bool(K.WRAP)

    fun setSaveEncoding(tab: Tab, enc: TextEncoding) { tab.session?.encoding = enc; tab.encodingLabel = enc.label; tab.modified = true; persistSoon() }
    fun setSaveEol(tab: Tab, eol: Eol) { tab.session?.eol = eol; tab.eolLabel = eol.name; persistSoon() }

    /** [displayed] is the number the user sees (respects the 0/1 numbering base). */
    fun gotoLine(displayed: Int) { val base = settings.str(K.LN_BASE).toIntOrNull() ?: 1; editor?.gotoLine(displayed - base + 1) }

    // ------------------------------------------------------------------ settings actions (settings screen)
    fun resetSettings() { viewModelScope.launch { app.settingsRepo.reset(); toast(tr("Настройки сброшены", "Settings reset")) } }

    fun exportSettingsBytes(): ByteArray = Schema.export(settings.values, BuildConfig.VERSION_NAME)

    /** Validates the whole file first; nothing is applied when anything is wrong. */
    fun importSettings(bytes: ByteArray) {
        val r = Schema.parseImport(bytes)
        if (!r.ok) {
            dialog = DialogState.Error(tr("Импорт не выполнен", "Import failed"), r.errors.take(8).joinToString("\n") + if (r.errors.size > 8) "\n…" else "")
            return
        }
        viewModelScope.launch {
            app.settingsRepo.setMany(r.values)
            toast(tr("Настройки импортированы (${r.values.size})", "Settings imported (${r.values.size})") + if (r.warnings.isNotEmpty()) tr(", пропущено: ${r.warnings.size}", ", skipped: ${r.warnings.size}") else "")
        }
    }

    fun clearTempFiles(): Int {
        var n = 0
        try { app.tempDir.listFiles()?.forEach { if (it.delete()) n++ } } catch (_: Throwable) {}
        return n
    }

    override fun onCleared() {
        flushNow()
        for (t in tabs) { try { t.session?.close() } catch (_: Throwable) {} }
        super.onCleared()
    }
}
