package app.codeedit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codeedit.data.tr
import kotlinx.coroutines.delay

/** Bottom find / replace panel. All state lives in [SearchController]; the panel only renders and forwards. */
@Composable
fun SearchPanel(vm: EditorViewModel) {
    val c = LocalAppColors.current
    val s = vm.search
    val focus = remember { FocusRequester() }
    var firstRun by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Throwable) {} }
    // debounce typing: one rebuild per pause, not per keystroke (the old scan is cancelled by rebuild)
    LaunchedEffect(s.query) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        delay(140)
        s.onOptionsChanged()
    }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.border.copy(alpha = 0.6f))
        if (s.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = Color.Transparent)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SlimField(
                s.query, { s.query = it }, Modifier.weight(1f), tr("Найти", "Find"),
                leading = { Icon(Icons.Default.Search, null, tint = c.muted, modifier = Modifier.size(18.dp)) },
                trailing = { SearchCount(s) }, mono = true, focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { s.next() }),
            )
            IconAction(Icons.Default.KeyboardArrowUp, tr("Предыдущее", "Previous"), { s.prev() }, enabled = s.query.isNotEmpty())
            IconAction(Icons.Default.KeyboardArrowDown, tr("Следующее", "Next"), { s.next() }, enabled = s.query.isNotEmpty())
            IconAction(Icons.Default.Close, tr("Закрыть", "Close"), { s.close() })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyChip("Aa", { s.matchCase = !s.matchCase; s.onOptionsChanged() }, selected = s.matchCase, height = 30.dp)
            KeyChip("W", { s.wholeWord = !s.wholeWord; s.onOptionsChanged() }, selected = s.wholeWord, height = 30.dp)
            KeyChip(".*", { s.regex = !s.regex; s.onOptionsChanged() }, selected = s.regex, height = 30.dp)
            IconChip(Icons.Default.FindReplace, s.replaceMode) { s.replaceMode = !s.replaceMode }
            s.error?.let { Text(it, color = c.danger, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f)) }
        }
        if (s.replaceMode) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SlimField(s.replacement, { s.replacement = it }, Modifier.weight(1f), tr("Заменить на", "Replace with"), mono = true)
                KeyChip(tr("Заменить", "Replace"), { s.replaceCurrent() }, mono = false, height = 36.dp)
                KeyChip(tr("Все", "All"), { s.replaceAll() }, mono = false, height = 36.dp)
            }
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.height(30.dp).widthIn(min = 40.dp).clip(shape).background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.border, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (selected) c.accent else c.text, modifier = Modifier.size(17.dp)) }
}

@Composable
private fun SearchCount(s: SearchController) {
    val c = LocalAppColors.current
    val text = when {
        s.query.isEmpty() || s.error != null -> ""
        s.notFound && s.count == 0L && !s.running -> tr("нет", "none")
        s.running -> (if (s.ordinal > 0) "${s.ordinal}/" else "") + s.count + "+"
        s.count == 0L -> tr("нет", "none")
        else -> (if (s.ordinal > 0) "${s.ordinal}" else "?") + "/" + s.count + (if (s.partial) "+" else "")
    }
    if (text.isNotEmpty()) Text(text, color = if (s.count == 0L && !s.running) c.danger else c.muted, fontSize = 12.sp, maxLines = 1)
}
