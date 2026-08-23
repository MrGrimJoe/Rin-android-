package com.example.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket

class UdpBeaconEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onBeaconReceived: (meshName: String, publicKey: String, deviceName: String, ip: String, port: Int, stunIp: String?, stunPort: Int?) -> Unit
) {
    private val tag = "UdpBeaconEngine"
    private val beaconPort = 45991
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerJob: Job? = null
    private var broadcastJob: Job? = null
    private var isRunning = false
    private var datagramSocket: DatagramSocket? = null

    private var cachedMeshName = ""
    private var cachedPublicKey = ""
    private var cachedDeviceName = ""
    private var cachedTcpPort = 45990
    var cachedStunIp: String? = null
    var cachedStunPort: Int? = null

    fun start(
        meshName: String,
        publicKey: String,
        deviceName: String,
        tcpPort: Int,
        stunIp: String? = null,
        stunPort: Int? = null
    ) {
        if (isRunning) return
        isRunning = true
        cachedMeshName = meshName
        cachedPublicKey = publicKey
        cachedDeviceName = deviceName
        cachedTcpPort = tcpPort
        cachedStunIp = stunIp
        cachedStunPort = stunPort

        multicastLock = NetworkHelper.acquireMulticastLock(context)

        // Start UDP listener on port 45991
        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                datagramSocket = DatagramSocket(beaconPort).apply {
                    broadcast = true
                    reuseAddress = true
                }
                val buffer = ByteArray(2048)
                Log.d(tag, "Listening for UDP discovery beacons on port $beaconPort")

                while (isRunning && isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    datagramSocket?.receive(packet)

                    val senderIp = packet.address?.hostAddress ?: continue
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(message)
                        if (json.optString("magic") == "RIN_BEACON") {
                            val rMesh = json.optString("mesh")
                            val rKey = json.optString("key")
                            val rName = json.optString("name")
                            val rPort = json.optInt("port", 45990)
                            val rStunIp = json.optString("stunIp", null)
                            val rStunPort = if (json.has("stunPort")) json.optInt("stunPort", 45990) else null

                            // Disregard our own broadcasts
                            if (rKey.isNotBlank() && rKey != publicKey) {
                                onBeaconReceived(rMesh, rKey, rName, senderIp, rPort, rStunIp, rStunPort)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed packets
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "UDP beacon listener stopped: ${e.message}")
            }
        }

        // Start periodic UDP broadcast beacon
        broadcastJob = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                sendBroadcastPacket()
                delay(6000) // Broadcast discovery beacon every 6 seconds
            }
        }
    }

    fun sendImmediateBroadcast() {
        scope.launch(Dispatchers.IO) {
            sendBroadcastPacket()
        }
    }

    private fun sendBroadcastPacket() {
        try {
            val broadcastPayload = JSONObject().apply {
                put("magic", "RIN_BEACON")
                put("mesh", cachedMeshName)
                put("key", cachedPublicKey)
                put("name", cachedDeviceName)
                put("port", cachedTcpPort)
                cachedStunIp?.let { put("stunIp", it) }
                cachedStunPort?.let { put("stunPort", it) }
                put("ts", System.currentTimeMillis())
            }.toString().toByteArray(Charsets.UTF_8)

            val broadcastAddr = NetworkHelper.getBroadcastAddress()
            val packet = DatagramPacket(
                broadcastPayload,
                broadcastPayload.size,
                broadcastAddr,
                beaconPort
            )
            datagramSocket?.send(packet)
        } catch (e: Exception) {
            // Ignore transient socket exceptions
        }
    }

    fun stop() {
        isRunning = false
        listenerJob?.cancel()
        broadcastJob?.cancel()
        try {
            datagramSocket?.close()
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
    }
}
