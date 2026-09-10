package com.example.core.network

import android.util.Log
import com.example.core.logging.MeshAuditLogger
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.TransportRail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RelayClient: opt-in relay fallback tier.
 *
 * This is the Android counterpart to Rin-windows' RelayClient/rin_relay
 * (src/relay.cpp, src/relay_server.cpp on the Windows side) and speaks the
 * EXACT same control protocol, so an Android device and a Windows device
 * can reach each other through the same relay server:
 *
 *   Register (long-lived, lets others reach us):
 *     {"op":"register","key":"<base64 public key>"}\n
 *   Forward (one-shot, asks the relay to deliver one packet):
 *     {"op":"forward","target":"<base64 public key>"}\n
 *     <one MeshPacket wire-JSON line, exactly what transmitOverNetwork()
 *      already puts on the wire for a direct LAN connection>\n
 *   Relay's reply to a forward request:
 *     "OK\n"             -- delivered to a registered target
 *     "ERR <reason>\n"   -- target not registered / write failed
 *
 * Why this exists: MeshRuntimeEngine.transmitToDevice()'s existing STUN
 * fallback (TransportRail.INTERNET_P2P) opens a plain TCP connection to a
 * peer's STUN-reported public IP:port. That only works by accident (full-
 * cone NAT, or a peer with no NAT at all) -- most real NAT/CGNAT setups
 * won't let an unsolicited inbound TCP SYN through a mapping that was
 * only ever established for UDP. This relay tier is what actually gives
 * two devices on separate networks (different Wi-Fi, one on cellular) a
 * working path, the same role Apple's iCloud/APNs relay plays for
 * Continuity/AirDrop when direct P2P fails -- except self-hostable: point
 * it at any machine running the Windows rin_relay binary, including one
 * you run yourself. Nothing here changes existing LAN/STUN behavior; this
 * is purely an additional tier tried only after both of those fail.
 *
 * Trust model: same as the Windows side -- the relay is a dumb forwarder
 * that never sees plaintext payload (still AES-256-GCM ciphertext end-to-
 * end), only the same sender/target/rail/timestamp metadata already
 * visible to anyone on the LAN via the UDP beacon. Running your own relay
 * means not having to trust anyone else's server with even that much.
 */
class RelayClient(
    private val scope: CoroutineScope
) {
    private val tag = "RelayClient"

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 0
    @Volatile private var registrationSocket: Socket? = null
    private var registrationJob: Job? = null

    fun isConfigured(): Boolean = host.isNotBlank()

    fun getHost(): String = host
    fun getPort(): Int = port

    /**
     * Configures and starts the persistent registration connection that
     * lets OTHER peers reach us through this relay. A no-op (and
     * isConfigured() stays false) if [relayHost] is blank -- this is how
     * the relay tier stays fully opt-in, matching the Windows side's
     * "empty host = disabled" contract.
     */
    fun start(
        relayHost: String,
        relayPort: Int,
        ownPublicKey: String,
        onPacket: suspend (MeshPacket) -> Unit,
        verifySignature: (MeshPacket) -> Boolean
    ) {
        if (relayHost.isBlank()) return
        host = relayHost
        port = relayPort

        registrationJob = scope.launch(Dispatchers.IO) {
            // Reconnect-with-backoff: a relay is an optional convenience
            // path, not something that should spin hot or crash the app
            // if it's temporarily unreachable -- same "log and keep
            // going" posture as UdpBeaconEngine/StunHolePunchEngine
            // toward transient network failure.
            while (isActive) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(relayHost, relayPort), 5000)
                    registrationSocket = socket

                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val registerJson = JSONObject().apply {
                        put("op", "register")
                        put("key", ownPublicKey)
                    }
                    writer.println(registerJson.toString())
                    Log.i(tag, "Registered with relay $relayHost:$relayPort")
                    MeshAuditLogger.logConnectionEstablished("RELAY", relayHost, relayPort, "self")

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    // Idle-read loop: the relay pushes forwarded packet
                    // lines to this same connection whenever another peer
                    // sends US a message via forward. Each line is a
                    // complete MeshPacket -- verified exactly like a
                    // direct incoming TCP connection before dispatch.
                    // Unlike the raw-socket C++ version, closing this
                    // Socket from stop() (a different coroutine/thread)
                    // reliably unblocks readLine() with an IOException --
                    // the JVM guarantees this, unlike a raw POSIX fd.
                    while (isActive) {
                        val line = reader.readLine() ?: break  // null = clean EOF, relay closed on us
                        if (line.isBlank()) continue

                        val packet = try {
                            parsePacketLine(line)
                        } catch (e: Exception) {
                            Log.w(tag, "Malformed packet line via relay, dropping: ${e.message}")
                            continue
                        }

                        if (!verifySignature(packet)) {
                            Log.w(tag, "Relay-delivered packet signature verification FAILED for sender: ${packet.senderName}")
                            continue
                        }
                        onPacket(packet)
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(tag, "Relay registration connection lost/failed: ${e.message} -- retrying in 5s")
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                    if (registrationSocket === socket) registrationSocket = null
                }
                if (isActive) delay(5000)
            }
        }
    }

    fun stop() {
        registrationJob?.cancel()
        registrationJob = null
        try {
            registrationSocket?.close()
        } catch (_: Exception) {}
        registrationSocket = null
    }

    /**
     * One-shot: opens a short connection to the relay and asks it to
     * forward [packet] to [targetPublicKey]. Returns elapsed round-trip
     * ms on a confirmed delivery ("OK"), or null on any failure -- same
     * contract as transmitOverNetwork(), so MeshRuntimeEngine can treat
     * both tiers identically.
     */
    suspend fun sendViaRelay(targetPublicKey: String, packet: MeshPacket): Long? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val startNs = System.nanoTime()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 5000

            val writer = PrintWriter(socket.getOutputStream(), true)
            val forwardJson = JSONObject().apply {
                put("op", "forward")
                put("target", targetPublicKey)
            }
            writer.println(forwardJson.toString())
            writer.println(packetToWireJson(packet).toString())

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val reply = reader.readLine()
            if (reply == "OK") {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                return@withContext elapsedMs.coerceAtLeast(1)
            }
            Log.w(tag, "Relay declined forward to ${targetPublicKey.take(12)}...: $reply")
            return@withContext null
        } catch (e: Exception) {
            Log.d(tag, "sendViaRelay to $host:$port failed: ${e.message}")
            return@withContext null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    // Mirrors transmitOverNetwork()'s JSONObject construction exactly --
    // MUST stay in sync with that field set/order if either changes.
    private fun packetToWireJson(packet: MeshPacket): JSONObject = JSONObject().apply {
        put("v", packet.version)
        put("sess", packet.sessionId)
        put("seq", packet.sequence)
        put("type", packet.type.name)
        put("senderKey", packet.senderKey)
        put("senderName", packet.senderName)
        packet.targetKey?.let { put("targetKey", it) }
        put("payload", packet.payload)
        put("sig", packet.signature)
        put("rail", packet.rail.name)
        put("ts", packet.timestamp)
    }

    // Mirrors handleIncomingConnection()'s parsing exactly -- MUST stay
    // in sync with that if either changes.
    private fun parsePacketLine(line: String): MeshPacket {
        val json = JSONObject(line)
        return MeshPacket(
            version = json.optInt("v", 1),
            sessionId = json.optString("sess", "sess_default"),
            sequence = json.optLong("seq", 0),
            type = PacketType.valueOf(json.optString("type", PacketType.HEARTBEAT.name)),
            senderKey = json.optString("senderKey", ""),
            senderName = json.optString("senderName", "Unknown Device"),
            targetKey = json.optString("targetKey", null),
            payload = json.optString("payload", ""),
            signature = json.optString("sig", ""),
            rail = TransportRail.RELAY,
            timestamp = json.optLong("ts", System.currentTimeMillis())
        )
    }
}
