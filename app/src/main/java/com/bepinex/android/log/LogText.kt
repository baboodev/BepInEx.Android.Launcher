package com.bepinex.android.log

import android.content.Context
import android.content.res.Configuration
import com.bepinex.android.BuildConfig
import com.bepinex.android.settings.AppSettings

/**
 * Resolves a context whose resources belong to the launcher package and whose locale
 * follows the launcher language setting.
 *
 * The in-game overlay runs with the game's resources swapped in (see
 * [com.bepinex.android.hooks.GameRuntime]), so calling `activity.getString(launcherId)`
 * would resolve against the game's resource table — or throw. Always look up overlay
 * text through this helper.
 */
internal object LogText {

    fun launcherContext(base: Context): Context {
        val launcher = try {
            base.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        } catch (_: Exception) {
            base
        }
        val locale = AppSettings.getLanguage(launcher).toLocale() ?: return launcher
        val configuration = Configuration(launcher.resources.configuration)
        configuration.setLocale(locale)
        return try {
            launcher.createConfigurationContext(configuration)
        } catch (_: Exception) {
            launcher
        }
    }

}
