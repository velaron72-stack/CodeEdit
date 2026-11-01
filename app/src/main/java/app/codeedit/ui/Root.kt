package app.codeedit.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.codeedit.CodeEditApp
import app.codeedit.data.K
import app.codeedit.data.tr
import app.codeedit.editor.EditorView
import app.codeedit.files.UriFiles
import java.io.ByteArrayOutputStream
import java.io.InputStream

val LocalActions = staticCompositionLocalOf<Actions?> { null }

private fun readLimited(ins: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = ins.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
fun Root(vm: EditorViewModel, returnToCaller: () -> Unit) {
    val ctx = LocalContext.current
    val app = CodeEditApp.of(ctx)
    val settings = vm.settings
    val dark = appIsDark(settings)
    var themed by remember { mutableStateOf(false) }
    LaunchedEffect(vm.ready, settings.str(K.HL_DARK), settings.str(K.HL_LIGHT), dark, vm.highlight) {
        if (vm.ready) { vm.ensureSyntaxTheme(dark); themed = true }
    }

    // ---- system pickers
    val openDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.openUris(uris) }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.openFolder(uri) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.pendingSaveAs?.let { vm.onSaveAsResult(it, uri) }
    }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val bytes = vm.exportSettingsBytes()
            app.ioPool.execute {
                val ok = try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null } catch (_: Throwable) { false }
                app.main.post { vm.toast(if (ok) tr("Настройки экспортированы", "Settings exported") else tr("Не удалось записать файл", "Could not write the file")) }
            }
        }
    }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.ioPool.execute {
            val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { readLimited(it, 2_000_000) } } catch (_: Throwable) { null }
            app.main.post {
                if (bytes == null) vm.dialog = DialogState.Error(tr("Импорт не выполнен", "Import failed"), tr("Файл не читается или слишком большой (> 2 МБ).", "The file cannot be read or is too large (> 2 MB)."))
                else vm.importSettings(bytes)
            }
        }
    }
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.ioPool.execute {
            val name = try { UriFiles.queryInfo(app, uri).name } catch (_: Throwable) { null }
            val (font, err) = app.fonts.importFont(uri, name)
            app.main.post {
                if (font != null) { vm.setSetting(K.FONT_FAMILY, font.id); vm.toast(tr("Шрифт добавлен: ", "Font added: ") + font.name) }
                else vm.toast(tr("Шрифт не принят: ", "Font rejected: ") + (err ?: "?"))
            }
        }
    }
    LaunchedEffect(vm.pendingSaveAs) { vm.pendingSaveAs?.let { saveAs.launch(it.tab.name) } }

    val actions = remember(vm) {
        Actions(
            openFiles = { openDocs.launch(arrayOf("*/*")) },
            openFolder = { openTree.launch(null) },
            returnToCaller = returnToCaller,
            share = { tab ->
                val u: Uri? = tab.uri
                if (u != null) try {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = ctx.contentResolver.getType(u) ?: "text/plain"
                        putExtra(Intent.EXTRA_STREAM, u)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(i, null))
                } catch (_: Throwable) { vm.toast(tr("Не удалось поделиться", "Could not share")) }
            },
            copyText = { t ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", t))
            },
            exportSettings = { exportSettings.launch("codeedit-settings.json") },
            importSettings = { importSettings.launch(arrayOf("*/*")) },
            importFont = { importFont.launch(arrayOf("*/*")) },
        )
    }

    val ec = vm.syntaxTheme?.colors ?: EditorView.DEFAULT_COLORS
    key(settings.str(K.LANGUAGE)) {
        AppTheme(ec, settings) {
            val c = LocalAppColors.current
            SystemBarsEffect(c.dark, c.bg, settings.bool(K.KEEP_ON))
            CompositionLocalProvider(LocalActions provides actions) {
                Box(Modifier.fillMaxSize().background(c.bg)) {
                    if (vm.ready && themed) {
                        when (val sc = vm.screen) {
                            Screen.Editor -> EditorScreen(vm, actions)
                            Screen.Settings -> SettingsScreen(vm, actions)
                            is Screen.Folder -> FolderScreen(vm, sc)
                        }
                        DialogHost(vm, actions)
                        Snacks(vm)
                    }
                }
            }
            BackHandler(enabled = vm.screen is Screen.Settings) { vm.screen = Screen.Editor }
            BackHandler(enabled = vm.screen is Screen.Editor && vm.search.visible) { vm.search.close() }
        }
    }
}

@Composable
private fun SystemBarsEffect(dark: Boolean, bg: Color, keepOn: Boolean) {
    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        w.setBackgroundDrawable(ColorDrawable(bg.toArgb()))
        val ctl = WindowCompat.getInsetsController(w, view)
        ctl.isAppearanceLightStatusBars = !dark
        ctl.isAppearanceLightNavigationBars = !dark
        view.keepScreenOn = keepOn
    }
}

@Composable
private fun Snacks(vm: EditorViewModel) {
    val c = LocalAppColors.current
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        val r = host.showSnackbar(m.text, m.actionLabel, duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) m.action?.invoke()
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host, Modifier.padding(bottom = 92.dp, start = 12.dp, end = 12.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(14.dp), containerColor = c.surface2, contentColor = c.text, actionColor = c.accent)
        }
    }
}
