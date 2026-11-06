package com.livenews.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** How often headlines refresh while the app is on screen. */
    val foregroundRefreshMinutes: Int = 3,
    /** How often headlines refresh in the background (this is what keeps the widget current). 0 = never. */
    val backgroundRefreshMinutes: Int = 15,
    /** Open articles in a browser tab inside the app rather than switching to the browser. */
    val openInApp: Boolean = true,
    val showImages: Boolean = true,
    /** Look for a newer version of the app when it is opened. */
    val autoUpdateCheck: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit()
            .putInt(FOREGROUND, next.foregroundRefreshMinutes)
            .putInt(BACKGROUND, next.backgroundRefreshMinutes)
            .putBoolean(OPEN_IN_APP, next.openInApp)
            .putBoolean(SHOW_IMAGES, next.showImages)
            .putBoolean(AUTO_UPDATE_CHECK, next.autoUpdateCheck)
            .apply()
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            foregroundRefreshMinutes = prefs.getInt(FOREGROUND, defaults.foregroundRefreshMinutes),
            backgroundRefreshMinutes = prefs.getInt(BACKGROUND, defaults.backgroundRefreshMinutes),
            openInApp = prefs.getBoolean(OPEN_IN_APP, defaults.openInApp),
            showImages = prefs.getBoolean(SHOW_IMAGES, defaults.showImages),
            autoUpdateCheck = prefs.getBoolean(AUTO_UPDATE_CHECK, defaults.autoUpdateCheck),
        )
    }

    private companion object {
        const val FOREGROUND = "foreground_refresh_minutes"
        const val BACKGROUND = "background_refresh_minutes"
        const val OPEN_IN_APP = "open_in_app"
        const val SHOW_IMAGES = "show_images"
        const val AUTO_UPDATE_CHECK = "auto_update_check"
    }
}
