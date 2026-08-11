package com.example.chargecontrol

private val IPV4_REGEX = Regex(
    "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
)

fun isValidIpv4(value: String): Boolean = IPV4_REGEX.matches(value)

fun isValidPort(value: String): Boolean {
    val port = value.toIntOrNull() ?: return false
    return port in 1..65535
}
