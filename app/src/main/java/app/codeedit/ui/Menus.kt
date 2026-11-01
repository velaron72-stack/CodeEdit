package app.codeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Modifier
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
        expanded = expanded, onDismissRequest = onDismiss, modifier = modifier.widthIn(min = 230.dp),
        containerColor = c.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, c.border),
        tonalElevation = 0.dp, shadowElevation = 10.dp, content = content,
    )
}

@Composable
fun MenuItem(
    label: String, onClick: () -> Unit, icon: ImageVector? = null, selected: Boolean = false, checked: Boolean? = null,
    value: String? = null, enabled: Boolean = true, accent: Boolean = false, danger: Boolean = false,
) {
    val c = LocalAppColors.current
    val col = when { danger -> c.danger; selected || accent -> c.accent; else -> c.text }
    DropdownMenuItem(
        text = {
            Text(label, color = col, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        onClick = onClick, enabled = enabled,
        modifier = if (selected) Modifier.padding(horizontal = 6.dp).background(c.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)) else Modifier,
        leadingIcon = if (icon != null) ({ Icon(icon, null, tint = if (enabled) col else c.muted, modifier = Modifier.size(19.dp)) }) else null,
        trailingIcon = when {
            selected || checked == true -> ({ Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp)) })
            value != null -> ({ Text(value, color = c.muted, fontSize = 12.sp, maxLines = 1) })
            else -> null
        },
    )
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

@Composable
fun MainMenu(vm: EditorViewModel, actions: Actions, expanded: Boolean, onDismiss: () -> Unit) {
    val tab = vm.active
    val ready = tab != null && tab.state == TabState.READY && tab.session != null
    val editable = ready && tab!!.editable
    val st = vm.settings
    AppMenu(expanded, onDismiss) {
        MenuItem(tr("Найти и заменить", "Find & replace"), { onDismiss(); vm.search.open(false) }, Icons.Default.Search, accent = true, enabled = ready)
        MenuItem(tr("Перейти к строке", "Go to line"), { onDismiss(); vm.dialog = DialogState.Goto }, Icons.Default.FormatListNumbered, enabled = ready)
        MenuDivider()
        MenuItem(tr("Язык", "Language"), { onDismiss(); vm.dialog = DialogState.Language }, Icons.Default.Code, value = tab?.languageName, enabled = ready)
        MenuItem(tr("Тема подсветки", "Syntax theme"), { onDismiss(); vm.dialog = DialogState.SyntaxTheme }, Icons.Default.Palette, value = vm.syntaxTheme?.name)
        MenuItem(tr("Шрифт и размер", "Font & size"), { onDismiss(); vm.dialog = DialogState.Font }, Icons.Default.TextFields)
        MenuItem(tr("Кодировка", "Encoding"), { onDismiss(); vm.dialog = DialogState.Encoding }, Icons.Default.Translate, value = tab?.encodingLabel, enabled = ready)
        MenuItem(tr("Окончания строк", "Line endings"), { onDismiss(); vm.dialog = DialogState.Eol }, value = tab?.eolLabel, enabled = ready)
        MenuDivider()
        MenuItem(tr("Перенос строк", "Word wrap"), { onDismiss(); vm.toggleWrap() }, Icons.AutoMirrored.Filled.WrapText, checked = if (ready) vm.isWrapping() else st.bool(K.WRAP), enabled = ready)
        MenuItem(tr("Нумерация строк", "Line numbers"), { onDismiss(); vm.setSetting(K.LINE_NUMBERS, !st.bool(K.LINE_NUMBERS)) }, Icons.Default.FormatListNumbered, checked = st.bool(K.LINE_NUMBERS))
        MenuItem(
            if (tab?.mode == DocMode.VIEW) tr("Включить редактирование", "Enable editing") else tr("Режим просмотра", "View mode"),
            { onDismiss(); tab?.let { if (it.mode == DocMode.VIEW) vm.switchToEdit(it) else vm.switchToView(it) } },
            if (tab?.mode == DocMode.VIEW) Icons.Default.Edit else Icons.Default.Visibility, enabled = ready,
        )
        MenuItem(tr("Развернуть все блоки", "Unfold all"), { onDismiss(); vm.editor?.unfoldAll() }, Icons.Default.UnfoldMore, enabled = ready)
        MenuDivider()
        MenuItem(tr("Сохранить", "Save"), { onDismiss(); vm.saveActive() }, Icons.Default.Save, enabled = editable)
        MenuItem(tr("Сохранить как…", "Save as…"), { onDismiss(); tab?.let { vm.pendingSaveAs = SaveAsRequest(it, null) } }, Icons.Default.SaveAs, enabled = editable)
        MenuItem(tr("Отменить", "Undo"), { onDismiss(); vm.editor?.undo() }, Icons.AutoMirrored.Filled.Undo, enabled = editable)
        MenuItem(tr("Повторить", "Redo"), { onDismiss(); vm.editor?.redo() }, Icons.AutoMirrored.Filled.Redo, enabled = editable)
        MenuDivider()
        MenuItem(tr("Сведения о файле", "File info"), { onDismiss(); vm.dialog = DialogState.MenuInfo }, Icons.Default.Info, enabled = ready)
        MenuItem(tr("Закрыть вкладку", "Close tab"), { onDismiss(); tab?.let { vm.requestClose(it) } }, Icons.Default.Close, enabled = tab != null)
        MenuItem(tr("Настройки", "Settings"), { onDismiss(); vm.screen = Screen.Settings }, Icons.Default.Settings)
    }
}
