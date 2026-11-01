package app.codeedit.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeedit.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Entry(val uri: Uri, val docId: String, val name: String, val isDir: Boolean, val size: Long)
private class Level(val docId: String, val name: String)

private fun listChildren(ctx: Context, tree: Uri, parentDocId: String): List<Entry> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
    val out = ArrayList<Entry>()
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
    )
    ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            out += Entry(
                DocumentsContract.buildDocumentUriUsingTree(tree, id), id, c.getString(1) ?: id,
                c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1L else c.getLong(3),
            )
        }
    }
    return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
}

/** Folder browser over a SAF tree (ACTION_OPEN_DOCUMENT_TREE). Reads children with a single cursor query per directory. */
@Composable
fun FolderScreen(vm: EditorViewModel, screen: Screen.Folder) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = screen.rootUri
    val stack = remember(tree) { mutableStateListOf(Level(DocumentsContract.getTreeDocumentId(tree), screen.title)) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf<String?>(null) }
    val cur = stack.last()

    LaunchedEffect(tree, cur.docId) {
        entries = null; error = null; filter = ""
        try { entries = withContext(Dispatchers.IO) { listChildren(ctx, tree, cur.docId) } }
        catch (t: Throwable) { error = (t.message ?: t.javaClass.simpleName); entries = emptyList() }
    }
    val back: () -> Unit = {
        if (stack.size > 1) { stack.removeAt(stack.size - 1) } else { vm.screen = Screen.Editor }
    }
    BackHandler { back() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height((52 * c.scale).dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад", "Back"), { back() })
            Text(stack.joinToString(" / ") { it.name }, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            IconAction(Icons.Default.NoteAdd, tr("Новый файл здесь", "New file here"), { newName = "" })
        }
        SearchBox(filter, { filter = it }, tr("Фильтр по имени", "Filter by name"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth())
        val list = entries
        when {
            list == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = c.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            error != null -> Text(
                tr("Не удалось прочитать папку: ", "Cannot read the folder: ") + error + "\n" + tr("Откройте папку заново через «Открыть папку».", "Re-open it with “Open folder”."),
                color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(20.dp),
            )
            else -> {
                val k = filter.trim().lowercase()
                val shown = if (k.isEmpty()) list else list.filter { it.name.lowercase().contains(k) }
                if (shown.isEmpty()) Text(tr("Пусто", "Empty"), color = c.muted, modifier = Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.docId }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (e.isDir) stack.add(Level(e.docId, e.name))
                                else { vm.screen = Screen.Editor; vm.openUris(listOf(e.uri), persist = false) }
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.Description, null, tint = if (e.isDir) c.accent else c.muted, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!e.isDir && e.size >= 0) Text(formatSize(e.size), color = c.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    newName?.let { name ->
        AlertDialog(
            onDismissRequest = { newName = null }, containerColor = c.surface, titleContentColor = c.text,
            title = { Text(tr("Новый файл", "New file"), fontSize = 17.sp) },
            text = { SlimField(name, { newName = it }, Modifier.fillMaxWidth(), "name.txt", mono = true) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim().replace('/', '_')
                    newName = null
                    if (n.isEmpty()) return@TextButton
                    scope.launch {
                        val created: Uri? = withContext(Dispatchers.IO) {
                            try {
                                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, cur.docId)
                                DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", n)
                            } catch (_: Throwable) { null }
                        }
                        if (created != null) { vm.screen = Screen.Editor; vm.openUris(listOf(created), persist = false) }
                        else vm.toast(tr("Не удалось создать файл", "Could not create the file"))
                    }
                }) { Text(tr("Создать", "Create"), color = c.accent) }
            },
            dismissButton = { TextButton(onClick = { newName = null }) { Text(tr("Отмена", "Cancel"), color = c.muted) } },
        )
    }
}
