package com.example.chargecontrol

private val IPV4_REGEX = Regex(
    "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
)

fun isValidIpv4(value: String): Boolean {
    if (!IPV4_REGEX.matches(value)) return false
    val (a, b, _, _) = value.split(".").map { it.toInt() }
    return isPrivateIpv4Range(a, b)
}

private fun isPrivateIpv4Range(firstOctet: Int, secondOctet: Int): Boolean = when {
    firstOctet == 10 -> true
    firstOctet == 172 && secondOctet in 16..31 -> true
    firstOctet == 192 && secondOctet == 168 -> true
    else -> false
}

fun isValidPort(value: String): Boolean {
    val port = value.toIntOrNull() ?: return false
    return port in 1..65535
}
