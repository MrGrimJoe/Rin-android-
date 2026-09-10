package com.example.core.transport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.core.crypto.CryptoEngine
import com.example.core.logging.AuditCategory
import com.example.core.logging.AuditLevel
import com.example.core.logging.MeshAuditLogger
import com.example.core.network.BleMeshDiscovery
import com.example.core.network.MeshNotificationHelper
import com.example.core.network.NetworkHelper
import com.example.core.network.NsdMeshDiscovery
import com.example.core.network.RelayClient
import com.example.core.network.StunCandidate
import com.example.core.network.StunHolePunchEngine
import com.example.core.network.UdpBeaconEngine
import com.example.core.network.WifiDirectMeshManager
import com.example.core.protocol.ConnectionState
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.PlatformType
import com.example.core.protocol.QrJoinToken
import com.example.core.protocol.TransportRail
import com.example.data.local.RinRepository
import com.example.data.local.entity.ClipboardItemEntity
import com.example.data.local.entity.MeshEntity
import com.example.data.local.entity.MeshPacketEntity
import com.example.data.local.entity.TrustedDeviceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class MeshRuntimeEngine(
    private val context: Context,
    private val repository: RinRepository,
    private val scope: CoroutineScope
) {
    private val tag = "MeshRuntimeEngine"
    private val sequenceNumber = AtomicLong(1)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var heartbeatJob: Job? = null
    private var clipboardListenerJob: Job? = null
    private var lastLocalClipboardText: String = ""

    var activeListeningPort: Int = 45990
        private set

    val fileTransferManager = FileTransferManager(context, repository) { targetDev, pkt ->
        transmitToDevice(targetDev, pkt)
    }

    private var nsdDiscovery: NsdMeshDiscovery? = null
    private var udpBeaconEngine: UdpBeaconEngine? = null
    private var bleDiscovery: BleMeshDiscovery? = null
    var wifiDirectManager: WifiDirectMeshManager? = null
        private set
    val stunEngine = StunHolePunchEngine()

    // Opt-in relay fallback tier (NEW). Disabled until configureRelay()
    // is called -- see RelayClient's doc comment for the full rationale
    // and the wire protocol, which is identical to the one already
    // implemented and tested on the Windows side (relay.hpp/relay_server.cpp),
    // so an Android device and a Windows device can use the same relay
    // server to reach each other.
    private val relayClient = RelayClient(scope)
    private var relayHost: String = ""
    private var relayPort: Int = 45992

    fun isRelayConfigured(): Boolean = relayClient.isConfigured()

    /**
     * Configures the relay fallback tier. Call before start() (or any
     * time -- it takes effect the next time start() runs the relay
     * registration). Passing a blank host leaves the relay tier
     * disabled, which is the default: behavior is identical to before
     * this feature existed until a host is actually configured.
     */
    fun configureRelay(host: String, port: Int = 45992) {
        relayHost = host
        relayPort = port
    }

    private val _publicStunEndpoint = MutableStateFlow<StunCandidate?>(null)
    val publicStunEndpoint: StateFlow<StunCandidate?> = _publicStunEndpoint.asStateFlow()

    private val _localIpAddress = MutableStateFlow(NetworkHelper.getLocalIpAddress())
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _incomingHandoffUrl = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val incomingHandoffUrl = _incomingHandoffUrl.asSharedFlow()

    val fileReceivedEvents = fileTransferManager.fileReceivedEvents

    private val _meshEventNotifications = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val meshEventNotifications = _meshEventNotifications.asSharedFlow()

    fun start() {
        if (isRunning) return
        isRunning = true
        MeshNotificationHelper.initialize(context)
        _localIpAddress.value = NetworkHelper.getLocalIpAddress()

        startTcpServer()
        startHeartbeatAndMeshLoop()
        startClipboardListener()
        initializeZeroConfDiscovery()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket", e)
        }
        heartbeatJob?.cancel()
        clipboardListenerJob?.cancel()
        nsdDiscovery?.stop()
        udpBeaconEngine?.stop()
        bleDiscovery?.stop()
        wifiDirectManager?.stop()
        relayClient.stop()
    }

    private fun initializeZeroConfDiscovery() {
        scope.launch(Dispatchers.IO) {
            val mesh = repository.getMeshInfoSync() ?: return@launch

            // 0. Opt-in relay fallback registration (NEW) -- no-op if
            // configureRelay() was never called. Uses the exact same
            // signature-verification and dispatch path as a direct TCP
            // connection (verify below, then processIncomingPacket) --
            // a packet arriving via relay is treated identically to one
            // arriving via LAN once it's been signature-checked.
            if (relayHost.isNotBlank()) {
                relayClient.start(
                    relayHost = relayHost,
                    relayPort = relayPort,
                    ownPublicKey = mesh.localPublicKey,
                    onPacket = { packet -> processIncomingPacket(packet) },
                    verifySignature = { packet ->
                        CryptoEngine.verify(packet.payload, packet.signature, packet.senderKey)
                    }
                )
            }

            // 1. UDP Subnet Broadcast Beaconing on port 45991
            val meshBeaconKey = CryptoEngine.deriveMeshEncryptionKey(mesh.meshSecret, mesh.meshName)
            udpBeaconEngine = UdpBeaconEngine(context, scope) { rKey, rIp, rPort, rStunIp, rStunPort ->
                scope.launch(Dispatchers.IO) {
                    onPeerDiscoveredNeedsVerification(rKey, rIp, rPort, stunIp = rStunIp, stunPort = rStunPort, rail = TransportRail.LAN)
                }
            }
            val currentStun = _publicStunEndpoint.value
            udpBeaconEngine?.start(
                meshKey = meshBeaconKey,
                publicKey = mesh.localPublicKey,
                tcpPort = activeListeningPort,
                stunIp = currentStun?.publicIp,
                stunPort = currentStun?.publicPort
            )

            // 2. Android NsdManager (DNS-SD / mDNS Zero-Config Service Discovery)
            nsdDiscovery = NsdMeshDiscovery(context) { serviceName, host, port, attributes ->
                scope.launch(Dispatchers.IO) {
                    val peerMeshName = attributes["mesh"] ?: mesh.meshName
                    val peerDeviceName = attributes["devname"] ?: serviceName.removePrefix("Rin-")
                    val peerPublicKey = attributes["pubkey"] ?: ""
                    handleDiscoveredPeer(peerMeshName, peerPublicKey, peerDeviceName, host, port, rail = TransportRail.LAN)
                }
            }
            nsdDiscovery?.registerService(mesh.localDeviceName, activeListeningPort, mesh.meshName, mesh.localPublicKey)
            nsdDiscovery?.startDiscovery()

            // 3. BLE Proximity Rail (Low energy presence beacons -> triggers active network resolution)
            bleDiscovery = BleMeshDiscovery(context) { token, rssi ->
                scope.launch(Dispatchers.IO) {
                    val meshInfo = repository.getMeshInfoSync() ?: return@launch
                    val currentMeshHash = meshInfo.meshName.hashCode().toString(16)
                    val parts = token.split(":")
                    val tokenMeshHash = parts.getOrNull(0) ?: ""

                    if (tokenMeshHash != currentMeshHash) {
                        Log.d(tag, "BLE proximity beacon ignored: mesh hash mismatch ($tokenMeshHash vs $currentMeshHash)")
                        return@launch
                    }

                    val keyPrefix = parts.getOrNull(1) ?: ""
                    val advertisedPort = parts.getOrNull(2)?.toIntOrNull() ?: activeListeningPort
                    Log.i(tag, "BLE proximity detected Rin mesh member (Prefix: $keyPrefix, Port: $advertisedPort, RSSI: $rssi dBm)")

                    // Check if peer is already in trust database
                    val existing = repository.getRemoteDevicesSync()
                    val matchedDevice = existing.find {
                        keyPrefix.isNotBlank() && (it.publicKey.startsWith(keyPrefix) || it.fingerprint.contains(keyPrefix))
                    }

                    if (matchedDevice != null && !matchedDevice.ipAddress.isNullOrBlank()) {
                        Log.d(tag, "BLE proximity matched known peer: ${matchedDevice.name} -> triggering heartbeat ping")
                        val latency = pingTargetDevice(matchedDevice)
                        _meshEventNotifications.emit("BLE proximity reactivated ${matchedDevice.name} (${latency}ms, ${rssi} dBm)")
                    } else {
                        // Unresolved peer: trigger immediate UDP beacon burst and ZeroConf refresh to resolve genuine EC public key & IP
                        udpBeaconEngine?.sendImmediateBroadcast()
                        _meshEventNotifications.emit("BLE proximity detected peer (${rssi} dBm) — resolving network endpoint...")
                    }
                }
            }
            bleDiscovery?.startAdvertising(mesh.meshName, mesh.localPublicKey, activeListeningPort)
            bleDiscovery?.startScanning()

            // 4. Wi-Fi Direct (Off-Grid peer-to-peer TCP transport)
            wifiDirectManager = WifiDirectMeshManager(context) { groupOwnerIp, isHost ->
                scope.launch(Dispatchers.IO) {
                    Log.i(tag, "Wi-Fi Direct P2P Link Established: Host IP: $groupOwnerIp (isHost: $isHost)")
                    _meshEventNotifications.emit("Wi-Fi Direct P2P group active ($groupOwnerIp)")

                    if (!isHost) {
                        // Client: Immediately probe and handshake Group Owner TCP endpoint
                        triggerWifiDirectProbe(groupOwnerIp, activeListeningPort)
                    } else {
                        // Group Owner: Probe probable client IP allocations on 192.168.49.x subnet
                        for (lastOctet in 2..8) {
                            launch(Dispatchers.IO) {
                                triggerWifiDirectProbe("192.168.49.$lastOctet", activeListeningPort)
                            }
                        }
                    }
                }
            }
            wifiDirectManager?.start()

            // 5. STUN Discovery (Public reflexive NAT mapping for cellular/internet traversal)
            scope.launch(Dispatchers.IO) {
                try {
                    val candidate = stunEngine.resolvePublicEndpoint(activeListeningPort)
                    if (candidate != null) {
                        _publicStunEndpoint.value = candidate
                        udpBeaconEngine?.cachedStunIp = candidate.publicIp
                        udpBeaconEngine?.cachedStunPort = candidate.publicPort
                        Log.i(tag, "STUN Public Reflexive NAT endpoint: ${candidate.publicIp}:${candidate.publicPort}")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "STUN reflexive resolution deferred: ${e.message}")
                }
            }
        }
    }

    suspend fun handleDiscoveredPeer(
        meshName: String,
        publicKey: String,
        deviceName: String,
        ip: String,
        port: Int,
        stunIp: String? = null,
        stunPort: Int? = null,
        rail: TransportRail = TransportRail.LAN
    ) {
        val currentMesh = repository.getMeshInfoSync() ?: return
        if (meshName != currentMesh.meshName) return

        // Cryptographic validation: verify that peer publicKey is a genuine EC public key
        if (!CryptoEngine.isValidPublicKey(publicKey)) {
            Log.w(tag, "Ignored peer $deviceName ($ip:$port) with invalid/corrupted public key.")
            return
        }

        val resolvedRail = if (ip.startsWith("192.168.49.")) TransportRail.WIFI_DIRECT else rail
        val existing = repository.getRemoteDevicesSync()
        val match = existing.find { it.publicKey == publicKey || it.ipAddress == ip }

        if (match != null) {
            // Update connection details and state
            val updated = match.copy(
                publicKey = publicKey,
                ipAddress = ip,
                port = port,
                stunIp = stunIp ?: match.stunIp,
                stunPort = stunPort ?: match.stunPort,
                connectionState = ConnectionState.CONNECTED,
                activeRail = resolvedRail,
                lastSeen = System.currentTimeMillis()
            )
            repository.saveDevice(updated)
        } else {
            // NOT auto-trusted anymore (this used to create a CONNECTED
            // TrustedDeviceEntity right here -- see DESIGN_NOTES.md
            // "Discovery hardening" for why that was a real hole: a
            // beacon/mDNS/HELLO advertisement claiming the right mesh
            // name used to be sufficient on its own). Hand off to the
            // same challenge/response verification path the UDP beacon
            // uses -- deviceName/platform get filled in from the
            // encrypted DiscoveryResponse once the peer actually proves
            // it holds the real mesh secret, not from this (possibly
            // cleartext, e.g. via mDNS TXT records or a WiFi-Direct
            // HELLO probe) advertisement.
            onPeerDiscoveredNeedsVerification(publicKey, ip, port, stunIp, stunPort, resolvedRail)
        }
    }

    // -- Discovery hardening (NEW) ---------------------------------------
    // A beacon/mDNS/HELLO match is only a HINT ("probably our mesh") --
    // it does NOT grant trust. Trust requires the peer to prove it holds
    // the real mesh_secret by successfully decrypting a random nonce we
    // send it (DISCOVERY_CHALLENGE) and echoing it back re-encrypted
    // (DISCOVERY_RESPONSE). Until that round-trip succeeds, a discovered
    // public key sits in pendingChallenges, never in the trusted-device
    // table, and gets zero access to anything.
    //
    // Tie-break: if BOTH sides see each other's beacon around the same
    // time, both would otherwise send a challenge -- harmless but
    // wasteful and racy to reason about. Only the side whose own public
    // key sorts lexicographically greater initiates; the other side
    // waits to receive a challenge instead. Must match Windows'
    // MeshEngine::on_peer_discovered tie-break exactly (same comparison,
    // same direction) since both sides need to agree on who initiates.
    private data class PendingChallenge(val nonce: String, val ip: String, val port: Int, val sentAtMs: Long)

    private val pendingChallenges = java.util.concurrent.ConcurrentHashMap<String, PendingChallenge>()

    suspend fun onPeerDiscoveredNeedsVerification(
        peerPublicKey: String,
        ip: String,
        port: Int,
        stunIp: String? = null,
        stunPort: Int? = null,
        rail: TransportRail = TransportRail.LAN
    ) {
        if (!CryptoEngine.isValidPublicKey(peerPublicKey)) return
        val meshInfo = repository.getMeshInfoSync() ?: return

        val existing = repository.getRemoteDevicesSync()
        val already = existing.find { it.publicKey == peerPublicKey }
        if (already != null) {
            // Already trusted from a prior handshake (this session or a
            // persisted one) -- just refresh reachability, no new proof
            // needed. Re-proving on every beacon would be needless churn;
            // the original handshake already established trust.
            repository.saveDevice(
                already.copy(
                    ipAddress = ip,
                    port = port,
                    stunIp = stunIp ?: already.stunIp,
                    stunPort = stunPort ?: already.stunPort,
                    connectionState = ConnectionState.CONNECTED,
                    activeRail = rail,
                    lastSeen = System.currentTimeMillis()
                )
            )
            return
        }

        // Tie-break decision + "reserve" the pending-challenge slot
        // atomically via ConcurrentHashMap.putIfAbsent -- this closes a
        // real TOCTOU race: a peer can be discovered via more than one
        // signal at once (UDP beacon racing an mDNS resolution racing a
        // Wi-Fi-Direct HELLO probe), and if "is anything pending?" and
        // "insert a pending entry" were two separate steps, two
        // concurrent callers could both see "nothing pending" and both
        // send a challenge with a different nonce, silently orphaning
        // whichever challenge's response arrives second.
        if (!(meshInfo.localPublicKey > peerPublicKey)) {
            Log.d(tag, "Beacon/discovery match from $ip:$port -- waiting for their challenge (tie-break)")
            return
        }

        val nonce = CryptoEngine.generateDiscoveryNonce()
        val alreadyPending = pendingChallenges.putIfAbsent(
            peerPublicKey, PendingChallenge(nonce, ip, port, System.currentTimeMillis())
        )
        if (alreadyPending != null) {
            // Someone else's concurrent call already reserved this slot
            // and is sending its own challenge -- don't send a second
            // one, just let that one play out.
            return
        }

        MeshAuditLogger.logDiscoveryBeaconMatched(ip, port, peerPublicKey)
        sendDiscoveryChallenge(ip, port, peerPublicKey, nonce, meshInfo.meshSecret, meshInfo.meshName)
    }

    private suspend fun sendDiscoveryChallenge(
        ip: String,
        port: Int,
        peerPublicKey: String,
        nonce: String,
        meshSecret: String,
        meshName: String
    ) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshSecret, meshName)

        val body = JSONObject().apply {
            put("nonce", nonce)
            put("ts", System.currentTimeMillis())
            // Every packet on this transport is one-shot (a fresh
            // outbound TCP connection per packet) -- there is no open
            // connection to write a reply on. The responder has to open
            // its OWN new connection back to us, which means it needs to
            // know our real listening port, not just our IP. Carrying it
            // inside the encrypted body also means an eavesdropper who
            // can't decrypt learns nothing extra beyond what the beacon
            // already exposed.
            put("challengerPort", activeListeningPort)
        }
        val encrypted = CryptoEngine.encryptPayload(body.toString(), meshKey)

        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.DISCOVERY_CHALLENGE,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = peerPublicKey,
            payload = encrypted,
            signature = CryptoEngine.sign(encrypted, meshInfo.localPrivateKey),
            rail = TransportRail.LAN,
            timestamp = System.currentTimeMillis()
        )

        transmitOverNetwork(ip, port, packet)
        MeshAuditLogger.logDiscoveryChallengeSent(ip, port, peerPublicKey)
    }

    /**
     * We are the CHALLENGED side here: someone (who matched our beacon's
     * meshTag, or is guessing) sent us a nonce encrypted with what they
     * claim is the mesh key. Decrypting it proves WE hold the real
     * secret; encrypting the echo proves it again on the way back. If we
     * can't decrypt, we simply don't reply -- no error packet, no
     * information leak about why it failed, matching the fail-closed
     * pattern used for broadcast decryption everywhere else.
     */
    suspend fun handleDiscoveryChallenge(packet: MeshPacket, remoteIp: String) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)

        val decrypted = try {
            CryptoEngine.decryptPayload(packet.payload, meshKey)
        } catch (e: Exception) {
            MeshAuditLogger.logDiscoveryRejected(remoteIp, 0, packet.senderKey,
                "could not decrypt DISCOVERY_CHALLENGE payload with our mesh key: ${e.message}")
            return
        }

        val nonce: String
        val challengerPort: Int
        try {
            val body = JSONObject(decrypted)
            nonce = body.optString("nonce", "")
            challengerPort = body.optInt("challengerPort", 45990)
        } catch (e: Exception) {
            return  // malformed decrypted body -- ignore, matches Windows
        }
        if (nonce.isEmpty()) return
        if (remoteIp.isBlank()) {
            MeshAuditLogger.logDiscoveryRejected("", 0, packet.senderKey,
                "no direct remote address to reply to (relay-delivered?)")
            return
        }

        val responseBody = JSONObject().apply {
            put("nonce", nonce)  // echo back exactly what we decrypted
            put("ts", System.currentTimeMillis())
            put("deviceName", meshInfo.localDeviceName)
        }
        val encrypted = CryptoEngine.encryptPayload(responseBody.toString(), meshKey)

        val response = MeshPacket(
            sessionId = packet.sessionId,
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.DISCOVERY_RESPONSE,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = packet.senderKey,
            payload = encrypted,
            signature = CryptoEngine.sign(encrypted, meshInfo.localPrivateKey),
            rail = TransportRail.LAN,
            timestamp = System.currentTimeMillis()
        )
        transmitOverNetwork(remoteIp, challengerPort, response)

        // We now know enough to trust THEM too, symmetrically: they
        // proved they hold the mesh secret by sending a challenge we
        // could decrypt in the first place (only a real member could
        // have derived the same mesh key to construct it). Promote
        // immediately rather than waiting on a challenge of our own --
        // the tie-break rule means we are, by construction, the side
        // that does NOT initiate here, so this is the only place our
        // side of the pair gets promoted.
        promoteToTrusted(packet.senderKey, packet.senderName, remoteIp, challengerPort)
    }

    /** We are the CHALLENGER here, checking the echo. */
    suspend fun handleDiscoveryResponse(packet: MeshPacket, remoteIp: String) {
        val pending = pendingChallenges[packet.senderKey]
        if (pending == null) {
            // No outstanding challenge for this key -- either a stale/
            // duplicate response, or an unsolicited one. Fail closed:
            // ignore rather than trust.
            MeshAuditLogger.logDiscoveryRejected(remoteIp, 0, packet.senderKey,
                "DISCOVERY_RESPONSE with no matching outstanding challenge")
            return
        }

        val meshInfo = repository.getMeshInfoSync() ?: return
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)

        val decrypted = try {
            CryptoEngine.decryptPayload(packet.payload, meshKey)
        } catch (e: Exception) {
            // Deliberately do NOT remove the pending entry here -- with
            // putIfAbsent reserving one slot per peer, there should only
            // ever be one legitimate outstanding challenge, so a
            // response that fails to decrypt is a stray/replayed/forged
            // packet, not the real answer. Removing on a bad response
            // would let an attacker DoS a legitimate in-flight handshake
            // just by lobbing garbage back at the challenger first.
            MeshAuditLogger.logDiscoveryRejected(pending.ip, pending.port, packet.senderKey,
                "could not decrypt DISCOVERY_RESPONSE payload with our mesh key: ${e.message}")
            return
        }

        val echoedNonce: String
        val deviceName: String
        try {
            val body = JSONObject(decrypted)
            echoedNonce = body.optString("nonce", "")
            deviceName = body.optString("deviceName", "")
        } catch (e: Exception) {
            return  // same reasoning as above -- don't remove on a malformed reply
        }

        if (echoedNonce.isEmpty() || echoedNonce != pending.nonce) {
            // Same reasoning again: a mismatched nonce means THIS
            // response isn't the one we're waiting for, not that the
            // real one won't still arrive -- the pending entry stays.
            MeshAuditLogger.logDiscoveryRejected(pending.ip, pending.port, packet.senderKey,
                "echoed nonce did not match what we sent")
            return
        }

        // Genuine match -- consume it now (before promoting), so a
        // duplicate/replayed copy of this same valid response can't
        // re-trigger anything once we've already acted on it.
        pendingChallenges.remove(packet.senderKey)
        val resolvedName = deviceName.ifEmpty { "Unknown Device" }
        val ip = remoteIp.ifBlank { pending.ip }
        promoteToTrusted(packet.senderKey, resolvedName, ip, pending.port)
    }

    private suspend fun promoteToTrusted(publicKey: String, deviceName: String, ip: String, port: Int) {
        val platform = if (deviceName.contains("PC", ignoreCase = true) || deviceName.contains("Windows", ignoreCase = true)) {
            PlatformType.WINDOWS
        } else if (deviceName.contains("Mac", ignoreCase = true)) {
            PlatformType.MACOS
        } else if (deviceName.contains("Tab", ignoreCase = true) || deviceName.contains("iPad", ignoreCase = true)) {
            PlatformType.TABLET
        } else {
            PlatformType.ANDROID
        }
        val isWifiDirect = ip.startsWith("192.168.49.")

        val newDevice = TrustedDeviceEntity(
            publicKey = publicKey,
            name = deviceName,
            platform = platform,
            connectionState = ConnectionState.CONNECTED,
            activeRail = if (isWifiDirect) TransportRail.WIFI_DIRECT else TransportRail.LAN,
            ipAddress = ip,
            port = port,
            latencyMs = 2,
            isSelf = false
        )
        repository.saveDevice(newDevice)
        _meshEventNotifications.emit("Discovery verified, now trusted: $deviceName ($ip:$port)")
        MeshAuditLogger.logDiscoveryVerified(deviceName, publicKey, ip, port)
    }

    fun triggerWifiDirectProbe(ip: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            val meshInfo = repository.getMeshInfoSync() ?: return@launch
            val currentStun = _publicStunEndpoint.value
            val helloPayload = JSONObject().apply {
                put("mesh", meshInfo.meshName)
                put("name", meshInfo.localDeviceName)
                put("port", activeListeningPort)
                put("rail", TransportRail.WIFI_DIRECT.name)
                currentStun?.let {
                    put("stunIp", it.publicIp)
                    put("stunPort", it.publicPort)
                }
            }.toString()

            val sig = CryptoEngine.sign(helloPayload, meshInfo.localPrivateKey)
            val packet = MeshPacket(
                sessionId = CryptoEngine.generateSessionId(),
                sequence = sequenceNumber.incrementAndGet(),
                type = PacketType.HELLO,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                payload = helloPayload,
                signature = sig,
                rail = TransportRail.WIFI_DIRECT
            )
            val latency = transmitOverNetwork(ip, port, packet)
            if (latency != null) {
                Log.i(tag, "Wi-Fi Direct handshake probe reached peer at $ip:$port (Latency: ${latency}ms)")
                _meshEventNotifications.emit("Wi-Fi Direct peer connected at $ip:$port (${latency}ms)")
            }
        }
    }

    fun triggerDiscoveryProbe(ip: String, port: Int) {
        scope.launch(Dispatchers.IO) {
            val meshInfo = repository.getMeshInfoSync() ?: return@launch
            val currentStun = _publicStunEndpoint.value
            val helloPayload = JSONObject().apply {
                put("mesh", meshInfo.meshName)
                put("name", meshInfo.localDeviceName)
                put("port", activeListeningPort)
                put("rail", TransportRail.LAN.name)
                currentStun?.let {
                    put("stunIp", it.publicIp)
                    put("stunPort", it.publicPort)
                }
            }.toString()

            val sig = CryptoEngine.sign(helloPayload, meshInfo.localPrivateKey)
            val packet = MeshPacket(
                sessionId = CryptoEngine.generateSessionId(),
                sequence = sequenceNumber.incrementAndGet(),
                type = PacketType.HELLO,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                payload = helloPayload,
                signature = sig,
                rail = TransportRail.LAN
            )
            val latency = transmitOverNetwork(ip, port, packet)
            if (latency != null) {
                _meshEventNotifications.emit("Discovery probe sent to $ip:$port (Latency: ${latency}ms)")
            } else {
                _meshEventNotifications.emit("Failed to reach peer at $ip:$port")
            }
        }
    }

    private fun startTcpServer() {
        scope.launch(Dispatchers.IO) {
            var port = 45990
            var attempts = 0
            while (attempts < 5 && serverSocket == null && isRunning) {
                try {
                    serverSocket = ServerSocket(port)
                    activeListeningPort = port
                    Log.d(tag, "Rin LAN TCP transport listening on port $activeListeningPort")
                    break
                } catch (e: Exception) {
                    Log.w(tag, "Port $port occupied, trying next port...")
                    port++
                    attempts++
                }
            }

            try {
                while (isRunning && isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch(Dispatchers.IO) {
                        handleIncomingConnection(client)
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "LAN Server loop terminated: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: ""
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val rawLine = reader.readLine() ?: return
            val json = JSONObject(rawLine)
            val packet = MeshPacket(
                version = json.optInt("v", 1),
                sessionId = json.optString("sess", "sess_default"),
                sequence = json.optLong("seq", 0),
                type = PacketType.valueOf(json.optString("type", PacketType.HEARTBEAT.name)),
                senderKey = json.optString("senderKey", ""),
                senderName = json.optString("senderName", "Unknown Device"),
                targetKey = json.optString("targetKey", null),
                payload = json.optString("payload", ""),
                signature = json.optString("sig", ""),
                rail = TransportRail.valueOf(json.optString("rail", TransportRail.LAN.name)),
                timestamp = json.optLong("ts", System.currentTimeMillis())
            )

            // Cryptographic Digital Signature Verification
            val isValid = CryptoEngine.verify(packet.payload, packet.signature, packet.senderKey)
            MeshAuditLogger.logSignatureVerification(isValid, packet.senderName, packet.senderKey, packet.sequence)
            if (!isValid) {
                Log.w(tag, "Packet signature verification FAILED for sender: ${packet.senderName} (${packet.senderKey.take(8)})")
                return
            }

            // Send immediate TCP ACK back
            val writer = PrintWriter(socket.getOutputStream(), true)
            val ackJson = JSONObject().apply {
                put("type", "ACK")
                put("seq", packet.sequence)
                put("ts", System.currentTimeMillis())
            }
            writer.println(ackJson.toString())

            // If packet is HELLO discovery packet, register / update the peer in trust database
            if (packet.type == PacketType.HELLO && clientIp.isNotBlank() && clientIp != "127.0.0.1") {                val meshInfo = repository.getMeshInfoSync()
                var peerMesh = meshInfo?.meshName ?: ""
                var peerPort = activeListeningPort
                var peerStunIp: String? = null
                var peerStunPort: Int? = null

                try {
                    val payloadJson = JSONObject(packet.payload)
                    peerMesh = payloadJson.optString("mesh", peerMesh)
                    peerPort = payloadJson.optInt("port", peerPort)
                    peerStunIp = payloadJson.optString("stunIp", null)
                    peerStunPort = if (payloadJson.has("stunPort")) payloadJson.optInt("stunPort", 45990) else null
                } catch (_: Exception) {}

                val isWifiDirect = clientIp.startsWith("192.168.49.")
                val effectiveRail = if (isWifiDirect) TransportRail.WIFI_DIRECT else packet.rail

                handleDiscoveredPeer(
                    meshName = peerMesh,
                    publicKey = packet.senderKey,
                    deviceName = packet.senderName,
                    ip = clientIp,
                    port = peerPort,
                    stunIp = peerStunIp,
                    stunPort = peerStunPort,
                    rail = effectiveRail
                )
            }

            // Discovery challenge/response bypass normal decrypt entirely
            // (their payloads ARE mesh-key ciphertext, but that decrypt is
            // attempt-and-see with its own fail-closed handling, not a
            // hard "drop the whole packet" like generic broadcast decrypt
            // failure) -- see handleDiscoveryChallenge/handleDiscoveryResponse.
            if (packet.type == PacketType.DISCOVERY_CHALLENGE) {
                handleDiscoveryChallenge(packet, clientIp)
                return
            }
            if (packet.type == PacketType.DISCOVERY_RESPONSE) {
                handleDiscoveryResponse(packet, clientIp)
                return
            }

            // Process payload with real AES-GCM decryption
            processIncomingPacket(packet)
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle incoming packet from $clientIp", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun processIncomingPacket(packet: MeshPacket) {
        // Reached via the relay fallback path (direct TCP delivery is
        // intercepted earlier, in handleIncomingConnection, where a real
        // remoteIp is available to reply on). Relay-delivered packets
        // have no direct socket to answer over, so a DISCOVERY_CHALLENGE
        // arriving this way can't be replied to -- matches Windows'
        // identical relay limitation (remote_ip empty, reply skipped,
        // rejection logged). Peers reached only via relay still get
        // trust through the QR join handshake instead.
        if (packet.type == PacketType.DISCOVERY_CHALLENGE) {
            handleDiscoveryChallenge(packet, "")
            return
        }
        if (packet.type == PacketType.DISCOVERY_RESPONSE) {
            handleDiscoveryResponse(packet, "")
            return
        }

        val meshInfo = repository.getMeshInfoSync() ?: return

        // Decrypt payload using AES-256-GCM
        val plainPayload = if (packet.type == PacketType.HEARTBEAT || packet.type == PacketType.HELLO) {
            packet.payload
        } else if (packet.targetKey == meshInfo.localPublicKey && packet.senderKey.isNotBlank()) {
            // Targeted Unicast packet: Explicitly decrypt via ECDH session key
            try {
                val peerSessionKey = CryptoEngine.derivePeerSessionKey(
                    localPrivateKeyB64 = meshInfo.localPrivateKey,
                    remotePublicKeyB64 = packet.senderKey,
                    sessionId = packet.sessionId
                )
                val decrypted = CryptoEngine.decryptPayload(packet.payload, peerSessionKey)
                MeshAuditLogger.logEcdhDecryptionSuccess(packet.sequence, packet.senderName, packet.senderKey, packet.sessionId)
                decrypted
            } catch (e: Exception) {
                MeshAuditLogger.logEcdhDecryptionFailure(packet.sequence, packet.senderName, packet.senderKey, packet.sessionId, e)
                return
            }
        } else {
            // Group Broadcast packet: Decrypt via 256-bit Mesh Master Key
            try {
                val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
                val decrypted = CryptoEngine.decryptPayload(packet.payload, meshKey)
                MeshAuditLogger.logBroadcastDecryptionSuccess(packet.sequence, packet.type.name, packet.senderName)
                decrypted
            } catch (e: Exception) {
                MeshAuditLogger.logBroadcastDecryptionFailure(packet.sequence, packet.type.name, packet.senderName, e)
                _meshEventNotifications.emit(
                    "Dropped unauthenticated/corrupted ${packet.type} packet from ${packet.senderName}"
                )
                return
            }
        }

        val payloadSummary = when (packet.type) {
            PacketType.CLIPBOARD_SYNC -> "Clipboard: \"${plainPayload.take(30)}...\""
            PacketType.BROWSER_HANDOFF -> "URL Handoff: $plainPayload"
            PacketType.FILE_START -> "Incoming File Stream ($plainPayload)"
            PacketType.FILE_CHUNK -> "File Chunk Transfer [LAN]"
            PacketType.FILE_COMPLETE -> "File Transfer Verified & Reassembled"
            PacketType.HEARTBEAT -> "Heartbeat Ping (${packet.rail.label})"
            PacketType.JOIN_REQUEST -> "QR Join Handshake Request"
            PacketType.JOIN_ACCEPT -> "QR Join Accepted into Mesh"
            PacketType.REVOCATION -> "Device Revocation Notice"
            PacketType.HELLO -> "Mesh Peer Discovery"
            PacketType.ACK -> "Packet Delivery Acknowledged"
        }

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = packet.type,
                senderKey = packet.senderKey,
                senderName = packet.senderName,
                targetKey = packet.targetKey,
                payloadSummary = payloadSummary,
                rawPayload = packet.payload, // Encrypted ciphertext
                signature = packet.signature,
                rail = packet.rail,
                latencyMs = 2,
                isOutbound = false,
                timestamp = System.currentTimeMillis()
            )
        )

        when (packet.type) {
            PacketType.CLIPBOARD_SYNC -> {
                val text = plainPayload
                if (text.isNotBlank() && text != lastLocalClipboardText) {
                    lastLocalClipboardText = text
                    updateSystemClipboard(text)
                    repository.addClipboardItem(
                        ClipboardItemEntity(
                            text = text,
                            senderName = packet.senderName,
                            rail = packet.rail,
                            isLocal = false
                        )
                    )
                    MeshNotificationHelper.showClipboardSyncNotification(context, text, packet.senderName)
                    _meshEventNotifications.emit("Clipboard synced from ${packet.senderName}")
                }
            }
            PacketType.BROWSER_HANDOFF -> {
                val url = plainPayload
                _incomingHandoffUrl.emit(url)
                MeshNotificationHelper.showUrlHandoffNotification(context, url, packet.senderName)
                _meshEventNotifications.emit("URL Handoff received from ${packet.senderName}")
            }
            PacketType.FILE_START -> {
                fileTransferManager.handleFileStart(plainPayload, packet.senderKey, packet.senderName)
                _meshEventNotifications.emit("Incoming file stream from ${packet.senderName}")
            }
            PacketType.FILE_CHUNK -> {
                fileTransferManager.handleFileChunk(plainPayload, packet.senderKey)
            }
            PacketType.FILE_COMPLETE -> {
                val file = fileTransferManager.handleFileComplete(plainPayload, packet.senderName)
                if (file != null) {
                    _meshEventNotifications.emit("File received & saved: ${file.name}")
                }
            }
            PacketType.REVOCATION -> {
                repository.removeDevice(plainPayload)
                _meshEventNotifications.emit("Device revoked from mesh: ${plainPayload.take(8)}")
            }
            PacketType.HEARTBEAT -> {
                repository.updateDeviceState(packet.senderKey, ConnectionState.CONNECTED)
            }
            else -> {}
        }
    }

    private fun updateSystemClipboard(text: String) {
        scope.launch(Dispatchers.Main) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
                val clip = ClipData.newPlainText("Rin Mesh", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.e(tag, "Error updating system clipboard", e)
            }
        }
    }

    private fun startClipboardListener() {
        clipboardListenerJob = scope.launch(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            clipboard.addPrimaryClipChangedListener {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank() && text != lastLocalClipboardText) {
                        lastLocalClipboardText = text
                        scope.launch(Dispatchers.IO) {
                            broadcastClipboard(text)
                        }
                    }
                }
            }
        }
    }

    private suspend fun transmitOverNetwork(ip: String?, port: Int, packet: MeshPacket): Long? = withContext(Dispatchers.IO) {
        if (ip.isNullOrBlank() || ip == "127.0.0.1") return@withContext null
        val startNs = System.nanoTime()
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 2500)
            socket.soTimeout = 2500

            val json = JSONObject().apply {
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

            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(json.toString())

            // Await ACK
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val ackLine = reader.readLine()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            Log.d(tag, "Socket transmission to $ip:$port succeeded in ${elapsedMs}ms (ACK: $ackLine)")
            MeshAuditLogger.logConnectionEstablished(packet.rail.name, ip, port, packet.senderName)
            return@withContext elapsedMs.coerceAtLeast(1)
        } catch (e: Exception) {
            Log.d(tag, "Direct socket transmission to $ip:$port failed: ${e.message}")
            MeshAuditLogger.logConnectionFailed(packet.rail.name, ip, port, e, packet.senderName)
            return@withContext null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun transmitToDevice(targetDevice: TrustedDeviceEntity, packet: MeshPacket): Long? = withContext(Dispatchers.IO) {
        // 1. Primary route: LAN or Wi-Fi Direct IP
        if (!targetDevice.ipAddress.isNullOrBlank() && targetDevice.ipAddress != "127.0.0.1") {
            val isWifiDirect = targetDevice.ipAddress.startsWith("192.168.49.")
            val rail = if (isWifiDirect) TransportRail.WIFI_DIRECT else TransportRail.LAN
            val latency = transmitOverNetwork(targetDevice.ipAddress, targetDevice.port, packet.copy(rail = rail))
            if (latency != null) {
                repository.updateDeviceRail(targetDevice.publicKey, rail, latency)
                repository.updateDeviceState(targetDevice.publicKey, ConnectionState.CONNECTED)
                return@withContext latency
            }
        }

        // 2. Fallback route: STUN-reflexive public endpoint (Internet P2P NAT hole punch / cross-network)
        if (!targetDevice.stunIp.isNullOrBlank()) {
            val stunPort = targetDevice.stunPort ?: 45990
            Log.i(tag, "Attempting STUN reflexive fallback transmission to ${targetDevice.name} at ${targetDevice.stunIp}:$stunPort")
            val stunPacket = packet.copy(rail = TransportRail.INTERNET_P2P)
            val stunLatency = transmitOverNetwork(targetDevice.stunIp, stunPort, stunPacket)
            if (stunLatency != null) {
                Log.i(tag, "STUN reflexive fallback succeeded for ${targetDevice.name} (Latency: ${stunLatency}ms)")
                repository.updateDeviceRail(targetDevice.publicKey, TransportRail.INTERNET_P2P, stunLatency)
                repository.updateDeviceState(targetDevice.publicKey, ConnectionState.CONNECTED)
                _meshEventNotifications.emit("Connected to ${targetDevice.name} via STUN Internet P2P (${stunLatency}ms)")
                return@withContext stunLatency
            }
        }

        // 3. Fallback route: opt-in relay server (NEW). Only tried if
        // configureRelay() was ever called AND both LAN and STUN above
        // already failed -- see RelayClient's doc comment for why this
        // tier exists (in short: the STUN tier above only works by
        // accident against most real NATs, since it's a plain TCP
        // connect to a UDP-mapped address; this is the tier that
        // actually gives two devices on separate networks a working
        // path). Never attempted, and behaves identically to before
        // this existed, unless a relay has been explicitly configured.
        if (relayClient.isConfigured()) {
            val relayPacket = packet.copy(rail = TransportRail.RELAY)
            val relayLatency = relayClient.sendViaRelay(targetDevice.publicKey, relayPacket)
            if (relayLatency != null) {
                Log.i(tag, "Relay fallback succeeded for ${targetDevice.name} (Latency: ${relayLatency}ms)")
                repository.updateDeviceRail(targetDevice.publicKey, TransportRail.RELAY, relayLatency)
                repository.updateDeviceState(targetDevice.publicKey, ConnectionState.CONNECTED)
                _meshEventNotifications.emit("Connected to ${targetDevice.name} via Relay (${relayLatency}ms)")
                return@withContext relayLatency
            }
        }

        null
    }

    suspend fun broadcastClipboard(text: String) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val devices = repository.getRemoteDevicesSync()
        if (devices.isEmpty()) return

        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
        val encryptedPayload = CryptoEngine.encryptPayload(text, meshKey)
        val sig = CryptoEngine.sign(encryptedPayload, meshInfo.localPrivateKey)

        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.CLIPBOARD_SYNC,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            payload = encryptedPayload,
            signature = sig,
            rail = TransportRail.LAN
        )

        repository.addClipboardItem(
            ClipboardItemEntity(
                text = text,
                senderName = meshInfo.localDeviceName + " (This Device)",
                rail = TransportRail.LAN,
                isLocal = true
            )
        )

        // Physical transmission to all connected remote endpoints (with STUN fallback)
        for (dev in devices) {
            transmitToDevice(dev, packet)
        }

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = packet.type,
                senderKey = packet.senderKey,
                senderName = packet.senderName,
                payloadSummary = "Clipboard Sync: \"${text.take(30)}...\"",
                rawPayload = encryptedPayload,
                signature = sig,
                rail = TransportRail.LAN,
                latencyMs = 2,
                isOutbound = true
            )
        )

        _meshEventNotifications.emit("Synced clipboard to ${devices.size} devices")
    }

    suspend fun broadcastUrlHandoff(url: String, targetDeviceKey: String? = null) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val devices = repository.getRemoteDevicesSync()
        val target = if (targetDeviceKey != null) devices.find { it.publicKey == targetDeviceKey } else null
        val sessionId = CryptoEngine.generateSessionId()

        val encryptionKey = if (targetDeviceKey != null) {
            try {
                CryptoEngine.derivePeerSessionKey(meshInfo.localPrivateKey, targetDeviceKey, sessionId)
            } catch (_: Exception) {
                CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
            }
        } else {
            CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
        }

        val encryptedPayload = CryptoEngine.encryptPayload(url, encryptionKey)
        val sig = CryptoEngine.sign(encryptedPayload, meshInfo.localPrivateKey)

        val packet = MeshPacket(
            sessionId = sessionId,
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.BROWSER_HANDOFF,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = targetDeviceKey,
            payload = encryptedPayload,
            signature = sig,
            rail = target?.activeRail ?: TransportRail.LAN
        )

        val realLatency = if (target != null) {
            transmitToDevice(target, packet)
        } else {
            devices.forEach { transmitToDevice(it, packet) }
            null
        }

        val latency = realLatency ?: (target?.latencyMs ?: 2)

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = packet.type,
                senderKey = packet.senderKey,
                senderName = packet.senderName,
                targetKey = targetDeviceKey,
                payloadSummary = "URL Handoff: $url",
                rawPayload = encryptedPayload,
                signature = sig,
                rail = packet.rail,
                latencyMs = latency,
                isOutbound = true
            )
        )

        val destinationLabel = target?.name ?: "all devices"
        _meshEventNotifications.emit("Dispatched URL Handoff to $destinationLabel ($latency ms)")
    }

    suspend fun sendRealFileUri(
        uri: Uri,
        targetDevice: TrustedDeviceEntity,
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean {
        val success = fileTransferManager.sendUri(uri, targetDevice, onProgress)
        if (success) {
            val (name, _) = fileTransferManager.getDisplayNameAndSize(uri)
            _meshEventNotifications.emit("Transferred $name to ${targetDevice.name}")
        }
        return success
    }

    suspend fun sendFileSimulation(fileName: String, fileSizeKb: Long, targetDevice: TrustedDeviceEntity, onProgress: (Float) -> Unit) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val sessionKey = try {
            CryptoEngine.derivePeerSessionKey(meshInfo.localPrivateKey, targetDevice.publicKey)
        } catch (_: Exception) {
            CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
        }
        val totalChunks = 5
        for (i in 1..totalChunks) {
            delay(150)
            val progress = i / totalChunks.toFloat()
            onProgress(progress)

            val rawChunk = "CHUNK $i/$totalChunks: $fileName (${fileSizeKb / totalChunks} KB)"
            val encryptedChunk = CryptoEngine.encryptPayload(rawChunk, sessionKey)
            val sig = CryptoEngine.sign(encryptedChunk, meshInfo.localPrivateKey)

            val packet = MeshPacket(
                sessionId = CryptoEngine.generateSessionId(),
                sequence = sequenceNumber.incrementAndGet(),
                type = PacketType.FILE_CHUNK,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                targetKey = targetDevice.publicKey,
                payload = encryptedChunk,
                signature = sig,
                rail = targetDevice.activeRail
            )

            val realLatency = transmitToDevice(targetDevice, packet)

            repository.recordPacket(
                MeshPacketEntity(
                    sessionId = packet.sessionId,
                    sequence = packet.sequence,
                    type = PacketType.FILE_CHUNK,
                    senderKey = meshInfo.localPublicKey,
                    senderName = meshInfo.localDeviceName,
                    targetKey = targetDevice.publicKey,
                    payloadSummary = "File Transfer: $fileName ($i/$totalChunks)",
                    rawPayload = encryptedChunk,
                    signature = sig,
                    rail = targetDevice.activeRail,
                    latencyMs = realLatency ?: 2,
                    isOutbound = true
                )
            )
        }
        _meshEventNotifications.emit("Transferred $fileName to ${targetDevice.name}")
    }

    suspend fun pingTargetDevice(targetDevice: TrustedDeviceEntity): Long {
        val meshInfo = repository.getMeshInfoSync() ?: return 2L
        val sig = CryptoEngine.sign("PING", meshInfo.localPrivateKey)
        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.HEARTBEAT,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = targetDevice.publicKey,
            payload = "PING",
            signature = sig,
            rail = targetDevice.activeRail
        )

        val realLatency = transmitToDevice(targetDevice, packet)
        val finalLatency = realLatency ?: 2L

        repository.updateDeviceRail(targetDevice.publicKey, targetDevice.activeRail, finalLatency)
        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = PacketType.HEARTBEAT,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                targetKey = targetDevice.publicKey,
                payloadSummary = "Ping -> ${targetDevice.name} (${targetDevice.activeRail.label})",
                rawPayload = "PING_HEARTBEAT",
                signature = sig,
                rail = targetDevice.activeRail,
                latencyMs = finalLatency,
                isOutbound = true
            )
        )
        return finalLatency
    }

    suspend fun revokeDevice(publicKey: String) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val devices = repository.getRemoteDevicesSync()
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
        val encryptedPayload = CryptoEngine.encryptPayload(publicKey, meshKey)
        val sig = CryptoEngine.sign(encryptedPayload, meshInfo.localPrivateKey)

        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.REVOCATION,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            payload = encryptedPayload,
            signature = sig,
            rail = TransportRail.LAN
        )

        devices.forEach { transmitToDevice(it, packet) }

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = packet.type,
                senderKey = packet.senderKey,
                senderName = packet.senderName,
                payloadSummary = "Revocation Notice for ${publicKey.take(8)}",
                rawPayload = encryptedPayload,
                signature = sig,
                rail = TransportRail.LAN,
                latencyMs = 1,
                isOutbound = true
            )
        )

        repository.removeDevice(publicKey)
        _meshEventNotifications.emit("Signed and broadcast revocation for device")
    }

    suspend fun completeJoinHandshake(qrToken: QrJoinToken): Boolean {
        val meshInfo = repository.getMeshInfoSync() ?: return false
        val platform = if (qrToken.hostDeviceName.contains("PC", ignoreCase = true) || qrToken.hostDeviceName.contains("Windows", ignoreCase = true)) {
            PlatformType.WINDOWS
        } else if (qrToken.hostDeviceName.contains("Mac", ignoreCase = true)) {
            PlatformType.MACOS
        } else if (qrToken.hostDeviceName.contains("Tab", ignoreCase = true) || qrToken.hostDeviceName.contains("iPad", ignoreCase = true)) {
            PlatformType.TABLET
        } else {
            PlatformType.ANDROID
        }

        val resolvedRail = if (qrToken.hostIp?.startsWith("192.168.49.") == true) {
            TransportRail.WIFI_DIRECT
        } else if (!qrToken.hostIp.isNullOrBlank()) {
            TransportRail.LAN
        } else {
            TransportRail.INTERNET_P2P
        }

        val newDevice = TrustedDeviceEntity(
            publicKey = qrToken.hostPublicKey,
            name = qrToken.hostDeviceName,
            platform = platform,
            connectionState = ConnectionState.CONNECTED,
            activeRail = resolvedRail,
            ipAddress = qrToken.hostIp,
            port = qrToken.hostPort,
            stunIp = qrToken.stunIp,
            stunPort = qrToken.stunPort,
            latencyMs = 2,
            isSelf = false
        )
        repository.saveDevice(newDevice)

        // If joining an existing mesh via QR token, adopt the meshSecret from host
        val activeSecret = qrToken.meshSecret ?: meshInfo.meshSecret
        if (!qrToken.meshSecret.isNullOrBlank() && meshInfo.meshSecret != qrToken.meshSecret) {
            repository.saveMesh(meshInfo.copy(meshSecret = qrToken.meshSecret))
        }

        val meshKey = CryptoEngine.deriveMeshEncryptionKey(activeSecret, meshInfo.meshName)
        val encryptedPayload = CryptoEngine.encryptPayload(qrToken.ephemeralToken, meshKey)
        val sig = CryptoEngine.sign(encryptedPayload, meshInfo.localPrivateKey)

        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.JOIN_ACCEPT,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = qrToken.hostPublicKey,
            payload = encryptedPayload,
            signature = sig,
            rail = resolvedRail
        )

        val realLatency = transmitToDevice(newDevice, packet)
        MeshAuditLogger.logHandshakeCompleted(qrToken.hostDeviceName, qrToken.hostPublicKey, resolvedRail.label)

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = packet.sessionId,
                sequence = packet.sequence,
                type = PacketType.JOIN_ACCEPT,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                targetKey = qrToken.hostPublicKey,
                payloadSummary = "Handshake Complete: Joined ${qrToken.hostDeviceName}",
                rawPayload = encryptedPayload,
                signature = sig,
                rail = resolvedRail,
                latencyMs = realLatency ?: 3,
                isOutbound = true
            )
        )
        _meshEventNotifications.emit("Authenticated ${qrToken.hostDeviceName} into ${meshInfo.meshName}")
        return true
    }

    suspend fun createInitialMesh(meshName: String, deviceName: String = getDefaultDeviceName()): MeshEntity {
        val keys = CryptoEngine.generateIdentityKeyPair()
        val meshSecret = CryptoEngine.generateEphemeralSecret()
        val myIp = NetworkHelper.getLocalIpAddress()
        val mesh = MeshEntity(
            meshName = meshName.ifBlank { "My Mesh" },
            localDeviceName = deviceName,
            localPublicKey = keys.publicKey,
            localPrivateKey = keys.privateKey,
            localFingerprint = keys.fingerprint,
            meshSecret = meshSecret,
            port = activeListeningPort
        )
        repository.saveMesh(mesh)

        // Self device
        val selfDevice = TrustedDeviceEntity(
            publicKey = keys.publicKey,
            name = "$deviceName (This Device)",
            platform = PlatformType.ANDROID,
            connectionState = ConnectionState.ACTIVE,
            activeRail = TransportRail.LAN,
            ipAddress = myIp,
            port = activeListeningPort,
            isSelf = true
        )
        repository.saveDevice(selfDevice)

        val sig = CryptoEngine.sign("MESH_CREATED", keys.privateKey)
        repository.recordPacket(
            MeshPacketEntity(
                sessionId = CryptoEngine.generateSessionId(),
                sequence = sequenceNumber.incrementAndGet(),
                type = PacketType.HELLO,
                senderKey = keys.publicKey,
                senderName = "$deviceName (This Device)",
                payloadSummary = "Mesh \"${mesh.meshName}\" initialized locally (Socket on $myIp:$activeListeningPort)",
                rawPayload = "INIT",
                signature = sig,
                rail = TransportRail.LAN,
                latencyMs = 1,
                isOutbound = true
            )
        )

        return mesh
    }

    private fun startHeartbeatAndMeshLoop() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                delay(15000)
                _localIpAddress.value = NetworkHelper.getLocalIpAddress()
                val devices = repository.getRemoteDevicesSync()
                val meshInfo = repository.getMeshInfoSync() ?: continue

                for (dev in devices) {
                    if (dev.isSelf) continue
                    val realLatency = transmitToDevice(dev, MeshPacket(
                        sessionId = CryptoEngine.generateSessionId(),
                        sequence = sequenceNumber.incrementAndGet(),
                        type = PacketType.HEARTBEAT,
                        senderKey = meshInfo.localPublicKey,
                        senderName = meshInfo.localDeviceName,
                        targetKey = dev.publicKey,
                        payload = "PING",
                        signature = CryptoEngine.sign("PING", meshInfo.localPrivateKey),
                        rail = dev.activeRail
                    ))
                    if (realLatency == null) {
                        repository.updateDeviceState(dev.publicKey, ConnectionState.IDLE)
                    }
                }
            }
        }
    }

    fun openUrlInBrowser(url: String) {
        try {
            val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatted)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Unable to open URL in browser", e)
        }
    }

    private fun getDefaultDeviceName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }
}
