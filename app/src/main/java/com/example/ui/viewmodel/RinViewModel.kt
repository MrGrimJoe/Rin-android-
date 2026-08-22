package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.crypto.CryptoEngine
import com.example.core.network.StunCandidate
import com.example.core.network.WifiP2pPeer
import com.example.core.protocol.ConnectionState
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.PlatformType
import com.example.core.protocol.QrJoinToken
import com.example.core.protocol.TransportRail
import com.example.core.transport.MeshRuntimeEngine
import com.example.core.transport.ReceivedFileRecord
import com.example.data.local.RinDatabase
import com.example.data.local.RinRepository
import com.example.data.local.entity.ClipboardItemEntity
import com.example.data.local.entity.MeshEntity
import com.example.data.local.entity.MeshPacketEntity
import com.example.data.local.entity.TrustedDeviceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed class IncomingSharePayload {
    data class Text(val content: String, val isUrl: Boolean) : IncomingSharePayload()
    data class Files(val uris: List<Uri>, val displayNames: List<String>) : IncomingSharePayload()
}

class RinViewModel(application: Application) : AndroidViewModel(application) {
    private val database = RinDatabase.getDatabase(application)
    private val repository = RinRepository(
        database.meshDao(),
        database.trustedDeviceDao(),
        database.packetDao(),
        database.clipboardDao()
    )

    val runtimeEngine = MeshRuntimeEngine(application, repository, viewModelScope)

    val meshInfo: StateFlow<MeshEntity?> = repository.meshInfo.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val trustedDevices: StateFlow<List<TrustedDeviceEntity>> = repository.trustedDevices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val recentPackets: StateFlow<List<MeshPacketEntity>> = repository.recentPackets.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val clipboardHistory: StateFlow<List<ClipboardItemEntity>> = repository.clipboardHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isAddDeviceSheetVisible = MutableStateFlow(false)
    val isAddDeviceSheetVisible: StateFlow<Boolean> = _isAddDeviceSheetVisible.asStateFlow()

    private val _isPacketInspectorVisible = MutableStateFlow(false)
    val isPacketInspectorVisible: StateFlow<Boolean> = _isPacketInspectorVisible.asStateFlow()

    private val _isClipboardHistoryVisible = MutableStateFlow(false)
    val isClipboardHistoryVisible: StateFlow<Boolean> = _isClipboardHistoryVisible.asStateFlow()

    private val _selectedDevice = MutableStateFlow<TrustedDeviceEntity?>(null)
    val selectedDevice: StateFlow<TrustedDeviceEntity?> = _selectedDevice.asStateFlow()

    private val _urlHandoffTarget = MutableStateFlow<TrustedDeviceEntity?>(null)
    val urlHandoffTarget: StateFlow<TrustedDeviceEntity?> = _urlHandoffTarget.asStateFlow()

    private val _fileTransferTarget = MutableStateFlow<TrustedDeviceEntity?>(null)
    val fileTransferTarget: StateFlow<TrustedDeviceEntity?> = _fileTransferTarget.asStateFlow()

    private val _transferProgress = MutableStateFlow<Float?>(null)
    val transferProgress: StateFlow<Float?> = _transferProgress.asStateFlow()

    private val _transferStatusLabel = MutableStateFlow<String?>(null)
    val transferStatusLabel: StateFlow<String?> = _transferStatusLabel.asStateFlow()

    private val _incomingSharePayload = MutableStateFlow<IncomingSharePayload?>(null)
    val incomingSharePayload: StateFlow<IncomingSharePayload?> = _incomingSharePayload.asStateFlow()

    private val _lastReceivedFile = MutableStateFlow<ReceivedFileRecord?>(null)
    val lastReceivedFile: StateFlow<ReceivedFileRecord?> = _lastReceivedFile.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _currentJoinToken = MutableStateFlow<QrJoinToken?>(null)
    val currentJoinToken: StateFlow<QrJoinToken?> = _currentJoinToken.asStateFlow()

    init {
        runtimeEngine.start()
        viewModelScope.launch {
            runtimeEngine.meshEventNotifications.collect { msg ->
                _userMessage.value = msg
            }
        }
        viewModelScope.launch {
            runtimeEngine.fileReceivedEvents.collect { fileRecord ->
                _lastReceivedFile.value = fileRecord
                _userMessage.value = "Received: ${fileRecord.fileName} (${formatSize(fileRecord.fileSize)})"
            }
        }
    }

    fun setIncomingShareIntent(payload: IncomingSharePayload) {
        _incomingSharePayload.value = payload
    }

    fun dismissSharePayload() {
        _incomingSharePayload.value = null
    }

    fun dismissReceivedFile() {
        _lastReceivedFile.value = null
    }

    fun openReceivedFile(record: ReceivedFileRecord) {
        val file = File(record.localFilePath)
        if (file.exists()) {
            runtimeEngine.fileTransferManager.openFileWithSystem(getApplication(), file, record.mimeType)
        } else {
            _userMessage.value = "File not found on storage"
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun createMesh(name: String) {
        viewModelScope.launch {
            val mesh = runtimeEngine.createInitialMesh(name)
            refreshJoinToken(mesh)
        }
    }

    fun openAddDevice() {
        val mesh = meshInfo.value
        if (mesh != null) {
            refreshJoinToken(mesh)
        }
        _isAddDeviceSheetVisible.value = true
    }

    fun closeAddDevice() {
        _isAddDeviceSheetVisible.value = false
    }

    fun setPacketInspectorVisible(visible: Boolean) {
        _isPacketInspectorVisible.value = visible
    }

    fun setClipboardHistoryVisible(visible: Boolean) {
        _isClipboardHistoryVisible.value = visible
    }

    fun openUrlHandoff(device: TrustedDeviceEntity) {
        _urlHandoffTarget.value = device
    }

    fun closeUrlHandoff() {
        _urlHandoffTarget.value = null
    }

    fun openFileTransfer(device: TrustedDeviceEntity) {
        _fileTransferTarget.value = device
    }

    fun closeFileTransfer() {
        _fileTransferTarget.value = null
        _transferProgress.value = null
        _transferStatusLabel.value = null
    }

    val localIpAddress: StateFlow<String> = runtimeEngine.localIpAddress
    val publicStunEndpoint: StateFlow<StunCandidate?> = runtimeEngine.publicStunEndpoint

    fun triggerWifiDirectDiscovery() {
        runtimeEngine.wifiDirectManager?.discoverPeers()
        _userMessage.value = "Scanning for off-grid Wi-Fi Direct peers..."
    }

    fun createOffGridWifiDirectGroup() {
        runtimeEngine.wifiDirectManager?.createGroup()
        _userMessage.value = "Creating Wi-Fi Direct autonomous group..."
    }

    fun refreshJoinToken(mesh: MeshEntity? = meshInfo.value) {
        if (mesh == null) return
        val ephemeralToken = CryptoEngine.generateEphemeralToken()
        val currentIp = runtimeEngine.localIpAddress.value
        val secret = mesh.meshSecret.ifBlank {
            CryptoEngine.deriveMeshSecretFromKey(mesh.localPrivateKey, mesh.meshName)
        }
        _currentJoinToken.value = QrJoinToken(
            meshName = mesh.meshName,
            hostPublicKey = mesh.localPublicKey,
            hostDeviceName = mesh.localDeviceName,
            ephemeralToken = ephemeralToken,
            meshSecret = secret,
            hostPort = runtimeEngine.activeListeningPort,
            hostIp = currentIp
        )
    }

    fun joinMeshViaScannedToken(token: QrJoinToken) {
        viewModelScope.launch {
            if (meshInfo.value == null) {
                // First launch: join an existing mesh
                val keys = CryptoEngine.generateIdentityKeyPair()
                val mesh = MeshEntity(
                    meshName = token.meshName,
                    localDeviceName = "Android Device",
                    localPublicKey = keys.publicKey,
                    localPrivateKey = keys.privateKey,
                    localFingerprint = keys.fingerprint
                )
                repository.saveMesh(mesh)
                repository.saveDevice(
                    TrustedDeviceEntity(
                        publicKey = keys.publicKey,
                        name = "Android Device (This Device)",
                        platform = PlatformType.ANDROID,
                        connectionState = ConnectionState.ACTIVE,
                        activeRail = TransportRail.LAN,
                        isSelf = true
                    )
                )
            }
            runtimeEngine.completeJoinHandshake(token)
            closeAddDevice()
        }
    }

    fun probePeerEndpoint(ip: String, port: Int) {
        runtimeEngine.triggerDiscoveryProbe(ip, port)
        closeAddDevice()
    }

    fun addDemoDevice(platform: PlatformType) {
        viewModelScope.launch {
            val key = CryptoEngine.generateIdentityKeyPair()
            val name = when (platform) {
                PlatformType.WINDOWS -> "Dell XPS 15 (Windows)"
                PlatformType.MACOS -> "MacBook Pro M3"
                PlatformType.TABLET -> "Pixel Tablet"
                PlatformType.LINUX -> "Ubuntu Workstation"
                PlatformType.ANDROID -> "Pixel 7a"
            }
            val newDevice = TrustedDeviceEntity(
                publicKey = key.publicKey,
                name = name,
                platform = platform,
                connectionState = ConnectionState.CONNECTED,
                activeRail = if (platform == PlatformType.WINDOWS || platform == PlatformType.MACOS) TransportRail.LAN else TransportRail.BLE,
                ipAddress = "192.168.1." + (100..200).random(),
                latencyMs = (1..5).random().toLong(),
                batteryLevel = (60..99).random(),
                isSelf = false
            )
            repository.saveDevice(newDevice)

            val mesh = repository.getMeshInfoSync()
            if (mesh != null) {
                val sig = CryptoEngine.sign("JOINED:$name", mesh.localPrivateKey)
                repository.recordPacket(
                    MeshPacketEntity(
                        sessionId = CryptoEngine.generateSessionId(),
                        sequence = (10..999).random().toLong(),
                        type = PacketType.JOIN_ACCEPT,
                        senderKey = mesh.localPublicKey,
                        senderName = mesh.localDeviceName,
                        targetKey = key.publicKey,
                        payloadSummary = "Device \"$name\" authenticated & paired",
                        rawPayload = "JOIN_ACCEPT",
                        signature = sig,
                        rail = newDevice.activeRail,
                        latencyMs = 2,
                        isOutbound = true
                    )
                )
            }
            _userMessage.value = "Added $name to mesh"
            closeAddDevice()
        }
    }

    fun removeDevice(publicKey: String) {
        viewModelScope.launch {
            runtimeEngine.revokeDevice(publicKey)
        }
    }

    fun syncClipboardNow(text: String) {
        viewModelScope.launch {
            runtimeEngine.broadcastClipboard(text)
        }
    }

    fun sendUrlHandoff(url: String) {
        viewModelScope.launch {
            val target = _urlHandoffTarget.value
            runtimeEngine.broadcastUrlHandoff(url, target?.publicKey)
            closeUrlHandoff()
        }
    }

    fun sendRealFile(uri: Uri, targetDevice: TrustedDeviceEntity? = null) {
        val target = targetDevice ?: _fileTransferTarget.value ?: return
        val (fileName, totalSize) = runtimeEngine.fileTransferManager.getDisplayNameAndSize(uri)
        _transferStatusLabel.value = "Transmitting $fileName (${formatSize(totalSize)})..."
        _transferProgress.value = 0.01f

        viewModelScope.launch {
            runtimeEngine.sendRealFileUri(uri, target) { ratio, sent, total ->
                _transferProgress.value = ratio
                _transferStatusLabel.value = "Streaming $fileName • ${(ratio * 100).toInt()}% (${formatSize(sent)}/${formatSize(total)})"
            }
            _transferProgress.value = null
            _transferStatusLabel.value = null
            closeFileTransfer()
        }
    }

    fun sendSharedPayloadToDevice(targetDevice: TrustedDeviceEntity) {
        val payload = _incomingSharePayload.value ?: return
        viewModelScope.launch {
            when (payload) {
                is IncomingSharePayload.Text -> {
                    if (payload.isUrl) {
                        runtimeEngine.broadcastUrlHandoff(payload.content, targetDevice.publicKey)
                    } else {
                        runtimeEngine.broadcastClipboard(payload.content)
                    }
                }
                is IncomingSharePayload.Files -> {
                    for (uri in payload.uris) {
                        runtimeEngine.sendRealFileUri(uri, targetDevice) { ratio, _, _ ->
                            _transferProgress.value = ratio
                        }
                    }
                    _transferProgress.value = null
                }
            }
            _incomingSharePayload.value = null
            _userMessage.value = "Shared successfully to ${targetDevice.name}"
        }
    }

    fun sendFile(fileName: String, fileSizeKb: Long) {
        val target = _fileTransferTarget.value ?: return
        viewModelScope.launch {
            _transferStatusLabel.value = "Transmitting $fileName..."
            runtimeEngine.sendFileSimulation(fileName, fileSizeKb, target) { progress ->
                _transferProgress.value = progress
            }
            _transferProgress.value = null
            _transferStatusLabel.value = null
            closeFileTransfer()
        }
    }

    fun sendPingToDevice(device: TrustedDeviceEntity) {
        viewModelScope.launch {
            val latency = runtimeEngine.pingTargetDevice(device)
            _userMessage.value = "Ping delivered to ${device.name} ($latency ms)"
        }
    }

    fun clearPackets() {
        viewModelScope.launch {
            repository.clearPackets()
        }
    }

    fun clearClipboardHistory() {
        viewModelScope.launch {
            repository.clearClipboardHistory()
        }
    }

    fun resetMesh() {
        viewModelScope.launch {
            repository.clearMesh()
            _userMessage.value = "Mesh reset. Identity keys cleared."
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format("%.1f MB", mb)
        } else if (kb >= 1.0) {
            String.format("%.1f KB", kb)
        } else {
            "$bytes B"
        }
    }

    override fun onCleared() {
        super.onCleared()
        runtimeEngine.stop()
    }
}
