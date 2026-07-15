package com.flux.payload.dumper.data

import android.content.Context
import com.flux.payload.dumper.DumperApplication

/** Thin typed wrapper over the app's default SharedPreferences. */
object Preferences {

    private val prefs by lazy {
        DumperApplication.appContext.getSharedPreferences("flux_dumper", Context.MODE_PRIVATE)
    }

    // Keys
    const val KEY_PATH_OR_URL = "path_or_url"
    const val KEY_OUTPUT_FOLDER = "output_folder"
    const val KEY_CUSTOM_UA_ENABLED = "custom_ua_enabled"
    const val KEY_CUSTOM_UA = "custom_ua"
    const val KEY_VERIFY_ENABLED = "verify_enabled"
    const val KEY_WORKERS = "workers"

    fun getString(key: String, default: String? = null): String? = prefs.getString(key, default)
    fun setString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun setBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    fun setInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}
