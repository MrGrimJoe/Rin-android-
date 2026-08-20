package com.example.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

data class StunCandidate(
    val publicIp: String,
    val publicPort: Int,
    val rttMs: Long,
    val stunServer: String
)

/**
 * StunHolePunchEngine:
 * RFC 5389 compliant STUN client for Internet Traversal across NAT/Firewalls / separate cellular networks.
 * Discovers public reflexive IP and UDP port mapping, enabling direct peer-to-peer UDP hole-punching
 * across cellular data and independent internet networks without third-party cloud data relays.
 */
class StunHolePunchEngine {
    private val tag = "StunHolePunch"

    companion object {
        // Standard public zero-logging STUN servers (RFC 5389)
        val DEFAULT_STUN_SERVERS = listOf(
            "stun.l.google.com" to 19302,
            "stun1.l.google.com" to 19302,
            "stun2.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478
        )

        private const val STUN_BINDING_REQUEST_TYPE: Short = 0x0001
        private const val STUN_MAGIC_COOKIE = 0x2112A442
        private const val ATTR_XOR_MAPPED_ADDRESS: Short = 0x0020
        private const val ATTR_MAPPED_ADDRESS: Short = 0x0001
    }

    /**
     * Resolves public reflexive IP & NAT mapping for local port.
     */
    suspend fun resolvePublicEndpoint(localPort: Int = 45991): StunCandidate? = withContext(Dispatchers.IO) {
        for ((serverHost, serverPort) in DEFAULT_STUN_SERVERS) {
            try {
                val candidate = queryStunServer(serverHost, serverPort, localPort)
                if (candidate != null) {
                    Log.i(tag, "Discovered Reflexive Public Endpoint: ${candidate.publicIp}:${candidate.publicPort} (via $serverHost, ${candidate.rttMs}ms)")
                    return@withContext candidate
                }
            } catch (e: Exception) {
                Log.w(tag, "STUN resolution failed with $serverHost: ${e.message}")
            }
        }
        null
    }

    private fun queryStunServer(host: String, serverPort: Int, localPort: Int): StunCandidate? {
        val startNs = System.nanoTime()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localPort))
            socket.soTimeout = 2500

            val serverAddr = InetAddress.getByName(host)

            // Build STUN 20-byte Header Binding Request (RFC 5389)
            val buffer = ByteBuffer.allocate(20)
            buffer.putShort(STUN_BINDING_REQUEST_TYPE) // 0x0001
            buffer.putShort(0x0000.toShort())          // Message length: 0 attrs
            buffer.putInt(STUN_MAGIC_COOKIE)           // 0x2112A442
            // 12 bytes random transaction ID
            val transactionId = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }
            buffer.put(transactionId)

            val requestData = buffer.array()
            val sendPacket = DatagramPacket(requestData, requestData.size, serverAddr, serverPort)
            socket.send(sendPacket)

            // Receive response
            val recvBuffer = ByteArray(512)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            // Parse response
            val resp = ByteBuffer.wrap(recvBuffer, 0, recvPacket.length)
            if (resp.remaining() < 20) return null

            val msgType = resp.short
            val msgLen = resp.short.toInt() and 0xFFFF
            val magic = resp.int

            if (magic != STUN_MAGIC_COOKIE) return null

            // Skip transaction ID
            resp.position(20)

            // Parse attributes
            while (resp.remaining() >= 4) {
                val attrType = resp.short
                val attrLength = resp.short.toInt() and 0xFFFF
                if (resp.remaining() < attrLength) break

                if (attrType == ATTR_XOR_MAPPED_ADDRESS) {
                    val reserved = resp.get()
                    val family = resp.get().toInt()
                    val xorPort = resp.short.toInt() and 0xFFFF
                    val actualPort = xorPort xor (STUN_MAGIC_COOKIE ushr 16)

                    if (family == 0x01) { // IPv4
                        val xorIpInt = resp.int
                        val actualIpInt = xorIpInt xor STUN_MAGIC_COOKIE
                        val ipBytes = byteArrayOf(
                            ((actualIpInt ushr 24) and 0xFF).toByte(),
                            ((actualIpInt ushr 16) and 0xFF).toByte(),
                            ((actualIpInt ushr 8) and 0xFF).toByte(),
                            (actualIpInt and 0xFF).toByte()
                        )
                        val publicIp = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                        return StunCandidate(publicIp, actualPort, elapsedMs, host)
                    }
                } else if (attrType == ATTR_MAPPED_ADDRESS) {
                    val reserved = resp.get()
                    val family = resp.get().toInt()
                    val port = resp.short.toInt() and 0xFFFF
                    if (family == 0x01) {
                        val ipBytes = ByteArray(4)
                        resp.get(ipBytes)
                        val publicIp = InetAddress.getByAddress(ipBytes).hostAddress ?: return null
                        return StunCandidate(publicIp, port, elapsedMs, host)
                    }
                } else {
                    // Skip unknown attribute
                    val currentPos = resp.position()
                    val paddedLength = (attrLength + 3) and 3.inv()
                    resp.position(currentPos + paddedLength)
                }
            }
        }
        return null
    }

    /**
     * Performs direct UDP bidirectional hole punching handshake between two endpoints.
     */
    suspend fun probePeerHolePunch(
        targetPublicIp: String,
        targetPublicPort: Int,
        localPort: Int,
        authToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(localPort))
                socket.soTimeout = 1500

                val targetAddr = InetAddress.getByName(targetPublicIp)
                val payload = "RIN_HOLE_PUNCH:$authToken".toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(payload, payload.size, targetAddr, targetPublicPort)

                // Send a burst of 5 punch packets to open NAT pinhole
                repeat(5) {
                    socket.send(packet)
                    kotlinx.coroutines.delay(80)
                }

                // Check for incoming punch response
                val recvBuf = ByteArray(256)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(recvPacket)
                    val reply = String(recvBuf, 0, recvPacket.length, Charsets.UTF_8)
                    if (reply.startsWith("RIN_HOLE_PUNCH") || reply.startsWith("RIN_DISCOVER")) {
                        Log.i(tag, "Direct NAT hole punch confirmed with $targetPublicIp:$targetPublicPort!")
                        return@withContext true
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(tag, "Hole punch probe error: ${e.message}")
        }
        false
    }
}
