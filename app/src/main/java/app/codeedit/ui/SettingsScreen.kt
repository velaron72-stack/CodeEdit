package app.codeedit.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeedit.BuildConfig
import app.codeedit.data.K
import app.codeedit.data.SDef
import app.codeedit.data.SType
import app.codeedit.data.Schema
import app.codeedit.data.Section
import app.codeedit.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private class Special(val section: Section, val title: String, val value: String?, val search: String, val onClick: (() -> Unit)?)

@Composable
fun SettingsScreen(vm: EditorViewModel, actions: Actions) {
    val c = LocalAppColors.current
    val st = vm.settings
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var editDef by remember { mutableStateOf<SDef?>(null) }
    var pickDef by remember { mutableStateOf<SDef?>(null) }
    var themePick by remember { mutableStateOf<Boolean?>(null) }   // true = dark list, false = light list
    var licenses by remember { mutableStateOf(false) }
    val hs = vm.highlight

    fun themeName(id: String) = hs?.themes?.firstOrNull { it.id == id }?.name ?: id
    val fontName = remember(st.str(K.FONT_FAMILY)) { vm.app.fonts.get(st.str(K.FONT_FAMILY)).name }

    val specials = listOf(
        Special(Section.FONT, tr("Семейство шрифта", "Font family"), fontName, "font family шрифт семейство ttf otf") { vm.dialog = DialogState.Font },
        Special(Section.FONT, tr("Импортировать шрифт…", "Import font…"), null, "import font импорт шрифта ttf otf", actions.importFont),
        Special(Section.HIGHLIGHT, tr("Тема подсветки (тёмная)", "Highlight theme (dark)"), themeName(st.str(K.HL_DARK)), "theme dark тема тёмная") { themePick = true },
        Special(Section.HIGHLIGHT, tr("Тема подсветки (светлая)", "Highlight theme (light)"), themeName(st.str(K.HL_LIGHT)), "theme light тема светлая") { themePick = false },
    )

    fun matches(vararg parts: String): Boolean {
        val k = q.trim().lowercase()
        return k.isEmpty() || parts.any { it.lowercase().contains(k) }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height((52 * c.scale).dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад", "Back"), { vm.screen = Screen.Editor })
            Text(tr("Настройки", "Settings"), color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        SearchBox(q, { q = it }, tr("Поиск по настройкам", "Search settings"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            var any = false
            for (section in Section.values()) {
                val defs = Schema.all.filter { it.section == section && !it.hidden && matches(it.title, it.desc, it.keywords, section.title, it.ru, it.en) }
                val sp = specials.filter { it.section == section && matches(it.title, it.search, section.title) }
                if (defs.isEmpty() && sp.isEmpty()) continue
                any = true
                SectionHeader(section.title)
                SectionCard {
                    var first = true
                    for (s in sp) { if (!first) RowDivider(); first = false; ValueRow(s.title, s.value, null, onClick = s.onClick) }
                    for (d in defs) {
                        if (!first) RowDivider(); first = false
                        when (d.type) {
                            SType.BOOL -> SwitchRow(d, st.bool(d.key)) { vm.setSetting(d.key, it) }
                            SType.INT, SType.FLOAT -> SliderRow(d, st) { v -> vm.setSetting(d.key, v) }
                            SType.ENUM -> ValueRow(d.title, d.options.firstOrNull { it.value == st.str(d.key) }?.label ?: st.str(d.key), d.desc.ifEmpty { null }) { pickDef = d }
                            SType.STRING -> ValueRow(d.title, st.str(d.key), d.desc.ifEmpty { null }, mono = true) { editDef = d }
                        }
                    }
                }
            }
            // data / reset group
            if (matches(tr("данные экспорт импорт сброс временные", "data export import reset temporary files"))) {
                any = true
                SectionHeader(tr("Данные", "Data"))
                SectionCard {
                    ValueRow(tr("Экспорт настроек (JSON)", "Export settings (JSON)"), null, null, onClick = actions.exportSettings)
                    RowDivider()
                    ValueRow(tr("Импорт настроек (JSON)", "Import settings (JSON)"), null, tr("Файл проверяется целиком; при ошибке ничего не меняется", "The whole file is validated; nothing changes on error"), onClick = actions.importSettings)
                    RowDivider()
                    ValueRow(tr("Очистить временные файлы", "Clear temporary files"), null, null) {
                        val n = vm.clearTempFiles(); vm.toast(tr("Удалено файлов: $n", "Files removed: $n"))
                    }
                    RowDivider()
                    ValueRow(tr("Сбросить все настройки", "Reset all settings"), null, null, danger = true) { vm.dialog = DialogState.ResetSettings }
                }
            }
            if (!any) Text(tr("Ничего не найдено", "Nothing found"), color = c.muted, modifier = Modifier.padding(24.dp))
            // about (always last: the version is at the very bottom)
            if (q.isBlank()) AboutSection(vm) { licenses = true }
        }
    }

    pickDef?.let { d ->
        PickerDialog(d.title, d.options.map { PickItem(it.value, it.label) }, st.str(d.key), { vm.setSetting(d.key, it.id); pickDef = null }, { pickDef = null })
    }
    editDef?.let { d -> TextEditDialog(d, st.str(d.key), onSave = { vm.setSetting(d.key, it); editDef = null }, onDismiss = { editDef = null }) }
    themePick?.let { dark ->
        val themes = hs?.themes?.filter { it.isDark == dark }?.sortedBy { it.name.lowercase() } ?: emptyList()
        PickerDialog(
            if (dark) tr("Тёмная тема подсветки", "Dark highlight theme") else tr("Светлая тема подсветки", "Light highlight theme"),
            themes.map { PickItem(it.id, it.name) }, st.str(if (dark) K.HL_DARK else K.HL_LIGHT),
            { vm.pickSyntaxTheme(it.id, dark); themePick = null }, { themePick = null },
        )
    }
    if (licenses) LicensesDialog(ctx) { licenses = false }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Surface(
        shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.border.copy(alpha = 0.7f)),
        modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun RowDivider() { HorizontalDivider(color = LocalAppColors.current.border.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp)) }

@Composable
private fun ValueRow(title: String, value: String?, desc: String?, mono: Boolean = false, danger: Boolean = false, onClick: (() -> Unit)?) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) c.danger else c.text, fontSize = 15.sp)
            if (desc != null) Text(desc, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) Text(value, color = c.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.widthIn(max = 150.dp))
    }
}

@Composable
private fun SwitchRow(d: SDef, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.title, color = c.text, fontSize = 15.sp)
            if (d.desc.isNotEmpty()) Text(d.desc, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.border,
            ),
        )
    }
}

private fun fmt(d: SDef, v: Double): String =
    if (d.type == SType.INT) v.roundToInt().toString() else if (d.step >= 0.1) "%.1f".format(v) else "%.2f".format(v)

@Composable
private fun SliderRow(d: SDef, st: app.codeedit.data.Settings, onCommit: (Any) -> Unit) {
    val c = LocalAppColors.current
    val cur = if (d.type == SType.INT) st.int(d.key).toDouble() else st.float(d.key).toDouble()
    var drag by remember(d.key, cur) { mutableFloatStateOf(cur.toFloat()) }
    var typing by remember { mutableStateOf(false) }
    fun snap(v: Float): Double {
        val step = if (d.step > 0) d.step else 1.0
        val s = Math.round((v - d.min) / step) * step + d.min
        return s.coerceIn(d.min, d.max)
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.title, color = c.text, fontSize = 15.sp)
                if (d.desc.isNotEmpty()) Text(d.desc, color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(fmt(d, snap(drag)), color = c.accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { typing = true }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        }
        Slider(
            value = drag, onValueChange = { drag = snap(it).toFloat() }, valueRange = d.min.toFloat()..d.max.toFloat(),
            onValueChangeFinished = { val v = snap(drag); onCommit(if (d.type == SType.INT) v.roundToInt() else v.toFloat()) },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
        )
    }
    if (typing) {
        TextEditDialog(d, fmt(d, cur), numeric = true, onSave = { s ->
            typing = false
            val v = s.trim().replace(',', '.').toDoubleOrNull()
            if (v != null && v >= d.min - 1e-9 && v <= d.max + 1e-9) onCommit(if (d.type == SType.INT) v.roundToInt() else v.toFloat())
        }, onDismiss = { typing = false })
    }
}

@Composable
private fun TextEditDialog(d: SDef, initial: String, numeric: Boolean = false, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        title = { Text(d.title, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimField(text, { text = it }, Modifier.fillMaxWidth(), mono = true, singleLine = !(d.type == SType.STRING && d.key == K.G_KEYS_SET),
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
                if (numeric) Text("${fmt(d, d.min)} … ${fmt(d, d.max)}", fontSize = 12.sp)
                else if (d.desc.isNotEmpty()) Text(d.desc, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Сохранить", "Save"), color = c.accent) } },
        dismissButton = {
            Row {
                if (!numeric) TextButton(onClick = { text = d.default.toString() }) { Text(tr("По умолчанию", "Default"), color = c.muted) }
                TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel"), color = c.muted) }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------- about / licenses

@Composable
private fun AboutSection(vm: EditorViewModel, onLicenses: () -> Unit) {
    val c = LocalAppColors.current
    val hs = vm.highlight
    val pi = hs?.packageInfo
    SectionHeader(tr("О приложении", "About"))
    SectionCard {
        ValueRow(tr("Пакет подсветки", "Highlighting package"), null,
            if (pi != null) "${pi.grammarName} ${pi.grammarVersion} (${pi.languageCount} " + tr("языков", "languages") + ")\n${pi.themeName} ${pi.themeVersion} (${pi.themeCount} " + tr("тем", "themes") + ")\n${pi.engine}"
            else (vm.app.highlightError ?: tr("недоступен", "unavailable")), onClick = null)
        RowDivider()
        ValueRow(tr("Нативный движок", "Native engine"), if (hs == null) tr("нет", "no") else if (hs.engineAvailable) tr("работает", "loaded") else tr("не загружен", "not loaded"), null, onClick = null)
        RowDivider()
        ValueRow(tr("Лицензии", "Licenses"), null, tr("TextMate-грамматики, темы, движок, шрифт", "TextMate grammars, themes, engine, font"), onClick = onLicenses)
    }
    Spacer(Modifier.height(18.dp))
    Text(
        "Code Edit ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}",
        color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun LicensesDialog(ctx: Context, onDismiss: () -> Unit) {
    var files by remember { mutableStateOf<List<String>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { files = withContext(Dispatchers.IO) { try { ctx.assets.list("licenses")?.sorted() ?: emptyList() } catch (_: Throwable) { emptyList() } } }
    LaunchedEffect(open) {
        val f = open ?: return@LaunchedEffect
        text = withContext(Dispatchers.IO) { try { ctx.assets.open("licenses/$f").use { String(it.readBytes(), Charsets.UTF_8) } } catch (t: Throwable) { t.message ?: "error" } }
    }
    val list = files
    if (open == null) {
        if (list != null) PickerDialog(tr("Лицензии", "Licenses"), list.map { PickItem(it, it) }, null, { open = it.id }, onDismiss)
    } else {
        val c = LocalAppColors.current
        val body = text
        AlertDialog(
            onDismissRequest = { open = null; text = null }, containerColor = c.surface, titleContentColor = c.text,
            title = { Text(open ?: "", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (body == null) Text("…", color = c.muted)
                else {
                    val lines = remember(body) { body.lines() }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) { itemsIndexed(lines) { _, l -> Text(l, color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp) } }
                }
            },
            confirmButton = { TextButton(onClick = { open = null; text = null }) { Text("OK", color = c.accent) } },
        )
    }
}
