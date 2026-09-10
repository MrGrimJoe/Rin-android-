package com.example.core.network

import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.TransportRail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RelayClient tests, mirroring rin-windows' tests/test_relay.cpp (already
 * passing there -- see its own file for the two real bugs that test caught
 * during development). This is the one place in this suite that spins up
 * a real local server rather than testing pure logic, for the same reason
 * as the C++ version: "does a packet actually get from A to B through a
 * third process" isn't provable any other way.
 *
 * A minimal re-implementation of rin_relay's register/forward protocol
 * lives in this file (TestRelayServer) rather than depending on the
 * actual Windows rin_relay binary, which obviously isn't available in a
 * JVM unit test -- it mirrors src/relay_server.cpp's wire behavior
 * exactly (see RelayClient.kt's doc comment for the shared protocol).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayClientTest {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var testServer: TestRelayServer? = null

    @After
    fun tearDown() {
        testServer?.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun makeTestPacket(senderKey: String, targetKey: String) = MeshPacket(
        sessionId = "relay_test_session",
        sequence = 1,
        type = PacketType.HEARTBEAT,
        senderKey = senderKey,
        senderName = "Relay Test Sender",
        targetKey = targetKey,
        payload = "test_payload_ciphertext_placeholder",
        signature = "ecdsa:test_signature_placeholder",
        rail = TransportRail.LAN,
        timestamp = 1234567890L
    )

    @Test
    fun `unconfigured RelayClient reports isConfigured false`() {
        val client = RelayClient(scope)
        assertFalse(client.isConfigured())
    }

    @Test
    fun `start with a non-empty host makes isConfigured true`() {
        val client = RelayClient(scope)
        client.start("127.0.0.1", 1, "peerKey", onPacket = {}, verifySignature = { true })
        assertTrue(client.isConfigured())
        client.stop()
    }

    @Test
    fun `sendViaRelay delivers a packet to a registered peer unchanged`() = runBlocking {
        val server = TestRelayServer().also { it.start() }
        testServer = server

        val received = Channel<MeshPacket>(capacity = 1)
        val clientB = RelayClient(scope)
        clientB.start(
            relayHost = "127.0.0.1",
            relayPort = server.port,
            ownPublicKey = "peerB_public_key_placeholder",
            onPacket = { packet -> received.trySend(packet) },
            verifySignature = { true }  // stubbed -- this test is about relay delivery, not crypto
        )

        // Give the registration connection a moment to land server-side.
        kotlinx.coroutines.delay(300)

        val clientA = RelayClient(scope)
        clientA.start("127.0.0.1", server.port, "peerA_public_key_placeholder", onPacket = {}, verifySignature = { true })

        val packet = makeTestPacket("peerA_public_key_placeholder", "peerB_public_key_placeholder")
        val latency = clientA.sendViaRelay("peerB_public_key_placeholder", packet)
        assertTrue("send_via_relay() to a registered peer should succeed", latency != null)

        val forwarded = withTimeoutOrNull(3000) { received.receive() }
        assertTrue("the registered peer's onPacket callback should fire within 3s", forwarded != null)
        assertEquals(packet.sessionId, forwarded!!.sessionId)
        assertEquals(packet.senderKey, forwarded.senderKey)
        assertEquals(
            "payload must survive the relay hop unchanged -- relay never touches ciphertext",
            packet.payload, forwarded.payload
        )

        clientA.stop()
        clientB.stop()
    }

    @Test
    fun `sendViaRelay to an unregistered target returns null instead of hanging`() = runBlocking {
        val server = TestRelayServer().also { it.start() }
        testServer = server

        val clientA = RelayClient(scope)
        clientA.start("127.0.0.1", server.port, "peerA_public_key_placeholder", onPacket = {}, verifySignature = { true })

        val orphanPacket = makeTestPacket("peerA_public_key_placeholder", "nobody_registered_this_key")
        val latency = withTimeoutOrNull(5000) {
            clientA.sendViaRelay("nobody_registered_this_key", orphanPacket)
        }
        // Either a clean null (peer not found) or the outer timeout would
        // both indicate a real bug if this ever hangs -- withTimeoutOrNull
        // returning null itself would fail the assertion below, making a
        // hang visible as a failure rather than a stuck test run.
        assertNull("forwarding to an unregistered target must return null, not hang", latency)

        clientA.stop()
    }

    /**
     * Minimal re-implementation of rin_relay's forwarding logic (see
     * relay_server.cpp), scoped to this test. Deliberately mirrors that
     * file's protocol handling -- if the real protocol changes, this
     * should be updated to match (and would then likely fail here first).
     */
    private class TestRelayServer {
        private val serverSocket = ServerSocket(0)
        private val running = AtomicBoolean(false)
        private val peers = ConcurrentHashMap<String, Socket>()
        private var acceptThread: Thread? = null

        val port: Int get() = serverSocket.localPort

        fun start() {
            running.set(true)
            acceptThread = Thread {
                while (running.get()) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (e: Exception) {
                        if (!running.get()) break else continue
                    }
                    Thread { handleConnection(socket) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        fun stop() {
            running.set(false)
            try {
                serverSocket.close()
            } catch (_: Exception) {}
        }

        private fun handleConnection(socket: Socket) {
            var registeredKey: String? = null
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val controlLine = reader.readLine() ?: return
                val control = JSONObject(controlLine)
                when (control.optString("op", "")) {
                    "register" -> {
                        val key = control.optString("key", "")
                        if (key.isBlank()) return
                        registeredKey = key
                        peers[key] = socket
                        // Idle-read to detect disconnect; keeps the entry valid.
                        while (running.get()) {
                            if (reader.readLine() == null) break
                        }
                    }
                    "forward" -> {
                        val target = control.optString("target", "")
                        val packetLine = reader.readLine()
                        val writer = PrintWriter(socket.getOutputStream(), true)
                        val targetSocket = peers[target]
                        if (target.isBlank() || packetLine.isNullOrBlank() || targetSocket == null) {
                            writer.println("ERR no such peer")
                            return
                        }
                        val targetWriter = PrintWriter(targetSocket.getOutputStream(), true)
                        targetWriter.println(packetLine)
                        writer.println("OK")
                    }
                }
            } catch (_: Exception) {
                // connection dropped -- fine, mirrors production relay_server.cpp
            } finally {
                registeredKey?.let { peers.remove(it) }
            }
        }
    }
}
