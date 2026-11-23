package com.bepinex.android

import android.content.Intent
import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.bepinex.android.hooks.DeviceOrientationGate
import com.bepinex.android.remote.ModSettingsClient
import com.bepinex.android.settings.AppSettings
import com.bepinex.android.ui.screens.ModRemoteScreen
import com.bepinex.android.ui.theme.BepInExTheme

/**
 * Portrait mod control panel in the default process. Opened by
 * [com.bepinex.android.hooks.RotatePipRemoteController] when the device
 * rotates to portrait while the game is in PiP.
 */
class ModRemoteActivity : ComponentActivity() {

    companion object {
        const val EXTRA_GAME_PACKAGE = "game_package"
        const val EXTRA_SESSION = "session"
        const val EXTRA_FINISH = "finish"
    }

    private var landscapeGate: OrientationEventListener? = null
    private val settingsClient = ModSettingsClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getIntExtra(EXTRA_FINISH, 0) == 1) {
            finish()
            return
        }
        settingsClient.bind(this)
        val themeMode = AppSettings.getThemeMode(this)
        val dynamicColor = AppSettings.isDynamicColorEnabled(this)
        setContent {
            BepInExTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface {
                    ModRemoteScreen(client = settingsClient)
                }
            }
        }
        installLandscapeFinishGate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getIntExtra(EXTRA_FINISH, 0) == 1) {
            finish()
        }
    }

    override fun onDestroy() {
        landscapeGate?.disable()
        landscapeGate = null
        settingsClient.unbind(this)
        super.onDestroy()
    }

    private fun installLandscapeFinishGate() {
        // Belt and braces if the :game process finish request is missed.
        val gate = object : OrientationEventListener(applicationContext) {
            private var pendingLandscape = false
            private val commit = Runnable {
                if (pendingLandscape && !isFinishing) finish()
            }

            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return
                val portrait = DeviceOrientationGate.isPortraitAngle(angle)
                if (portrait) {
                    pendingLandscape = false
                    window.decorView.removeCallbacks(commit)
                    return
                }
                if (pendingLandscape) return
                pendingLandscape = true
                window.decorView.removeCallbacks(commit)
                window.decorView.postDelayed(commit, 500L)
            }
        }
        if (gate.canDetectOrientation()) {
            gate.enable()
            landscapeGate = gate
        }
    }
}
