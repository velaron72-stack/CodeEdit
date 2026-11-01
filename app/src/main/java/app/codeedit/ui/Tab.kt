package app.codeedit.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.codeedit.core.doc.DocMode
import app.codeedit.core.doc.Match
import app.codeedit.core.edit.EditorSession
import app.codeedit.core.search.SearchSession
import app.codeedit.data.TabRecord

enum class TabState { UNLOADED, LOADING, READY, FAILED }

/** One open document. Compose-observable fields drive the UI; the rest is bookkeeping owned by EditorViewModel. */
class Tab(val id: String) {
    var uri by mutableStateOf<Uri?>(null)
    var name by mutableStateOf("")
    var state by mutableStateOf(TabState.UNLOADED)
    var error by mutableStateOf<String?>(null)
    var modified by mutableStateOf(false)
    var mode by mutableStateOf(DocMode.EDIT)
    var languageId by mutableStateOf("plaintext")
    var languageName by mutableStateOf("Plain Text")
    var encodingLabel by mutableStateOf("UTF-8")
    var eolLabel by mutableStateOf("LF")
    var indexProgress by mutableFloatStateOf(1f)
    var lineCount by mutableIntStateOf(1)
    var externalChanged by mutableStateOf(false)
    /** Bumped whenever the EditorSession object (or its store) was replaced: the view must rebind. */
    var sessionVersion by mutableIntStateOf(0)
    var saving by mutableStateOf(false)
    var highlightNote by mutableStateOf<String?>(null)

    var session: EditorSession? = null
    var record: TabRecord? = null            // state to restore on (re)load
    var manualLanguage: String? = null       // null = auto-detect
    var restoredDirty = false                // content came from a draft that is newer than the file
    var fromExternal = false
    var knownSize = -1L
    var knownModified = 0L
    var lastUsed = 0L
    var draftVersion = -1L
    var draftScheduled = false
    var hasDraft = false
    var initialText: String? = null
    var promptedUnavailable = false
    var search: SearchSession? = null
    var currentMatch: Match? = null

    val dirty: Boolean get() = restoredDirty || session?.isModified == true
    val editable: Boolean get() = session?.editable != null
}
