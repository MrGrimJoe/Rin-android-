package com.example.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.core.crypto.CryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import javax.crypto.SecretKey

/**
 * UDP discovery: broadcast JSON on port 45991 every 6s. WIRE FORMAT v2
 * (bumped from v1 -- both Android and Windows update together, see
 * DESIGN_NOTES.md "Discovery hardening"):
 *   {"magic":"RIN_BEACON","v":2,"meshTag":<rotating HKDF tag, base64>,
 *    "key":<pubkey>,"port":<tcp_port>,"ts":<millis>,
 *    "stunIp":<optional>,"stunPort":<optional>}
 *
 * v1 carried the mesh name and device name in cleartext ("mesh":<name>,
 * "name":<device>) -- readable by anyone on the LAN, member or not. v2
 * drops both: meshTag is derived from the mesh's real secret key (see
 * CryptoEngine.deriveBeaconTag) and rotates every 5 minutes, so a
 * passive observer sees an opaque blob instead of identifying metadata.
 * The device name is now only ever revealed post-handshake, inside the
 * encrypted DiscoveryResponse payload (see MeshRuntimeEngine). There is
 * no v1 fallback: v1 and v2 peers cannot discover each other, by design
 * -- this is a coordinated breaking bump, not a soft migration.
 *
 * stunIp/stunPort are kept in cleartext -- they're NAT-traversal server
 * hints, not identifying metadata about the mesh or the device, so they
 * don't need to move behind the challenge/response handshake the way
 * mesh name and device name did.
 */
class UdpBeaconEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onBeaconMatched: (publicKey: String, ip: String, port: Int, stunIp: String?, stunPort: Int?) -> Unit
) {
    private val tag = "UdpBeaconEngine"
    private val beaconPort = 45991

    // 5-minute discovery windows -- coarse enough that both sides' clocks
    // don't need to be tightly synced, fine enough that a tag isn't a
    // long-lived tracking identifier. See CryptoEngine.deriveBeaconTag's
    // doc comment. Must match Windows' kBeaconTagWindowSeconds exactly.
    private val beaconTagWindowSeconds = 300L

    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerJob: Job? = null
    private var broadcastJob: Job? = null
    private var isRunning = false
    private var datagramSocket: DatagramSocket? = null

    private var cachedMeshKey: SecretKey? = null
    private var cachedPublicKey = ""
    private var cachedTcpPort = 45990
    var cachedStunIp: String? = null
    var cachedStunPort: Int? = null

    private fun currentTimeBucket(): Long = System.currentTimeMillis() / 1000L / beaconTagWindowSeconds

    fun start(
        meshKey: SecretKey,
        publicKey: String,
        tcpPort: Int,
        stunIp: String? = null,
        stunPort: Int? = null
    ) {
        if (isRunning) return
        isRunning = true
        cachedMeshKey = meshKey
        cachedPublicKey = publicKey
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
                        if (json.optString("magic") != "RIN_BEACON") continue
                        // v1 beacons (cleartext mesh/name fields, no
                        // meshTag) are no longer understood -- see the
                        // class doc comment above. A v1 peer simply
                        // won't be discovered until it's updated too.
                        if (json.optInt("v", 1) < 2) continue

                        val rTag = json.optString("meshTag", "")
                        val rKey = json.optString("key", "")
                        val rPort = json.optInt("port", 45990)
                        val rStunIp = json.optString("stunIp", null)
                        val rStunPort = if (json.has("stunPort")) json.optInt("stunPort", 45990) else null

                        if (rTag.isEmpty() || rKey.isEmpty()) continue
                        // Disregard our own broadcasts
                        if (rKey == cachedPublicKey) continue

                        val key = cachedMeshKey ?: continue
                        // Check the tag against the current window plus
                        // one on either side, to tolerate clock drift
                        // and the fact that a beacon sent right at a
                        // window boundary may arrive just after we've
                        // rolled over.
                        val bucket = currentTimeBucket()
                        val matched = (bucket - 1..bucket + 1).any { b ->
                            rTag == CryptoEngine.deriveBeaconTag(key, b)
                        }
                        if (!matched) continue  // not our mesh (or we don't share a real secret with them)

                        onBeaconMatched(rKey, senderIp, rPort, rStunIp, rStunPort)
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
            val key = cachedMeshKey ?: return
            val broadcastPayload = JSONObject().apply {
                put("magic", "RIN_BEACON")
                put("v", 2)
                put("meshTag", CryptoEngine.deriveBeaconTag(key, currentTimeBucket()))
                put("key", cachedPublicKey)
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
