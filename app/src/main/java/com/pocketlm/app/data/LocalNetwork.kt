package com.pocketlm.app.data

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalNetwork {
    /** This phone's addresses on the local network (Wi-Fi or hotspot), such as 192.168.1.20. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())
}
