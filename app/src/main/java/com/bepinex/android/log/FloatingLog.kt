package com.bepinex.android.log

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.bepinex.android.BepInExLog
import com.bepinex.android.settings.AppSettings

/**
 * Attaches the in-game floating log overlay, mirroring the upstream UnityPlayerHooks.
 *
 * The upstream launcher shows the overlay from a Pine hook on the UnityPlayer constructor; this
 * port uses the Instrumentation-based runtime instead, so it is triggered once
 * from [com.bepinex.android.hooks.GameRuntime] after the game Activity resumes.
 *
 * Gated by [AppSettings.isFloatingModMenuEnabled] (default off).
 * The overlay is a DecorView FAB that tails LogOutput.log — no IL2CPP `.text` patch and no
 * ClassInjector / UnityLogListening path.
 */
object FloatingLog {

    private const val ATTACH_DELAY_MS = 2000L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var attached = false

    @JvmStatic
    fun maybeAttach(activity: Activity, packageName: String) {
        if (attached) return
        if (!AppSettings.isFloatingModMenuEnabled(activity)) return
        attached = true
        handler.postDelayed({
            try {
                // Re-check: the preference may have been flipped during the delay.
                if (!AppSettings.isFloatingModMenuEnabled(activity)) {
                    attached = false
                    return@postDelayed
                }
                GameLogOverlay.show(activity, packageName)
                BepInExLog.i("Floating log overlay attached to ${activity.javaClass.name}")
            } catch (t: Throwable) {
                attached = false
                BepInExLog.e("Failed to attach floating log overlay", t)
            }
        }, ATTACH_DELAY_MS)
    }
}
