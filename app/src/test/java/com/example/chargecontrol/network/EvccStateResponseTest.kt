package com.example.chargecontrol.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EvccStateResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses loadpoint fields from a real evcc state payload`() {
        val payload = """
            {
              "loadpoints": [
                {
                  "mode": "pv",
                  "phasesConfigured": 3,
                  "offeredCurrent": 9.5,
                  "connected": true,
                  "charging": true,
                  "minCurrent": 6,
                  "vehicleSoc": 82,
                  "title": "go-e Box",
                  "chargePower": 2185
                }
              ],
              "version": "0.313.2",
              "siteTitle": "Zuhause"
            }
        """.trimIndent()

        val result = json.decodeFromString<EvccStateResponse>(payload)

        val loadpoint = result.loadpoints.single()
        assertEquals("pv", loadpoint.mode)
        assertEquals(3, loadpoint.phasesConfigured)
        assertEquals(9.5, loadpoint.offeredCurrent, 0.0)
        assertEquals(true, loadpoint.connected)
        assertEquals(true, loadpoint.charging)
        assertEquals(6.0, loadpoint.minCurrent, 0.0)
        assertEquals(82.0, loadpoint.vehicleSoc!!, 0.0)
    }

    @Test
    fun `ignores unknown top-level and nested fields`() {
        val payload = """
            {
              "loadpoints": [
                {
                  "mode": "off",
                  "phasesConfigured": 0,
                  "offeredCurrent": 0.0,
                  "connected": false,
                  "charging": false,
                  "minCurrent": 6,
                  "vehicleSoc": 0,
                  "chargeVoltages": [227.2, 233.4, 234.9],
                  "vehicleTitle": "BYDSurf"
                }
              ],
              "battery": [],
              "pv": []
            }
        """.trimIndent()

        val result = json.decodeFromString<EvccStateResponse>(payload)

        assertEquals("off", result.loadpoints.single().mode)
        assertFalse(result.loadpoints.single().connected)
    }

    @Test
    fun `vehicleSoc defaults to null when absent or explicitly null`() {
        val absentPayload = """
            {
              "loadpoints": [
                {
                  "mode": "now",
                  "phasesConfigured": 0,
                  "offeredCurrent": 0.0,
                  "connected": false,
                  "charging": false,
                  "minCurrent": 6
                }
              ]
            }
        """.trimIndent()
        val nullPayload = """
            {
              "loadpoints": [
                {
                  "mode": "now",
                  "phasesConfigured": 0,
                  "offeredCurrent": 0.0,
                  "connected": false,
                  "charging": false,
                  "minCurrent": 6,
                  "vehicleSoc": null
                }
              ]
            }
        """.trimIndent()

        assertEquals(null, json.decodeFromString<EvccStateResponse>(absentPayload).loadpoints.single().vehicleSoc)
        assertEquals(null, json.decodeFromString<EvccStateResponse>(nullPayload).loadpoints.single().vehicleSoc)
    }
}
