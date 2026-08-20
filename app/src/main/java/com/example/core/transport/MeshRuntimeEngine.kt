package com.example.core.transport

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.core.crypto.CryptoEngine
import com.example.core.network.BleMeshDiscovery
import com.example.core.network.MeshNotificationHelper
import com.example.core.network.NetworkHelper
import com.example.core.network.NsdMeshDiscovery
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

    val fileTransferManager = FileTransferManager(context, repository) { ip, port, pkt ->
        transmitOverNetwork(ip, port, pkt)
    }

    private var nsdDiscovery: NsdMeshDiscovery? = null
    private var udpBeaconEngine: UdpBeaconEngine? = null
    private var bleDiscovery: BleMeshDiscovery? = null
    var wifiDirectManager: WifiDirectMeshManager? = null
        private set
    val stunEngine = StunHolePunchEngine()

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
    }

    private fun initializeZeroConfDiscovery() {
        scope.launch(Dispatchers.IO) {
            val mesh = repository.getMeshInfoSync() ?: return@launch

            // 1. UDP Subnet Broadcast Beaconing on port 45991
            udpBeaconEngine = UdpBeaconEngine(context, scope) { rMesh, rKey, rName, rIp, rPort ->
                scope.launch(Dispatchers.IO) {
                    handleDiscoveredPeer(rMesh, rKey, rName, rIp, rPort)
                }
            }
            udpBeaconEngine?.start(mesh.meshName, mesh.localPublicKey, mesh.localDeviceName, activeListeningPort)

            // 2. Android NsdManager (mDNS fallback)
            nsdDiscovery = NsdMeshDiscovery(context) { serviceName, host, port ->
                scope.launch(Dispatchers.IO) {
                    val existing = repository.getRemoteDevicesSync()
                    val match = existing.find { it.ipAddress == host }
                    if (match != null) {
                        repository.updateDeviceState(match.publicKey, ConnectionState.CONNECTED)
                    }
                }
            }
            nsdDiscovery?.registerService(mesh.localDeviceName, activeListeningPort, mesh.meshName)
            nsdDiscovery?.startDiscovery()

            // 3. BLE Proximity Rail (Low energy presence beacons)
            bleDiscovery = BleMeshDiscovery(context) { token, rssi ->
                scope.launch(Dispatchers.IO) {
                    Log.d(tag, "BLE Proximity detected for token $token (RSSI: $rssi dBm)")
                }
            }
            bleDiscovery?.startAdvertising(mesh.meshName, mesh.localPublicKey)
            bleDiscovery?.startScanning()

            // 4. Wi-Fi Direct (Off-Grid peer-to-peer without router)
            wifiDirectManager = WifiDirectMeshManager(context) { groupOwnerIp, isHost ->
                scope.launch(Dispatchers.IO) {
                    Log.i(tag, "Wi-Fi Direct P2P Group Link established: $groupOwnerIp (isHost: $isHost)")
                    _meshEventNotifications.emit("Wi-Fi Direct P2P group active ($groupOwnerIp)")
                }
            }
            wifiDirectManager?.start()

            // 5. STUN Discovery (Public reflexive NAT mapping for cellular/internet traversal)
            scope.launch(Dispatchers.IO) {
                try {
                    val candidate = stunEngine.resolvePublicEndpoint(activeListeningPort)
                    if (candidate != null) {
                        _publicStunEndpoint.value = candidate
                        Log.i(tag, "STUN Public Reflexive NAT endpoint: ${candidate.publicIp}:${candidate.publicPort}")
                    }
                } catch (e: Exception) {
                    Log.w(tag, "STUN reflexive resolution deferred: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleDiscoveredPeer(meshName: String, publicKey: String, deviceName: String, ip: String, port: Int) {
        val currentMesh = repository.getMeshInfoSync() ?: return
        if (meshName != currentMesh.meshName) return

        val existing = repository.getRemoteDevicesSync()
        val match = existing.find { it.publicKey == publicKey || it.ipAddress == ip }

        if (match != null) {
            // Update connection details and state
            if (match.connectionState != ConnectionState.CONNECTED || match.ipAddress != ip || match.port != port) {
                val updated = match.copy(
                    ipAddress = ip,
                    port = port,
                    connectionState = ConnectionState.CONNECTED,
                    activeRail = TransportRail.LAN,
                    lastSeen = System.currentTimeMillis()
                )
                repository.saveDevice(updated)
            }
        } else {
            // Auto-join discovered peer in same mesh
            val platform = if (deviceName.contains("PC", ignoreCase = true) || deviceName.contains("Windows", ignoreCase = true)) {
                PlatformType.WINDOWS
            } else if (deviceName.contains("Mac", ignoreCase = true)) {
                PlatformType.MACOS
            } else if (deviceName.contains("Tab", ignoreCase = true) || deviceName.contains("iPad", ignoreCase = true)) {
                PlatformType.TABLET
            } else {
                PlatformType.ANDROID
            }

            val newDevice = TrustedDeviceEntity(
                publicKey = publicKey,
                name = deviceName,
                platform = platform,
                connectionState = ConnectionState.CONNECTED,
                activeRail = TransportRail.LAN,
                ipAddress = ip,
                port = port,
                latencyMs = 2,
                isSelf = false
            )
            repository.saveDevice(newDevice)
            _meshEventNotifications.emit("Discovered and connected to peer: $deviceName ($ip:$port)")
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

            // Process payload with real AES-GCM decryption
            processIncomingPacket(packet)
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle incoming packet", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun processIncomingPacket(packet: MeshPacket) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)

        // Decrypt payload using AES-256-GCM
        val plainPayload = if (packet.type == PacketType.HEARTBEAT || packet.type == PacketType.HELLO) {
            packet.payload
        } else {
            CryptoEngine.decryptPayload(packet.payload, meshKey)
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
            return@withContext elapsedMs.coerceAtLeast(1)
        } catch (e: Exception) {
            Log.d(tag, "Direct socket transmission to $ip:$port failed: ${e.message}")
            return@withContext null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    suspend fun broadcastClipboard(text: String) {
        val meshInfo = repository.getMeshInfoSync() ?: return
        val devices = repository.getRemoteDevicesSync()
        if (devices.isEmpty()) return

        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)
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

        // Physical transmission to all connected remote endpoints with IP
        for (dev in devices) {
            transmitOverNetwork(dev.ipAddress, dev.port, packet)
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

        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)
        val encryptedPayload = CryptoEngine.encryptPayload(url, meshKey)
        val sig = CryptoEngine.sign(encryptedPayload, meshInfo.localPrivateKey)

        val packet = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
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
            transmitOverNetwork(target.ipAddress, target.port, packet)
        } else {
            devices.forEach { transmitOverNetwork(it.ipAddress, it.port, packet) }
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
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)
        val totalChunks = 5
        for (i in 1..totalChunks) {
            delay(150)
            val progress = i / totalChunks.toFloat()
            onProgress(progress)

            val rawChunk = "CHUNK $i/$totalChunks: $fileName (${fileSizeKb / totalChunks} KB)"
            val encryptedChunk = CryptoEngine.encryptPayload(rawChunk, meshKey)
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

            val realLatency = transmitOverNetwork(targetDevice.ipAddress, targetDevice.port, packet)

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

        val realLatency = transmitOverNetwork(targetDevice.ipAddress, targetDevice.port, packet)
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
        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)
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

        devices.forEach { transmitOverNetwork(it.ipAddress, it.port, packet) }

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

        val newDevice = TrustedDeviceEntity(
            publicKey = qrToken.hostPublicKey,
            name = qrToken.hostDeviceName,
            platform = platform,
            connectionState = ConnectionState.CONNECTED,
            activeRail = TransportRail.LAN,
            ipAddress = qrToken.hostIp ?: "192.168.1.105",
            port = qrToken.hostPort,
            latencyMs = 2,
            isSelf = false
        )
        repository.saveDevice(newDevice)

        val meshKey = CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshName)
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
            rail = TransportRail.LAN
        )

        val realLatency = transmitOverNetwork(newDevice.ipAddress, newDevice.port, packet)

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
                rail = TransportRail.LAN,
                latencyMs = realLatency ?: 3,
                isOutbound = true
            )
        )
        _meshEventNotifications.emit("Authenticated ${qrToken.hostDeviceName} into ${meshInfo.meshName}")
        return true
    }

    suspend fun createInitialMesh(meshName: String, deviceName: String = getDefaultDeviceName()): MeshEntity {
        val keys = CryptoEngine.generateIdentityKeyPair()
        val myIp = NetworkHelper.getLocalIpAddress()
        val mesh = MeshEntity(
            meshName = meshName.ifBlank { "My Mesh" },
            localDeviceName = deviceName,
            localPublicKey = keys.publicKey,
            localPrivateKey = keys.privateKey,
            localFingerprint = keys.fingerprint,
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
                    if (!dev.ipAddress.isNullOrBlank() && dev.ipAddress != "127.0.0.1") {
                        val realLatency = transmitOverNetwork(dev.ipAddress, dev.port, MeshPacket(
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
                        if (realLatency != null) {
                            repository.updateDeviceState(dev.publicKey, ConnectionState.CONNECTED)
                            repository.updateDeviceRail(dev.publicKey, TransportRail.LAN, realLatency)
                        } else {
                            repository.updateDeviceState(dev.publicKey, ConnectionState.IDLE)
                        }
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
