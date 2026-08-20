package com.example.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkHelper {
    private const val TAG = "NetworkHelper"

    /**
     * Resolves the primary local IPv4 address (e.g. 192.168.x.x or 10.x.x.x)
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize wlan0 or non-loopback IPv4 interfaces
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (!hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return "127.0.0.1"
    }

    /**
     * Computes the subnet broadcast address (e.g. 192.168.1.255)
     */
    fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (interfaceAddress in intf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && interfaceAddress.address is Inet4Address) {
                        return broadcast
                    }
                }
            }
            return InetAddress.getByName("255.255.255.255")
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating broadcast address", e)
            return InetAddress.getByName("255.255.255.255")
        }
    }

    /**
     * Creates and acquires a Wi-Fi MulticastLock on Android to allow receiving UDP multicast/broadcast
     */
    fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("rin_mesh_multicast")
            lock?.setReferenceCounted(true)
            lock?.acquire()
            lock
        } catch (e: Exception) {
            Log.e(TAG, "Could not acquire MulticastLock", e)
            null
        }
    }
}
