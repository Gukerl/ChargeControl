package com.example.chargecontrol

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private class FakeKeyValueStore : KeyValueStore {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getString(key: String, default: String): String = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
    }

    @Before
    fun setUp() {
        SettingsRepository.initWithStore(FakeKeyValueStore())
    }

    @Test
    fun `returns defaults when nothing has been saved`() {
        assertEquals(SettingsRepository.DEFAULT_EVCC_HOST, SettingsRepository.evccHost)
        assertEquals(SettingsRepository.DEFAULT_EVCC_PORT, SettingsRepository.evccPort)
        assertEquals(SettingsRepository.DEFAULT_GOE_HOST, SettingsRepository.goeHost)
    }

    @Test
    fun `persists and returns saved values`() {
        SettingsRepository.evccHost = "10.0.0.5"
        SettingsRepository.evccPort = 8080
        SettingsRepository.goeHost = "10.0.0.6"

        assertEquals("10.0.0.5", SettingsRepository.evccHost)
        assertEquals(8080, SettingsRepository.evccPort)
        assertEquals("10.0.0.6", SettingsRepository.goeHost)
    }

    @Test
    fun `defaults match the previously-hardcoded values`() {
        assertEquals("192.168.224.24", SettingsRepository.evccHost)
        assertEquals(7070, SettingsRepository.evccPort)
        assertEquals("192.168.224.245", SettingsRepository.goeHost)
    }
}
