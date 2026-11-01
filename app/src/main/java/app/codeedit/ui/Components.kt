package app.codeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codeedit.data.tr

@Composable
fun SlimField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "",
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null, singleLine: Boolean = true, minHeight: Dp = 38.dp,
) {
    val c = LocalAppColors.current
    BasicTextField(
        value = value, onValueChange = onValueChange,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
        singleLine = singleLine,
        textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(c.accent), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(Modifier.heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                Box(Modifier.weight(1f).padding(horizontal = if (leading != null) 6.dp else 0.dp)) {
                    if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = c.muted, fontSize = 14.sp, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
fun SearchBox(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val c = LocalAppColors.current
    SlimField(value, onValueChange, modifier, hint,
        leading = { Icon(Icons.Default.Search, null, tint = c.muted, modifier = Modifier.size(18.dp)) },
        trailing = { if (value.isNotEmpty()) Icon(Icons.Default.Close, null, tint = c.muted, modifier = Modifier.size(18.dp).clickable { onValueChange("") }) },
        focusRequester = focus)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Text(text, color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

class PickItem(val id: String, val title: String, val subtitle: String? = null, val badge: String? = null, val section: String? = null, val search: String = title)

/** Rounded searchable list popup (language / theme / font / encoding pickers). */
@Composable
fun PickerDialog(
    title: String, items: List<PickItem>, selectedId: String?, onPick: (PickItem) -> Unit, onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = LocalAppColors.current
    var q by remember { mutableStateOf("") }
    val filtered = remember(q, items) {
        val k = q.trim().lowercase()
        if (k.isEmpty()) items else items.filter { it.search.lowercase().contains(k) }
    }
    val focus = remember { FocusRequester() }
    val searchable = items.size > 10
    LaunchedEffect(Unit) { if (searchable) try { focus.requestFocus() } catch (_: Throwable) {} }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(18.dp), color = c.surface, border = BorderStroke(1.dp, c.border),
            modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 600.dp)) {
            Column {
                Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp))
                if (searchable) SearchBox(q, { q = it }, tr("Поиск", "Search"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(), focus)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    itemsIndexed(filtered, key = { _, it -> it.id }) { i, it ->
                        if (it.section != null && it.section != filtered.getOrNull(i - 1)?.section) SectionHeader(it.section)
                        val sel = it.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(it) }.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(it.title, color = if (sel) c.accent else c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (it.subtitle != null) Text(it.subtitle, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (it.badge != null) Text(it.badge, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            if (sel) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
                if (filtered.isEmpty()) Text(tr("Ничего не найдено", "Nothing found"), color = c.muted, modifier = Modifier.padding(16.dp))
                if (footer != null) { HorizontalDivider(color = c.border); footer() }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismissLabel: String = tr("Отмена", "Cancel"), neutral: String? = null, onNeutral: (() -> Unit)? = null, danger: Boolean = false,
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        title = { Text(title, fontSize = 17.sp) },
        text = { Text(text, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) c.danger else c.accent) } },
        dismissButton = {
            Row {
                if (neutral != null && onNeutral != null) TextButton(onClick = onNeutral) { Text(neutral, color = c.muted) }
                TextButton(onClick = onDismiss) { Text(dismissLabel, color = c.muted) }
            }
        },
    )
}

@Composable
fun IconAction(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalAppColors.current.text, size: Dp = 40.dp, enabled: Boolean = true) {
    IconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled) {
        Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(20.dp))
    }
}

/** Small bordered toggle used in the search panel and the extra-keys row. */
@Composable
fun KeyChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, mono: Boolean = true, minWidth: Dp = 34.dp, height: Dp = 34.dp) {
    val c = LocalAppColors.current
    Box(
        modifier.heightIn(min = height).widthIn(min = minWidth).clip(RoundedCornerShape(9.dp))
            .background(if (selected) c.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) c.accent else c.text, fontSize = 13.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 1)
    }
}

@Composable fun Gap(w: Dp) { Spacer(Modifier.size(w)) }

@Composable
fun SimpleMenuRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, value: String? = null, checked: Boolean? = null, accent: Boolean = false, enabled: Boolean = true) {
    val c = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = (if (accent) c.accent else c.text).copy(alpha = if (enabled) 1f else 0.4f), fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, color = c.muted, fontSize = 12.sp, maxLines = 1)
        if (checked == true) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}
