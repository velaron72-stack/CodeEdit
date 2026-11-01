package app.codeedit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.codeedit.core.doc.DocMode
import app.codeedit.data.ExtraKey
import app.codeedit.data.ExtraKeysParser
import app.codeedit.data.K
import app.codeedit.data.Lang
import app.codeedit.data.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Things only the activity can do (system pickers, share sheet, going back to the caller). */
class Actions(
    val openFiles: () -> Unit,
    val openFolder: () -> Unit,
    val returnToCaller: () -> Unit,
    val share: (Tab) -> Unit,
    val copyText: (String) -> Unit,
    val exportSettings: () -> Unit,
    val importSettings: () -> Unit,
    val importFont: () -> Unit,
)

@Composable
fun EditorScreen(vm: EditorViewModel, actions: Actions) {
    val c = LocalAppColors.current
    val tab = vm.active
    val st = vm.settings
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
            TopBar(vm, actions)
            if (tab != null && tab.externalChanged) ExternalBanner(vm, tab)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == null) EmptyState(vm, actions)
                else {
                    EditorHost(vm, tab, c.editor, Modifier.fillMaxSize())
                    if (tab.state != TabState.READY) TabStateOverlay(vm, tab)
                    else {
                        if (tab.indexProgress < 1f) LinearProgressIndicator(progress = { tab.indexProgress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = Color.Transparent)
                        else if (tab.saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = Color.Transparent)
                    }
                }
            }
            if (tab != null && tab.state == TabState.READY) {
                if (vm.search.visible) SearchPanel(vm)
                if (st.bool(K.G_KEYS) && tab.editable) ExtraKeysRow(vm, st.str(K.G_KEYS_SET))
                StatusBar(vm, tab)
            }
        }
        FloatingReturn(vm, actions.returnToCaller, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())
    }
}

// ---------------------------------------------------------------------------------------------- top bar

@Composable
private fun TopBar(vm: EditorViewModel, actions: Actions) {
    val c = LocalAppColors.current
    var tabsPicker by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var mainMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height((52 * c.scale).dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Default.Apps, tr("Открытые файлы", "Open files"), { tabsPicker = true })
        TabStrip(vm, Modifier.weight(1f))
        Box {
            IconAction(Icons.Default.Add, tr("Создать или открыть", "New or open"), { newMenu = true })
            NewMenu(vm, actions, newMenu) { newMenu = false }
        }
        Box {
            IconAction(Icons.Default.Menu, tr("Меню", "Menu"), { mainMenu = true })
            MainMenu(vm, actions, mainMenu) { mainMenu = false }
        }
    }
    HorizontalDivider(color = c.border.copy(alpha = 0.6f))
    if (tabsPicker) TabsPicker(vm, actions) { tabsPicker = false }
}

@Composable
private fun TabsPicker(vm: EditorViewModel, actions: Actions, onDismiss: () -> Unit) {
    val items = vm.tabs.map { t ->
        PickItem(t.id, (if (t.modified) "● " else "") + t.name, t.uri?.let { android.net.Uri.decode(it.toString()) } ?: tr("Не сохранён", "Not saved"),
            if (t.languageId == "plaintext") null else t.languageName, search = t.name + " " + (t.uri?.toString() ?: ""))
    }
    PickerDialog(tr("Открытые файлы", "Open files"), items, vm.activeId, { vm.activate(it.id); onDismiss() }, onDismiss, footer = {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyChip(tr("Файлы", "Files"), { onDismiss(); actions.openFiles() }, mono = false, modifier = Modifier.weight(1f))
            KeyChip(tr("Папка", "Folder"), { onDismiss(); actions.openFolder() }, mono = false, modifier = Modifier.weight(1f))
            KeyChip(tr("Новый", "New"), { onDismiss(); vm.newDocument() }, mono = false, modifier = Modifier.weight(1f))
        }
    })
}

@Composable
private fun TabStrip(vm: EditorViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vm.activeId, vm.tabs.size) {
        val i = vm.tabs.indexOfFirst { it.id == vm.activeId }
        if (i >= 0 && dragId == null) {
            val vis = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
            val vp = listState.layoutInfo.viewportEndOffset
            if (vis == null || vis.offset < 0 || vis.offset + vis.size > vp) listState.animateScrollToItem(i)
        }
    }
    LazyRow(
        state = listState, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vm.tabs, key = { it.id }) { tab ->
            val dragging = dragId == tab.id
            val base = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationX = dragDx } else Modifier.animateItem()
            TabChip(
                vm, tab, tab.id == vm.activeId,
                base.pointerInput(tab.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragId = tab.id; dragDx = 0f },
                        onDragEnd = { dragId = null; dragDx = 0f },
                        onDragCancel = { dragId = null; dragDx = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragDx += amount.x
                            val infos = listState.layoutInfo.visibleItemsInfo
                            val cur = infos.firstOrNull { it.key == tab.id }
                            if (cur != null) {
                                val center = cur.offset + dragDx + cur.size / 2f
                                val target = infos.firstOrNull { it.key != tab.id && center >= it.offset && center <= it.offset + it.size }
                                if (target != null) {
                                    val newOffset = if (target.index > cur.index) target.offset + target.size - cur.size else target.offset
                                    vm.moveTab(cur.index, target.index)
                                    dragDx -= (newOffset - cur.offset)
                                }
                            }
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun TabChip(vm: EditorViewModel, tab: Tab, active: Boolean, modifier: Modifier) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Row(
            Modifier.height(36.dp).clip(shape)
                .background(if (active) c.surface2 else c.surface)
                .border(1.dp, if (active) c.accent.copy(alpha = 0.6f) else c.border, shape)
                .clickable { if (active) menu = true else vm.activate(tab.id) }
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Description, null, tint = if (active) c.accent else c.muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(tab.name, color = if (active) c.text else c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.widthIn(max = 150.dp))
            if (tab.modified) Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(c.accent))
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { vm.requestClose(tab) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, tr("Закрыть", "Close"), tint = c.muted, modifier = Modifier.size(15.dp))
            }
        }
        if (active) TabMenu(vm, tab, menu) { menu = false }
    }
}

// ---------------------------------------------------------------------------------------------- banners / overlays

@Composable
private fun ExternalBanner(vm: EditorViewModel, tab: Tab) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(tr("Файл изменён на диске", "File changed on disk"), color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        KeyChip(tr("Перезагрузить", "Reload"), { vm.dialog = DialogState.ExternalReload(tab) }, mono = false, height = 30.dp)
        KeyChip(tr("Оставить мой", "Keep mine"), { vm.keepMine(tab) }, mono = false, height = 30.dp)
    }
}

@Composable
private fun TabStateOverlay(vm: EditorViewModel, tab: Tab) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(24.dp).pointerInput(Unit) { detectTapGestures { } },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (tab.state == TabState.FAILED) {
            Text(tr("Не удалось открыть файл", "Cannot open the file"), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(tab.error ?: "", color = c.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyChip(tr("Повторить", "Retry"), { vm.load(tab, ignoreDraft = false) }, mono = false)
                KeyChip(tr("Закрыть вкладку", "Close tab"), { vm.closeNow(tab) }, mono = false)
            }
        } else {
            CircularProgressIndicator(color = c.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(tab.name, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(vm: EditorViewModel, actions: Actions) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Description, null, tint = c.border, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        // The spec fixes this exact wording; English UI adds a translated subtitle.
        Text("Ничего не открыто", color = c.muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (!Lang.ru) Text("Nothing is open", color = c.muted.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(tr("Открыть файлы", "Open files"), Icons.Default.Description, actions.openFiles, primary = true)
            PillButton(tr("Открыть папку", "Open folder"), Icons.Default.Folder, actions.openFolder)
            PillButton(tr("Новый файл", "New file"), Icons.Default.NoteAdd, { vm.newDocument() })
        }
        if (vm.recent.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Text(tr("Недавние", "Recent"), color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                for (r in vm.recent.take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openRecent(r) }.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(if (r.name.endsWith("/")) Icons.Default.Folder else Icons.Default.Description, null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Text(r.name, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, primary: Boolean = false) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(if (primary) c.accent else c.surface).border(1.dp, if (primary) c.accent else c.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (primary) c.onAccent else c.text, modifier = Modifier.size(19.dp))
        Text(label, color = if (primary) c.onAccent else c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------- bottom bars

@Composable
private fun StatusBar(vm: EditorViewModel, tab: Tab) {
    val c = LocalAppColors.current
    Column {
        HorizontalDivider(color = c.border.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().height((30 * c.scale).dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusItem(tab.languageName) { vm.dialog = DialogState.Language }
            StatusItem(tab.encodingLabel) { vm.dialog = DialogState.Encoding }
            StatusItem(tab.eolLabel) { vm.dialog = DialogState.Eol }
            val sel = vm.selectionLength
            val pos = "${tr("Стр", "Ln")} ${vm.cursorLine}, ${tr("Кол", "Col")} ${vm.cursorCol}" + if (sel > 0) " (${sel})" else ""
            StatusItem(pos) { vm.dialog = DialogState.Goto }
            if (tab.indexProgress < 1f) StatusItem(tr("Индексация ", "Indexing ") + (tab.indexProgress * 100).toInt() + "%") {}
            StatusItem(if (tab.mode == DocMode.VIEW) tr("Просмотр", "View") else tr("Правка", "Edit"), highlight = tab.mode == DocMode.VIEW) {
                if (tab.mode == DocMode.VIEW) vm.switchToEdit(tab) else vm.switchToView(tab)
            }
            tab.highlightNote?.let { note -> StatusItem(tr("без подсветки", "no highlight")) { vm.toast(note) } }
        }
    }
}

@Composable
private fun StatusItem(text: String, highlight: Boolean = false, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Text(
        text, color = if (highlight) c.accent else c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 150.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

@Composable
private fun ExtraKeysRow(vm: EditorViewModel, layout: String) {
    val c = LocalAppColors.current
    val keys = remember(layout) { ExtraKeysParser.parse(layout) }
    if (keys.isEmpty()) return
    Column {
        HorizontalDivider(color = c.border.copy(alpha = 0.6f))
        LazyRow(
            Modifier.fillMaxWidth().height((46 * c.scale).dp), contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(keys) { _, k -> ExtraKeyButton(vm, k) }
        }
    }
}

private fun fireKey(vm: EditorViewModel, k: ExtraKey) {
    val e = vm.editor ?: return
    when (k) {
        is ExtraKey.Text -> e.insertText(k.text)
        ExtraKey.Tab -> e.insertText("\t")
        is ExtraKey.Nav -> e.sendKey(k.keyCode, k.ctrl)
        is ExtraKey.Combo -> e.sendKey(k.keyCode, true)
        ExtraKey.Undo -> e.undo()
        ExtraKey.Redo -> e.redo()
    }
}

@Composable
private fun ExtraKeyButton(vm: EditorViewModel, k: ExtraKey) {
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val fire = rememberUpdatedState { fireKey(vm, k) }
    val repeat = k is ExtraKey.Nav
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier.heightIn(min = 34.dp).widthIn(min = 38.dp).clip(shape)
            .background(if (pressed) c.surface2 else c.surface).border(1.dp, c.border, shape)
            .pointerInput(k) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    var started = false
                    val job = if (repeat) scope.launch { delay(380); started = true; while (true) { fire.value(); delay(45) } } else null
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    if (up != null && !started) fire.value()
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(k.label, color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------- floating return button

/** Draggable round button (no overlay permission: it lives inside the app window). Tapping it returns to the calling app. */
@Composable
fun FloatingReturn(vm: EditorViewModel, onReturn: () -> Unit, modifier: Modifier = Modifier) {
    val st = vm.settings
    val show = when (st.str(K.FAB_MODE)) { "always" -> true; "never" -> false; else -> vm.tabs.any { it.fromExternal } }
    if (!show) return
    val c = LocalAppColors.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val sizeDp = st.int(K.FAB_SIZE).dp
        val sizePx = with(density) { sizeDp.toPx() }
        val marginPx = with(density) { 8.dp.toPx() }
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val range = max(1f, hPx - sizePx)
        val restX = if (st.str(K.FAB_SIDE) == "left") marginPx else wPx - sizePx - marginPx
        val restY = (st.float(K.FAB_Y) * range).coerceIn(0f, range)
        var px by remember(restX) { mutableFloatStateOf(restX) }
        var py by remember(restY) { mutableFloatStateOf(restY) }
        Box(
            Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }.size(sizeDp).alpha(st.float(K.FAB_OPACITY))
                .shadow(6.dp, CircleShape).clip(CircleShape).background(c.surface2).border(1.dp, c.border, CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { onReturn() }) }
                .pointerInput(wPx, hPx, sizePx, restX, restY) {
                    detectDragGestures(
                        onDragEnd = {
                            val left = px + sizePx / 2f < wPx / 2f
                            px = if (left) marginPx else wPx - sizePx - marginPx
                            vm.setSetting(K.FAB_SIDE, if (left) "left" else "right")
                            vm.setSetting(K.FAB_Y, (py / range).coerceIn(0f, 1f))
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            px = (px + amount.x).coerceIn(0f, max(0f, wPx - sizePx))
                            py = (py + amount.y).coerceIn(0f, range)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Вернуться", "Return"), tint = c.text, modifier = Modifier.size(22.dp))
        }
    }
}
