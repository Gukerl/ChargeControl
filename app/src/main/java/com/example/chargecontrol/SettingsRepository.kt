package com.example.chargecontrol

import android.content.Context

object SettingsRepository {
    const val DEFAULT_EVCC_HOST = "192.168.224.24"
    const val DEFAULT_EVCC_PORT = 7070
    const val DEFAULT_GOE_HOST = "192.168.224.245"
    const val DEFAULT_GOE_TRX = 1
    const val DEFAULT_HOLD_CONFIRM_MS = 1000
    const val DEFAULT_GOE_ENABLED = false
    const val DEFAULT_GOE_AUTO_AUTHORIZE = false
    /** Empty string means "follow the system language". */
    const val DEFAULT_LANGUAGE = ""

    private const val KEY_EVCC_HOST = "evcc_host"
    private const val KEY_EVCC_PORT = "evcc_port"
    private const val KEY_GOE_HOST = "goe_host"
    private const val KEY_GOE_TRX = "goe_trx"
    private const val KEY_HOLD_CONFIRM_MS = "hold_confirm_ms"
    private const val KEY_GOE_ENABLED = "goe_enabled"
    private const val KEY_GOE_AUTO_AUTHORIZE = "goe_auto_authorize"

    /** Shared Kotlin/Java-independent key name, also read directly by
     *  [com.example.chargecontrol.MainActivity.attachBaseContext] before this
     *  repository is initialized. Keep in sync if renamed. */
    const val KEY_LANGUAGE = "language"

    @Volatile
    private lateinit var store: KeyValueStore

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = SharedPreferencesKeyValueStore(context)
    }

    internal fun initWithStore(store: KeyValueStore) {
        this.store = store
    }

    var evccHost: String
        get() = store.getString(KEY_EVCC_HOST, DEFAULT_EVCC_HOST)
        set(value) = store.putString(KEY_EVCC_HOST, value)

    var evccPort: Int
        get() = store.getInt(KEY_EVCC_PORT, DEFAULT_EVCC_PORT)
        set(value) = store.putInt(KEY_EVCC_PORT, value)

    var goeHost: String
        get() = store.getString(KEY_GOE_HOST, DEFAULT_GOE_HOST)
        set(value) = store.putString(KEY_GOE_HOST, value)

    var goeTrx: Int
        get() = store.getInt(KEY_GOE_TRX, DEFAULT_GOE_TRX)
        set(value) = store.putInt(KEY_GOE_TRX, value)

    var holdConfirmMs: Int
        get() = store.getInt(KEY_HOLD_CONFIRM_MS, DEFAULT_HOLD_CONFIRM_MS)
        set(value) = store.putInt(KEY_HOLD_CONFIRM_MS, value)

    var goeEnabled: Boolean
        get() = store.getBoolean(KEY_GOE_ENABLED, DEFAULT_GOE_ENABLED)
        set(value) = store.putBoolean(KEY_GOE_ENABLED, value)

    var goeAutoAuthorize: Boolean
        get() = store.getBoolean(KEY_GOE_AUTO_AUTHORIZE, DEFAULT_GOE_AUTO_AUTHORIZE)
        set(value) = store.putBoolean(KEY_GOE_AUTO_AUTHORIZE, value)

    /** "" = follow system language, otherwise a BCP-47 tag like "de" or "en". */
    var language: String
        get() = store.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
        set(value) = store.putString(KEY_LANGUAGE, value)
}
