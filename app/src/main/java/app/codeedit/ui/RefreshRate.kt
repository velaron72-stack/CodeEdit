package app.codeedit.ui

import android.view.Window

/**
 * Many phones keep an app that "does not animate" at 60 Hz even though the panel can do 90/120 Hz, which makes scrolling look laggy.
 * While the app is in the foreground we ask for the fastest display mode of the CURRENT resolution (no resolution switch).
 */
object RefreshRate {
    @Suppress("DEPRECATION")
    fun apply(window: Window, high: Boolean) {
        try {
            val lp = window.attributes
            if (!high) {
                if (lp.preferredDisplayModeId != 0 || lp.preferredRefreshRate != 0f) {
                    lp.preferredDisplayModeId = 0; lp.preferredRefreshRate = 0f; window.attributes = lp
                }
                return
            }
            val display = window.windowManager.defaultDisplay
            val cur = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (lp.preferredDisplayModeId != best.modeId || lp.preferredRefreshRate != best.refreshRate) {
                lp.preferredDisplayModeId = best.modeId
                lp.preferredRefreshRate = best.refreshRate
                window.attributes = lp
            }
        } catch (_: Throwable) { /* a request only: never fatal */ }
    }
}
