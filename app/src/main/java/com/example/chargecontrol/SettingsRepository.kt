package com.example.chargecontrol

import android.content.Context

object SettingsRepository {
    const val DEFAULT_EVCC_HOST = "192.168.224.24"
    const val DEFAULT_EVCC_PORT = 7070
    const val DEFAULT_GOE_HOST = "192.168.224.245"

    private const val KEY_EVCC_HOST = "evcc_host"
    private const val KEY_EVCC_PORT = "evcc_port"
    private const val KEY_GOE_HOST = "goe_host"

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
}
