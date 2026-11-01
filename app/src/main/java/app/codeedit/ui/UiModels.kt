package app.codeedit.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean

sealed class Screen {
    object Editor : Screen()
    object Settings : Screen()
    class Folder(val rootUri: android.net.Uri, val title: String) : Screen()
}

sealed class DialogState {
    /** Closing tabs that have unsaved changes. [save] offered only for a single tab. */
    class ConfirmClose(val tabs: List<Tab>, val all: Boolean) : DialogState()
    class ExternalOverwrite(val tab: Tab, val then: (() -> Unit)?) : DialogState()
    class ExternalReload(val tab: Tab) : DialogState()
    class Binary(val tab: Tab, val reason: String) : DialogState()
    class Error(val title: String, val message: String, val retryLabel: String? = null, val retry: (() -> Unit)? = null) : DialogState()
    class EnableEdit(val tab: Tab) : DialogState()
    class ConfirmReplaceAll(val tab: Tab, val count: Long) : DialogState()
    class ConfirmSwitchToEdit(val tab: Tab, val sizeMb: Long) : DialogState()
    object Goto : DialogState()
    object Language : DialogState()
    object SyntaxTheme : DialogState()
    object Font : DialogState()
    object Encoding : DialogState()
    object Eol : DialogState()
    object MenuInfo : DialogState()
    object ResetSettings : DialogState()
}

class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null) { val id: Long = System.nanoTime() }

class ProgressState(val title: String, val cancel: AtomicBoolean = AtomicBoolean(false)) {
    var value by mutableFloatStateOf(0f)
}

class SaveAsRequest(val tab: Tab, val then: (() -> Unit)?)
