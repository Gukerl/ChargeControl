package com.example.chargecontrol

import android.content.Context
import androidx.core.content.edit

interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("chargecontrol_settings", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }
}
