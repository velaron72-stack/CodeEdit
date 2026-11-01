package app.codeedit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.codeedit.core.doc.EditableDocument
import app.codeedit.core.doc.Match
import app.codeedit.core.doc.ViewDocument
import app.codeedit.core.io.FileSaveTarget
import app.codeedit.core.search.QueryException
import app.codeedit.core.search.Replacer
import app.codeedit.core.search.SearchQuery
import app.codeedit.core.search.SearchSession
import app.codeedit.data.K
import app.codeedit.data.tr
import app.codeedit.editor.MatchHighlighter
import app.codeedit.files.UriFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** State + logic of the bottom find/replace panel. One SearchSession per active tab, always replaced (never mutated) when the query changes. */
class SearchController(private val vm: EditorViewModel) {
    var visible by mutableStateOf(false); private set
    var replaceMode by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var count by mutableLongStateOf(0L); private set
    var ordinal by mutableLongStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set
    var running by mutableStateOf(false); private set
    var notFound by mutableStateOf(false); private set
    var partial by mutableStateOf(false); private set

    private var inited = false
    private var stepGen = 0
    private val rebuildRunnable = Runnable { rebuild(jump = false) }

    fun open(replace: Boolean) {
        val tab = vm.active ?: return
        val st = vm.settings
        if (!inited) { inited = true; matchCase = st.bool(K.S_CASE); wholeWord = st.bool(K.S_WORD); regex = st.bool(K.S_REGEX) }
        val sel = tab.session?.selection
        if (st.bool(K.S_USE_SEL) && sel != null && !sel.isEmpty) vm.editor?.selectedTextOrWordAtCaret(200)?.let { query = it }
        replaceMode = replace || (visible && replaceMode)
        visible = true
        rebuild(jump = query.isNotEmpty())
    }

    fun close() {
        visible = false
        cancelAll()
        vm.editor?.setMatchHighlighter(null)
        vm.editor?.requestFocus()
    }

    private fun cancelAll() {
        stepGen++
        vm.app.main.removeCallbacks(rebuildRunnable)
        for (t in vm.tabs) { t.search?.close(); t.search = null; t.currentMatch = null }
        running = false
    }

    fun onTabChanged() {
        if (!visible) return
        cancelAll(); vm.editor?.setMatchHighlighter(null)
        count = 0; ordinal = 0
        vm.app.main.post { if (visible) rebuild(jump = false) }
    }

    /** The editor view got bound to a (new) session while the panel is open: matches must be repainted. */
    fun onEditorRebound() { if (visible) vm.app.main.post { if (visible) rebuild(jump = false) } }

    /** A document edit happened: re-scan after a short pause. */
    fun onDocumentEdited() {
        if (!visible || query.isEmpty()) return
        vm.app.main.removeCallbacks(rebuildRunnable)
        vm.app.main.postDelayed(rebuildRunnable, 350)
    }

    fun onOptionsChanged() { rebuild(jump = true) }

    fun rebuild(jump: Boolean) {
        val tab = vm.active ?: return
        val s = tab.session ?: return
        tab.search?.close(); tab.search = null
        tab.currentMatch = null
        stepGen++
        error = null; notFound = false; partial = false; ordinal = 0
        if (query.isEmpty()) { count = 0; running = false; vm.editor?.setMatchHighlighter(null); return }
        val holder = arrayOfNulls<SearchSession>(1)
        try {
            val ss = SearchSession(s.store, SearchQuery(query, matchCase, wholeWord, regex), vm.app.ioPool, SearchSession.Listener { c, complete ->
                vm.app.main.post {
                    if (tab.search === holder[0]) { count = c; running = !complete; vm.editor?.invalidate() }
                }
            })
            holder[0] = ss
            tab.search = ss
            running = true
            ss.start()
            vm.editor?.setMatchHighlighter(object : MatchHighlighter {
                override fun matchesInLine(line: Int): IntArray {
                    if (vm.settings.bool(K.S_HL_ALL)) return ss.matchesInLine(line)
                    val c = tab.currentMatch ?: return EMPTY
                    return if (c.start.line == line) intArrayOf(c.start.col, if (c.end.line == line) c.end.col - c.start.col else s.store.lineLength(line) - c.start.col) else EMPTY
                }
                override val current: Match? get() = tab.currentMatch
            })
            if (jump) step(forward = true, inclusive = true)
        } catch (e: QueryException) {
            error = e.message ?: tr("Некорректное выражение", "Invalid expression")
            count = 0; running = false
            vm.editor?.setMatchHighlighter(null)
        }
    }

    fun next() = step(true, false)
    fun prev() = step(false, false)

    private fun step(forward: Boolean, inclusive: Boolean) {
        val tab = vm.active ?: return
        val s = tab.session ?: return
        val ss = tab.search
        if (ss == null) { if (query.isNotEmpty() && error == null) rebuild(jump = true); return }
        val sel = s.selection
        val wrap = vm.settings.bool(K.S_WRAP)
        val gen = ++stepGen
        val from = if (forward) (if (sel.isEmpty) sel.head else sel.start) else sel.start
        val incl = inclusive && sel.isEmpty
        val compute = { if (forward) ss.findNext(from, wrap, incl) else ss.findPrevious(from, wrap) }
        val deliver = { m: Match? ->
            if (gen == stepGen && tab.search === ss) {
                partial = ss.lastFindPartial
                if (m == null) { notFound = true; tab.currentMatch = null }
                else {
                    notFound = false
                    tab.currentMatch = m
                    vm.editor?.revealMatch(m, select = true)
                    ordinal = ss.ordinalOf(m).coerceAtLeast(0)
                }
                vm.editor?.invalidate()
            }
        }
        if (s.editable != null) deliver(try { compute() } catch (_: Throwable) { null })
        else vm.viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { try { compute() } catch (_: Throwable) { null } }
            deliver(m)
        }
    }

    // ------------------------------------------------------------------ replace
    private fun expansion(s: app.codeedit.core.edit.EditorSession, m: Match): String {
        val q = SearchQuery(query, matchCase, wholeWord, regex)
        if (!regex) return replacement
        return if (m.start.line == m.end.line) {
            val line = s.store.getLine(m.start.line)
            Replacer.expandAt(q, line, m.start.col, m.end.col, replacement)
        } else {
            val text = s.store.getText(m.start, m.end)
            Replacer.expandAt(q, text, 0, text.length, replacement)
        }
    }

    fun replaceCurrent() {
        val tab = vm.active ?: return
        val s = tab.session ?: return
        if (s.editable == null) { vm.editorListener.onEditBlocked(); return }
        val m = tab.currentMatch
        val sel = s.selection
        if (m == null || sel.start != m.start || sel.end != m.end) { step(true, true); return }
        val text = try { expansion(s, m) } catch (e: Throwable) { error = e.message; return }
        vm.editor?.replaceRange(m, text)
        tab.currentMatch = null
        rebuild(jump = true)
    }

    fun replaceAll() {
        val tab = vm.active ?: return
        val s = tab.session ?: return
        if (query.isEmpty() || error != null) return
        if (s.editable == null && tab.uri == null) return
        val big = s.editable == null
        if (big || (vm.settings.bool(K.F_CONFIRM) && count > 1)) { vm.dialog = DialogState.ConfirmReplaceAll(tab, count); return }
        doReplaceAll(tab)
    }

    fun doReplaceAll(tab: Tab) {
        val s = tab.session ?: return
        val q = SearchQuery(query, matchCase, wholeWord, regex)
        val doc = s.editable
        if (doc != null) {
            val n = try { Replacer.replaceAll(doc, q, replacement, s.selection, s.selection) } catch (e: Throwable) {
                vm.dialog = DialogState.Error(tr("Замена не удалась", "Replace failed"), e.message ?: e.javaClass.simpleName); return }
            vm.editor?.notifyExternalEdit()
            vm.toast(if (n > 0) tr("Заменено: $n", "Replaced: $n") else tr("Совпадений нет", "No matches"))
            rebuild(jump = false)
            return
        }
        streamReplace(tab, q)
    }

    /** Big (view-mode) file: stream to a temp file; only a completely successful result replaces the original. */
    private fun streamReplace(tab: Tab, q: SearchQuery) {
        val s = tab.session ?: return
        val vd = s.store as? ViewDocument ?: return
        val uri = tab.uri ?: return
        val src = vd.source
        val info = s.info
        val repl = replacement
        val ps = ProgressState(tr("Замена во всём файле…", "Replacing in the whole file…"))
        vm.progress = ps
        vm.viewModelScope.launch {
            val app = vm.app
            val tmp = File(app.tempDir, "replace-${System.nanoTime()}.tmp").also { it.parentFile?.mkdirs() }
            val r = withContext(Dispatchers.IO) {
                Replacer.streamReplaceAll(src, info, q, repl, FileSaveTarget(tmp, false), { p -> ps.value = p }, ps.cancel)
            }
            if (!r.ok) {
                vm.progress = null
                tmp.delete()
                if (!ps.cancel.get()) vm.dialog = DialogState.Error(tr("Замена не удалась", "Replace failed"),
                    (r.error?.message ?: "?") + "\n" + tr("Исходный файл не изменён.", "The original file is untouched."))
                else vm.toast(tr("Замена отменена, файл не изменён", "Cancelled; the file is unchanged"))
                return@launch
            }
            ps.value = 1f
            // the document is open read-only on the very file we are about to rewrite: close it first, reload afterwards
            val name = tab.name
            vm.reloadAfterRewrite(tab) {
                withContext(Dispatchers.IO) {
                    try { UriFiles.overwrite(app, uri, tmp); tmp.delete(); null }
                    catch (t: Throwable) {
                        val keep = File(app.filesDir, "recovery").also { it.mkdirs() }.let { d -> File(d, "$name.replaced").also { f -> try { tmp.copyTo(f, true); tmp.delete() } catch (_: Throwable) {} } }
                        t.message + "\n" + tr("Результат сохранён в ${keep.absolutePath}", "The result was kept at ${keep.absolutePath}")
                    }
                }
            }
            vm.progress = null
            vm.toast(tr("Заменено: ${r.replacements}", "Replaced: ${r.replacements}"))
        }
    }

    companion object { private val EMPTY = IntArray(0) }
}
