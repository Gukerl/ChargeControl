package com.example.chargecontrol

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private class FakeKeyValueStore : KeyValueStore {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()
        private val booleans = mutableMapOf<String, Boolean>()

        override fun getString(key: String, default: String): String = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }

    @Before
    fun setUp() {
        SettingsRepository.initWithStore(FakeKeyValueStore())
    }

    @Test
    fun `returns defaults when nothing has been saved`() {
        assertEquals(SettingsRepository.DEFAULT_EVCC_HOST, SettingsRepository.evccHost)
        assertEquals(SettingsRepository.DEFAULT_EVCC_PORT, SettingsRepository.evccPort)
    }

    @Test
    fun `persists and returns saved values`() {
        SettingsRepository.evccHost = "10.0.0.5"
        SettingsRepository.evccPort = 8080

        assertEquals("10.0.0.5", SettingsRepository.evccHost)
        assertEquals(8080, SettingsRepository.evccPort)
    }

    @Test
    fun `defaults match the previously-hardcoded values`() {
        assertEquals("192.168.224.24", SettingsRepository.evccHost)
        assertEquals(7070, SettingsRepository.evccPort)
    }

    @Test
    fun `goeEnabled defaults to false`() {
        assertEquals(false, SettingsRepository.goeEnabled)
    }

    @Test
    fun `goeEnabled persists a saved value`() {
        SettingsRepository.goeEnabled = true

        assertEquals(true, SettingsRepository.goeEnabled)
    }
}
