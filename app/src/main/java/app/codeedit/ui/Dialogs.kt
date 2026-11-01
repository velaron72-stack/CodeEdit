package app.codeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeedit.core.doc.DocMode
import app.codeedit.core.doc.Eol
import app.codeedit.core.doc.TextEncoding
import app.codeedit.data.K
import app.codeedit.data.Schema
import app.codeedit.data.tr
import app.codeedit.highlight.PLAIN_TEXT_ID

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        title = { Text(title, fontSize = 17.sp) }, text = { Text(text, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = c.accent) } },
    )
}

/** Renders whichever [DialogState] is current, plus the progress overlay. */
@Composable
fun DialogHost(vm: EditorViewModel, actions: Actions) {
    val d = vm.dialog
    val dismiss = { vm.dialog = null }
    when (d) {
        null -> {}
        is DialogState.ConfirmClose -> {
            val dirty = d.tabs.filter { it.dirty }
            val text = if (d.tabs.size == 1) tr("В «${d.tabs[0].name}» есть несохранённые изменения.", "\"${d.tabs[0].name}\" has unsaved changes.")
            else tr("Несохранённые изменения: ${dirty.size} из ${d.tabs.size} файлов.", "Unsaved changes in ${dirty.size} of ${d.tabs.size} files.")
            ConfirmDialog(
                tr("Закрыть с изменениями?", "Close with unsaved changes?"), text, tr("Сохранить", "Save"),
                onConfirm = { dismiss(); vm.saveThenClose(d.tabs) }, onDismiss = dismiss,
                neutral = tr("Не сохранять", "Discard"), onNeutral = { dismiss(); d.tabs.forEach { vm.closeNow(it) } },
            )
        }
        is DialogState.ExternalOverwrite -> ConfirmDialog(
            tr("Файл изменён на диске", "File changed on disk"),
            tr("«${d.tab.name}» был изменён другим приложением после открытия. Перезаписать его вашей версией?", "\"${d.tab.name}\" was modified by another app after you opened it. Overwrite it with your version?"),
            tr("Перезаписать", "Overwrite"), onConfirm = { dismiss(); vm.save(d.tab, false, true, d.then) }, onDismiss = dismiss, danger = true,
            neutral = tr("Загрузить с диска", "Reload"), onNeutral = { dismiss(); vm.reloadFromDisk(d.tab) },
        )
        is DialogState.ExternalReload -> ConfirmDialog(
            tr("Перезагрузить с диска?", "Reload from disk?"),
            tr("Ваши несохранённые изменения в «${d.tab.name}» будут потеряны.", "Your unsaved changes in \"${d.tab.name}\" will be lost."),
            tr("Перезагрузить", "Reload"), onConfirm = { dismiss(); vm.reloadFromDisk(d.tab) }, onDismiss = dismiss, danger = true,
        )
        is DialogState.Binary -> ConfirmDialog(
            tr("Похоже на бинарный файл", "Looks like a binary file"), d.reason + "\n\n" + tr("Открыть как текст всё равно?", "Open it as text anyway?"),
            tr("Открыть", "Open anyway"), onConfirm = { dismiss(); vm.openAnyway(d.tab) },
            onDismiss = { dismiss(); vm.closeNow(d.tab) }, dismissLabel = tr("Закрыть вкладку", "Close tab"),
        )
        is DialogState.Error ->
            if (d.retry != null) ConfirmDialog(d.title, d.message, d.retryLabel ?: tr("Повторить", "Retry"), onConfirm = { dismiss(); d.retry.invoke() }, onDismiss = dismiss, dismissLabel = tr("Закрыть", "Close"))
            else InfoDialog(d.title, d.message, dismiss)
        is DialogState.EnableEdit -> ConfirmDialog(
            tr("Файл открыт для просмотра", "File is in view mode"),
            tr("Быстрый просмотр не позволяет менять текст. Загрузить файл целиком и включить редактирование?", "Fast view cannot modify text. Load the whole file and enable editing?"),
            tr("Редактировать", "Enable editing"), onConfirm = { dismiss(); vm.switchToEdit(d.tab) }, onDismiss = dismiss,
        )
        is DialogState.ConfirmReplaceAll -> ConfirmDialog(
            tr("Заменить везде?", "Replace all?"),
            if (d.tab.session?.editable == null) tr("Файл большой: результат будет записан потоком во временный файл и подменит оригинал (≈${d.count} замен). Продолжить?", "Large file: the result is streamed to a temp file and replaces the original (≈${d.count} replacements). Continue?")
            else tr("Будет заменено вхождений: ${d.count}. Отмена — через Undo.", "Occurrences to replace: ${d.count}. Undo reverts it."),
            tr("Заменить", "Replace"), onConfirm = { dismiss(); vm.search.doReplaceAll(d.tab) }, onDismiss = dismiss, danger = true,
        )
        is DialogState.ConfirmSwitchToEdit -> ConfirmDialog(
            tr("Загрузить в память?", "Load into memory?"),
            tr("Файл ≈${d.sizeMb} МБ будет прочитан целиком. Это может занять время и память.", "The ≈${d.sizeMb} MB file will be read completely. This can take time and memory."),
            tr("Загрузить", "Load"), onConfirm = { dismiss(); vm.switchToEdit(d.tab, true) }, onDismiss = dismiss,
        )
        DialogState.Goto -> GotoDialog(vm, dismiss)
        DialogState.Language -> LanguageDialog(vm, dismiss)
        DialogState.SyntaxTheme -> SyntaxThemeDialog(vm, dismiss)
        DialogState.Font -> FontDialog(vm, actions, dismiss)
        DialogState.Encoding -> EncodingDialog(vm, dismiss)
        DialogState.Eol -> EolDialog(vm, dismiss)
        DialogState.MenuInfo -> InfoPanel(vm, dismiss)
        DialogState.ResetSettings -> ConfirmDialog(
            tr("Сбросить настройки?", "Reset settings?"), tr("Все параметры вернутся к значениям по умолчанию. Открытые файлы не затрагиваются.", "All settings return to their defaults. Open files are not affected."),
            tr("Сбросить", "Reset"), onConfirm = { dismiss(); vm.resetSettings() }, onDismiss = dismiss, danger = true,
        )
    }
    vm.progress?.let { ProgressOverlay(it) }
}

@Composable
private fun ProgressOverlay(p: ProgressState) {
    val c = LocalAppColors.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(p.title, color = c.text, fontSize = 15.sp)
                if (p.value > 0f) LinearProgressIndicator(progress = { p.value }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surface2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { p.cancel.set(true) }) { Text(tr("Отмена", "Cancel"), color = c.muted) }
                }
            }
        }
    }
}

@Composable
private fun GotoDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    val c = LocalAppColors.current
    val tab = vm.active
    val base = vm.settings.str(K.LN_BASE).toIntOrNull() ?: 1
    val max = (tab?.lineCount ?: 1) + base - 1
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Throwable) {} }
    val go = { text.trim().toIntOrNull()?.let { n -> dismiss(); vm.gotoLine(n.coerceIn(base, max)) } ?: Unit }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = c.surface, titleContentColor = c.text,
        title = { Text(tr("Перейти к строке", "Go to line"), fontSize = 17.sp) },
        text = {
            SlimField(text, { s -> text = s.filter { it.isDigit() }.take(12) }, Modifier.fillMaxWidth(), "$base – $max", mono = true, focusRequester = focus,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
        },
        confirmButton = { TextButton(onClick = { go() }) { Text(tr("Перейти", "Go"), color = c.accent) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Отмена", "Cancel"), color = c.muted) } },
    )
}

private const val AUTO = "__auto"

@Composable
private fun LanguageDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    val tab = vm.active ?: run { dismiss(); return }
    val hs = vm.highlight
    val langs = remember(hs) { hs?.languages?.filter { it.id != PLAIN_TEXT_ID }?.sortedBy { it.name.lowercase() } ?: emptyList() }
    val autoName = tr("Автоопределение", "Auto-detect")
    val items = remember(langs) {
        buildList {
            add(PickItem(AUTO, autoName, tr("По имени файла, расширению и первой строке", "By file name, extension and first line"), section = null, search = "auto авто"))
            add(PickItem(PLAIN_TEXT_ID, tr("Простой текст", "Plain text"), null, null, section = tr("Языки", "Languages"), search = "plain text простой текст"))
            for (l in langs) add(PickItem(l.id, l.name, l.extensions.take(5).joinToString(" "), null, tr("Языки", "Languages"), l.name + " " + l.id + " " + l.extensions.joinToString(" ") + " " + l.aliases.joinToString(" ")))
        }
    }
    PickerDialog(
        tr("Язык подсветки", "Highlight language"), items, tab.manualLanguage ?: AUTO,
        { vm.setLanguage(tab, if (it.id == AUTO) null else it.id); dismiss() }, dismiss,
        footer = {
            val pi = hs?.packageInfo
            Text(
                if (pi != null) "${pi.grammarName} ${pi.grammarVersion} · ${pi.languageCount} " + tr("языков", "languages") else tr("Пакет грамматик недоступен", "Grammar package unavailable"),
                color = LocalAppColors.current.muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp),
            )
        },
    )
}

@Composable
private fun SyntaxThemeDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    val hs = vm.highlight
    if (hs == null) { InfoDialog(tr("Тема подсветки", "Syntax theme"), tr("Пакет тем недоступен.", "Theme package unavailable."), dismiss); return }
    val items = remember(hs) {
        hs.themes.sortedWith(compareBy({ !it.isDark }, { it.name.lowercase() })).map {
            PickItem(it.id, it.name, null, null, if (it.isDark) tr("Тёмные", "Dark") else tr("Светлые", "Light"), it.name + " " + it.id)
        }
    }
    val dark = appIsDark(vm.settings)
    PickerDialog(
        tr("Тема подсветки", "Syntax theme"), items, vm.syntaxTheme?.id,
        { pick ->
            val entry = hs.themes.firstOrNull { it.id == pick.id }
            if (entry != null) {
                vm.pickSyntaxTheme(entry.id, entry.isDark)
                if (entry.isDark != dark) vm.setSetting(K.THEME_MODE, if (entry.isDark) "dark" else "light")
            }
            dismiss()
        }, dismiss,
        footer = {
            val pi = hs.packageInfo
            Text("${pi.themeName} ${pi.themeVersion} · ${pi.themeCount}", color = LocalAppColors.current.muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
        },
    )
}

@Composable
private fun FontDialog(vm: EditorViewModel, actions: Actions, dismiss: () -> Unit) {
    val c = LocalAppColors.current
    val fonts = remember { vm.app.fonts.list() }
    var tick by remember { mutableIntStateOf(0) }
    val items = fonts.map { PickItem(it.id, it.name, if (it.builtin) null else tr("Импортирован", "Imported"), null, null, it.name) }
    PickerDialog(
        tr("Шрифт и размер", "Font & size"), items, vm.settings.str(K.FONT_FAMILY),
        { vm.setSetting(K.FONT_FAMILY, it.id) }, dismiss,
        footer = {
            val step = vm.settings.float(K.FONT_STEP)
            val cur = vm.active?.session?.fontSizeSp?.takeIf { !it.isNaN() } ?: vm.settings.float(K.FONT_SIZE)
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    KeyChip("A−", { vm.applyFontStep(-step); tick++ }, minWidth = 44.dp)
                    Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("${"%.1f".format(cur)} sp" + if (tick < 0) "" else "", color = c.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp) }
                    KeyChip("A+", { vm.applyFontStep(step); tick++ }, minWidth = 44.dp)
                    KeyChip(tr("Сброс", "Reset"), { vm.resetZoom(); tick++ }, mono = false)
                }
                KeyChip(tr("Импортировать шрифт (.ttf / .otf)…", "Import font (.ttf / .otf)…"), { dismiss(); actions.importFont() }, mono = false, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun EncodingDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    val tab = vm.active ?: run { dismiss(); return }
    val cur = tab.session?.encoding
    val reopen = tr("Перечитать файл в кодировке", "Re-read file as")
    val save = tr("Сохранять в кодировке", "Save as")
    val items = remember(reopen, save) {
        buildList {
            for (e in Schema.encodings) add(PickItem("open:$e", e, null, null, reopen, "$e $reopen"))
            for (e in Schema.encodings) add(PickItem("save:$e", e, null, null, save, "$e $save"))
            add(PickItem("save:UTF-8+bom", "UTF-8 (BOM)", null, null, save, "UTF-8 BOM $save"))
        }
    }
    val sel = cur?.let { "save:" + it.charsetName + if (it.bom) "+bom" else "" }
    PickerDialog(tr("Кодировка", "Encoding"), items, sel, { pick ->
        val kind = pick.id.substringBefore(':'); val rest = pick.id.substringAfter(':')
        dismiss()
        if (kind == "open") vm.reopenWithEncoding(tab, TextEncoding(rest, false))
        else vm.setSaveEncoding(tab, if (rest.endsWith("+bom")) TextEncoding(rest.removeSuffix("+bom"), true) else TextEncoding(rest, false))
    }, dismiss)
}

@Composable
private fun EolDialog(vm: EditorViewModel, dismiss: () -> Unit) {
    val tab = vm.active ?: run { dismiss(); return }
    val items = Eol.values().map { PickItem(it.name, it.label, when (it) { Eol.LF -> "Unix / Linux / Android"; Eol.CRLF -> "Windows"; Eol.CR -> "Classic Mac" }) }
    PickerDialog(tr("Окончания строк", "Line endings"), items, tab.session?.eol?.name, { pick -> dismiss(); vm.setSaveEol(tab, Eol.valueOf(pick.id)) }, dismiss)
}

@Composable
private fun InfoPanel(vm: EditorViewModel, dismiss: () -> Unit) {
    val c = LocalAppColors.current
    val tab = vm.active ?: run { dismiss(); return }
    val s = tab.session
    val rows = buildList {
        add(tr("Имя", "Name") to tab.name)
        add(tr("Путь", "Path") to (tab.uri?.let { android.net.Uri.decode(it.toString()) } ?: tr("не сохранён", "not saved")))
        if (s != null) {
            add(tr("Размер", "Size") to formatSize(s.info.sizeBytes))
            add(tr("Строк", "Lines") to tab.lineCount.toString())
            add(tr("Режим", "Mode") to if (s.mode == DocMode.VIEW) tr("быстрый просмотр", "fast view") else tr("редактирование", "editing"))
            add(tr("Кодировка", "Encoding") to s.encoding.label)
            add(tr("Окончания строк", "Line endings") to s.eol.label + if (s.info.eolMixed) tr(" (в файле смешанные)", " (mixed in file)") else "")
        }
        add(tr("Язык", "Language") to tab.languageName + (tab.highlightNote?.let { " — $it" } ?: ""))
    }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = c.surface, titleContentColor = c.text,
        title = { Text(tr("Сведения о файле", "File info"), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, v) in rows) Column { Text(k, color = c.muted, fontSize = 11.sp); Text(v, color = c.text, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("OK", color = c.accent) } },
    )
}

fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
