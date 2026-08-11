package com.example.chargecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpValidationTest {

    @Test
    fun `accepts private IPv4 addresses`() {
        assertTrue(isValidIpv4("192.168.224.24"))
        assertTrue(isValidIpv4("192.168.0.1"))
        assertTrue(isValidIpv4("10.0.0.1"))
        assertTrue(isValidIpv4("10.255.255.255"))
        assertTrue(isValidIpv4("172.16.0.1"))
        assertTrue(isValidIpv4("172.31.255.255"))
    }

    @Test
    fun `rejects malformed IPv4 addresses`() {
        assertFalse(isValidIpv4("256.1.1.1"))
        assertFalse(isValidIpv4("192.168.1"))
        assertFalse(isValidIpv4("192.168.1.1.1"))
        assertFalse(isValidIpv4("abc.def.ghi.jkl"))
        assertFalse(isValidIpv4(""))
    }

    @Test
    fun `rejects well-formed but non-private IPv4 addresses`() {
        assertFalse(isValidIpv4("8.8.8.8"))
        assertFalse(isValidIpv4("1.1.1.1"))
        assertFalse(isValidIpv4("0.0.0.0"))
        assertFalse(isValidIpv4("255.255.255.255"))
        assertFalse(isValidIpv4("11.0.0.1"))
        assertFalse(isValidIpv4("172.15.255.255"))
        assertFalse(isValidIpv4("172.32.0.1"))
        assertFalse(isValidIpv4("193.168.0.1"))
    }

    @Test
    fun `accepts valid ports`() {
        assertTrue(isValidPort("1"))
        assertTrue(isValidPort("7070"))
        assertTrue(isValidPort("65535"))
    }

    @Test
    fun `rejects invalid ports`() {
        assertFalse(isValidPort("0"))
        assertFalse(isValidPort("65536"))
        assertFalse(isValidPort("-1"))
        assertFalse(isValidPort("abc"))
        assertFalse(isValidPort(""))
    }
}
