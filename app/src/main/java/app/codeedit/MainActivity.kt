package app.codeedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.codeedit.data.K
import app.codeedit.ui.EditorViewModel
import app.codeedit.ui.Root

/**
 * Single activity. launchMode=singleTask: a VIEW/EDIT/SEND from another app lands in onNewIntent of the running
 * instance, so every file opens as one more tab instead of a second editor window.
 */
class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After process death the saved session restores the tabs; re-delivering the old launch intent would duplicate them.
        if (savedInstanceState == null) vm.onIntent(intent, fresh = true)
        setContent { Root(vm, ::returnToCaller) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.onIntent(intent, fresh = false)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onStop() {
        super.onStop()
        vm.flushNow()
    }

    /** Back to the app that opened the file. No overlay permission involved: this is an ordinary in-window button. */
    private fun returnToCaller() {
        val t = vm.active
        if (vm.settings.str(K.FAB_ON_RETURN) == "save" && t != null && t.dirty && t.uri != null && t.editable) vm.save(t, false, false) { leave() }
        else leave()
    }

    private fun leave() {
        vm.flushNow()
        if (!isTaskRoot) finish() else moveTaskToBack(true)
    }
}
