package com.example.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiP2pPeer(
    val deviceName: String,
    val deviceAddress: String,
    val isGroupOwner: Boolean = false,
    val status: String = "Available"
)

/**
 * WifiDirectMeshManager:
 * Manages off-grid zero-infrastructure direct Wi-Fi P2P connections (Wi-Fi Direct).
 * Enables two Android phones in the wilderness / off-grid to form a direct high-speed P2P link
 * without an external Wi-Fi router or access point.
 */
class WifiDirectMeshManager(
    private val context: Context,
    private val onGroupFormed: (groupOwnerAddress: String, isHost: Boolean) -> Unit
) {
    private val tag = "WifiDirectMesh"

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<WifiP2pPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiP2pPeer>> = _discoveredPeers.asStateFlow()

    private val _p2pInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val p2pInfo: StateFlow<WifiP2pInfo?> = _p2pInfo.asStateFlow()

    init {
        try {
            if (manager != null) {
                channel = manager.initialize(context, Looper.getMainLooper(), null)
            }
        } catch (e: Exception) {
            Log.w(tag, "WifiP2pManager initialize failed: ${e.message}")
        }
    }

    fun start() {
        if (manager == null || channel == null) return
        registerReceiver()
        discoverPeers()
    }

    fun stop() {
        unregisterReceiver()
        try {
            manager?.stopPeerDiscovery(channel, null)
        } catch (_: Exception) {}
    }

    fun discoverPeers() {
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Wi-Fi Direct peer discovery started")
                }

                override fun onFailure(reasonCode: Int) {
                    Log.w(tag, "Wi-Fi Direct peer discovery failed: code $reasonCode")
                }
            })
        } catch (e: SecurityException) {
            Log.w(tag, "Wi-Fi Direct missing permission: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Wi-Fi Direct error: ${e.message}")
        }
    }

    fun connectToPeer(deviceAddress: String) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        try {
            manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Connecting to Wi-Fi Direct peer: $deviceAddress")
                }

                override fun onFailure(reason: Int) {
                    Log.w(tag, "Failed to connect to Wi-Fi Direct peer: code $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.w(tag, "Wi-Fi Direct connect permission error: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Wi-Fi Direct connect error: ${e.message}")
        }
    }

    fun createGroup() {
        try {
            manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Wi-Fi Direct standalone group created successfully")
                }

                override fun onFailure(reason: Int) {
                    Log.w(tag, "Wi-Fi Direct createGroup failed: code $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.w(tag, "Permission error creating group: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Error creating group: ${e.message}")
        }
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        try {
            context.registerReceiver(wifiP2pReceiver, intentFilter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(tag, "Failed to register WiFi Direct receiver: ${e.message}")
        }
    }

    private fun unregisterReceiver() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(wifiP2pReceiver)
            isReceiverRegistered = false
        } catch (_: Exception) {}
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList: WifiP2pDeviceList? ->
        val peers = peerList?.deviceList?.map { device ->
            WifiP2pPeer(
                deviceName = if (device.deviceName.isNullOrBlank()) "Direct Peer" else device.deviceName,
                deviceAddress = device.deviceAddress,
                isGroupOwner = device.isGroupOwner,
                status = when (device.status) {
                    WifiP2pDevice.AVAILABLE -> "Available"
                    WifiP2pDevice.INVITED -> "Invited"
                    WifiP2pDevice.CONNECTED -> "Connected"
                    WifiP2pDevice.FAILED -> "Failed"
                    WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                    else -> "Unknown"
                }
            )
        } ?: emptyList()
        _discoveredPeers.value = peers
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        _p2pInfo.value = info
        if (info != null && info.groupFormed) {
            val groupOwnerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            Log.i(tag, "Wi-Fi Direct Group Formed! Host: $groupOwnerIp, isOwner: ${info.isGroupOwner}")
            onGroupFormed(groupOwnerIp, info.isGroupOwner)
        }
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _isWifiP2pEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    try {
                        manager?.requestPeers(channel, peerListListener)
                    } catch (e: SecurityException) {
                        Log.w(tag, "requestPeers security exception", e)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        manager?.requestConnectionInfo(channel, connectionInfoListener)
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Self device configuration update
                }
            }
        }
    }
}
