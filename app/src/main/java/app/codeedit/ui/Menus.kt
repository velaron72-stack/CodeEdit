package app.codeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeedit.core.doc.DocMode
import app.codeedit.data.K
import app.codeedit.data.tr

@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss, modifier = modifier.widthIn(min = 220.dp),
        containerColor = c.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, c.border),
        tonalElevation = 0.dp, shadowElevation = 8.dp, content = content,
    )
}

/**
 * One row of a menu: icon, label, and on the right either a check box ([checked] != null), a "›" for a sub-menu ([chevron]),
 * a check mark ([selected]) or a small grey [value].
 */
@Composable
fun MenuItem(
    label: String, onClick: () -> Unit, icon: ImageVector? = null, selected: Boolean = false, checked: Boolean? = null,
    value: String? = null, enabled: Boolean = true, accent: Boolean = false, danger: Boolean = false, chevron: Boolean = false,
) {
    val c = LocalAppColors.current
    val col = when { !enabled -> c.muted; danger -> c.danger; selected || accent -> c.accent; else -> c.text }
    DropdownMenuItem(
        text = { Text(label, color = col, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick, enabled = enabled,
        modifier = if (selected) Modifier.padding(horizontal = 6.dp).background(c.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)) else Modifier,
        leadingIcon = if (icon != null) ({ Icon(icon, null, tint = col, modifier = Modifier.size(22.dp)) }) else null,
        trailingIcon = when {
            checked != null -> ({ CheckMark(checked, enabled) })
            selected -> ({ Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp)) })
            chevron -> ({ Text("›", color = c.muted, fontSize = 22.sp) })
            value != null -> ({ Text(value, color = c.muted, fontSize = 12.sp, maxLines = 1) })
            else -> null
        },
    )
}

/** The check box of a toggle row: filled accent square with a tick when on, outlined square when off. */
@Composable
private fun CheckMark(checked: Boolean, enabled: Boolean) {
    val c = LocalAppColors.current
    val line = if (checked && enabled) c.accent else c.muted
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(20.dp).clip(shape).background(if (checked && enabled) c.accent else Color.Transparent).border(1.5.dp, line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Title of a sub-menu page ("Файл"); tapping it goes back to the main page. */
@Composable
fun MenuHeader(title: String, onBack: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = c.muted, fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
        Text(title, color = c.muted, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun MenuDivider() {
    HorizontalDivider(color = LocalAppColors.current.border.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 4.dp))
}

// -------------------------------------------------------------------------------------------- "+" menu

@Composable
fun NewMenu(vm: EditorViewModel, actions: Actions, expanded: Boolean, onDismiss: () -> Unit) {
    AppMenu(expanded, onDismiss) {
        MenuItem(tr("Новый файл", "New file"), { onDismiss(); vm.newDocument() }, Icons.Default.NoteAdd)
        MenuItem(tr("Открыть файлы…", "Open files…"), { onDismiss(); actions.openFiles() }, Icons.Default.Description)
        MenuItem(tr("Открыть папку…", "Open folder…"), { onDismiss(); actions.openFolder() }, Icons.Default.Folder)
        if (vm.recent.isNotEmpty()) {
            MenuDivider()
            for (r in vm.recent.take(5)) {
                MenuItem(r.name, { onDismiss(); vm.openRecent(r) }, if (r.name.endsWith("/")) Icons.Default.Folder else Icons.Default.History)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------- tab context menu

@Composable
fun TabMenu(vm: EditorViewModel, tab: Tab, expanded: Boolean, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val others = vm.tabs.filter { it.id != tab.id }
    AppMenu(expanded, onDismiss) {
        MenuItem(tr("Сохранить", "Save"), { onDismiss(); vm.save(tab) }, Icons.Default.Save, enabled = tab.editable)
        MenuItem(tr("Сохранить как…", "Save as…"), { onDismiss(); if (tab.editable) vm.pendingSaveAs = SaveAsRequest(tab, null) else vm.toast(tr("Файл открыт только для просмотра", "File is read-only")) }, Icons.Default.SaveAs)
        MenuDivider()
        MenuItem(tr("Закрыть", "Close"), { onDismiss(); vm.requestClose(tab) }, Icons.Default.Close)
        MenuItem(tr("Закрыть остальные", "Close others"), { onDismiss(); vm.requestCloseMany(others, false) }, enabled = others.isNotEmpty())
        MenuItem(tr("Закрыть все", "Close all"), { onDismiss(); vm.requestCloseMany(vm.tabs.toList(), true) })
        MenuDivider()
        MenuItem(tr("Копировать имя", "Copy name"), { onDismiss(); actions?.copyText?.invoke(tab.name) }, Icons.Default.ContentCopy)
        MenuItem(tr("Копировать путь", "Copy path"), { onDismiss(); tab.uri?.let { actions?.copyText?.invoke(android.net.Uri.decode(it.toString())) } }, enabled = tab.uri != null)
        MenuItem(tr("Поделиться", "Share"), { onDismiss(); actions?.share?.invoke(tab) }, Icons.Default.Share, enabled = tab.uri != null)
        MenuItem(tr("Сведения", "Properties"), { onDismiss(); vm.dialog = DialogState.MenuInfo }, Icons.Default.Info)
    }
}

// -------------------------------------------------------------------------------------------- main menu

private const val PAGE_MAIN = 0
private const val PAGE_FILE = 1
private const val PAGE_VIEW = 2

/**
 * Short menu in the style of MT Manager: the everyday actions on the first page, file operations under "Файл", look-and-feel under
 * "Вид" (both are pages of the same popup, the header goes back). Language / encoding / line endings / edit mode are also one tap
 * away in the status bar.
 */
@Composable
fun MainMenu(vm: EditorViewModel, actions: Actions, expanded: Boolean, onDismiss: () -> Unit) {
    val tab = vm.active
    val ready = tab != null && tab.state == TabState.READY && tab.session != null
    val editable = ready && tab!!.editable
    var page by remember(expanded) { mutableIntStateOf(PAGE_MAIN) }
    AppMenu(expanded, onDismiss) {
        when (page) {
            PAGE_FILE -> FilePage(vm, tab, ready, editable, onDismiss) { page = PAGE_MAIN }
            PAGE_VIEW -> ViewPage(vm, tab, ready, onDismiss) { page = PAGE_MAIN }
            else -> {
                MenuItem(tr("Найти и заменить", "Find & replace"), { onDismiss(); vm.search.open(false) }, Icons.Default.Search, enabled = ready)
                MenuItem(tr("Перейти к строке", "Go to line"), { onDismiss(); vm.dialog = DialogState.Goto }, Icons.Default.FormatListNumbered, enabled = ready)
                MenuItem(tr("Отменить", "Undo"), { onDismiss(); vm.editor?.undo() }, Icons.AutoMirrored.Filled.Undo, enabled = editable)
                MenuItem(tr("Повторить", "Redo"), { onDismiss(); vm.editor?.redo() }, Icons.AutoMirrored.Filled.Redo, enabled = editable)
                MenuItem(tr("Перенос строк", "Word wrap"), { onDismiss(); vm.toggleWrap() }, Icons.AutoMirrored.Filled.WrapText,
                    checked = if (ready) vm.isWrapping() else vm.settings.bool(K.WRAP), enabled = ready)
                MenuItem(tr("Только чтение", "Read only"), { onDismiss(); tab?.let { if (it.mode == DocMode.VIEW) vm.switchToEdit(it) else vm.switchToView(it) } },
                    Icons.Default.Visibility, checked = tab?.mode == DocMode.VIEW, enabled = ready)
                MenuItem(tr("Файл", "File"), { page = PAGE_FILE }, Icons.Default.Description, chevron = true, enabled = tab != null)
                MenuItem(tr("Вид", "View"), { page = PAGE_VIEW }, Icons.Default.TextFields, chevron = true)
                MenuItem(tr("Настройки", "Settings"), { onDismiss(); vm.screen = Screen.Settings }, Icons.Default.Settings)
                MenuItem(tr("Закрыть файл", "Close file"), { onDismiss(); tab?.let { vm.requestClose(it) } }, Icons.Default.Close, enabled = tab != null)
            }
        }
    }
}

@Composable
private fun FilePage(vm: EditorViewModel, tab: Tab?, ready: Boolean, editable: Boolean, onDismiss: () -> Unit, back: () -> Unit) {
    MenuHeader(tr("Файл", "File"), back)
    MenuItem(tr("Сохранить", "Save"), { onDismiss(); vm.saveActive() }, Icons.Default.Save, enabled = editable)
    MenuItem(tr("Сохранить как…", "Save as…"), { onDismiss(); tab?.let { vm.pendingSaveAs = SaveAsRequest(it, null) } }, Icons.Default.SaveAs, enabled = editable)
    MenuItem(tr("Перезагрузить", "Reload"), { onDismiss(); if (tab != null) { if (tab.dirty) vm.dialog = DialogState.ExternalReload(tab) else vm.reloadFromDisk(tab) } },
        Icons.Default.Refresh, enabled = ready && tab?.uri != null)
    MenuItem(tr("Кодировка текста", "Text encoding"), { onDismiss(); vm.dialog = DialogState.Encoding }, Icons.Default.Translate, value = tab?.encodingLabel, enabled = ready)
    MenuItem(tr("Код новой строки", "Line endings"), { onDismiss(); vm.dialog = DialogState.Eol }, Icons.Default.Code, value = tab?.eolLabel, enabled = ready)
    MenuItem(tr("Сведения о файле", "File info"), { onDismiss(); vm.dialog = DialogState.MenuInfo }, Icons.Default.Info, enabled = ready)
}

@Composable
private fun ViewPage(vm: EditorViewModel, tab: Tab?, ready: Boolean, onDismiss: () -> Unit, back: () -> Unit) {
    val st = vm.settings
    MenuHeader(tr("Вид", "View"), back)
    MenuItem(tr("Язык подсветки", "Highlight language"), { onDismiss(); vm.dialog = DialogState.Language }, Icons.Default.Code, value = tab?.languageName, enabled = ready)
    MenuItem(tr("Тема подсветки", "Syntax theme"), { onDismiss(); vm.dialog = DialogState.SyntaxTheme }, Icons.Default.Palette, value = vm.syntaxTheme?.name)
    MenuItem(tr("Шрифт и размер", "Font & size"), { onDismiss(); vm.dialog = DialogState.Font }, Icons.Default.TextFields)
    MenuItem(tr("Нумерация строк", "Line numbers"), { onDismiss(); vm.setSetting(K.LINE_NUMBERS, !st.bool(K.LINE_NUMBERS)) }, Icons.Default.FormatListNumbered, checked = st.bool(K.LINE_NUMBERS))
    MenuItem(tr("Развернуть все блоки", "Unfold all"), { onDismiss(); vm.editor?.unfoldAll() }, Icons.Default.UnfoldMore, enabled = ready)
}
