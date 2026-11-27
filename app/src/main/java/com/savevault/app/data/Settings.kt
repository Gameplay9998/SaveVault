package com.savevault.app.data

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Pasta (SAF) escolhida para os backups locais. Nulo = pasta interna do app. */
    var localFolderUri: String?
        get() = prefs.getString("localFolderUri", null)
        set(v) = prefs.edit { putString("localFolderUri", v) }

    var autoEnabled: Boolean
        get() = prefs.getBoolean("autoEnabled", true)
        set(v) = prefs.edit { putBoolean("autoEnabled", v) }

    var autoIntervalHours: Int
        get() = prefs.getInt("autoIntervalHours", 6)
        set(v) = prefs.edit { putInt("autoIntervalHours", v) }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit { putBoolean("wifiOnly", v) }

    var driveConnected: Boolean
        get() = prefs.getBoolean("driveConnected", false)
        set(v) = prefs.edit { putBoolean("driveConnected", v) }

    var driveEmail: String?
        get() = prefs.getString("driveEmail", null)
        set(v) = prefs.edit { putString("driveEmail", v) }

    var driveFolderId: String?
        get() = prefs.getString("driveFolderId", null)
        set(v) = prefs.edit { putString("driveFolderId", v) }

    var lastAutoRun: Long
        get() = prefs.getLong("lastAutoRun", 0)
        set(v) = prefs.edit { putLong("lastAutoRun", v) }
}
