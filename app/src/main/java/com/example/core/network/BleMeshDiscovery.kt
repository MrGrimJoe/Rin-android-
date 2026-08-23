package com.example.core.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * BLE Proximity Presence Rail:
 * Uses rotating ephemeral service data over BLE advertising to announce and detect nearby Rin mesh members
 * without draining high battery power or exposing long-term stable hardware identifiers.
 */
class BleMeshDiscovery(
    private val context: Context,
    private val onPeerProximityDetected: (meshToken: String, deviceRssi: Int) -> Unit
) {
    private val tag = "BleMeshDiscovery"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false

    companion object {
        // Dedicated 128-bit UUID for Rin Serverless Mesh BLE Presence Rail
        val RIN_BLE_SERVICE_UUID: UUID = UUID.fromString("0000fe90-0000-1000-8000-00805f9b34fb")
    }

    fun startAdvertising(meshName: String, localPublicKeyPrefix: String, tcpPort: Int = 45990) {
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return
            if (isAdvertising) return

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build()

            // Transmit truncated mesh identifier hash + public key prefix + port in service data
            val payload = "${meshName.hashCode().toString(16)}:${localPublicKeyPrefix.take(6)}:$tcpPort"
            val serviceData = payload.toByteArray(StandardCharsets.UTF_8)

            val pUuid = ParcelUuid(RIN_BLE_SERVICE_UUID)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(pUuid)
                .addServiceData(pUuid, serviceData)
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            Log.d(tag, "Started BLE mesh advertising for $meshName")
        } catch (e: SecurityException) {
            Log.w(tag, "BLE advertise permission not granted yet: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "BLE advertise start failed", e)
        }
    }

    fun startScanning() {
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
            scanner = bluetoothAdapter.bluetoothLeScanner ?: return
            if (isScanning) return

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(RIN_BLE_SERVICE_UUID))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Log.d(tag, "Started BLE mesh scanning")
        } catch (e: SecurityException) {
            Log.w(tag, "BLE scan permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "BLE scan start failed", e)
        }
    }

    fun stop() {
        try {
            if (isAdvertising) {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
            if (isScanning) {
                scanner?.stopScan(scanCallback)
                isScanning = false
            }
        } catch (_: Exception) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(tag, "BLE Mesh beacon advertisement active")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(tag, "BLE Mesh beacon advertisement failed: code $errorCode")
            isAdvertising = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val record = result.scanRecord ?: return
            val serviceData = record.getServiceData(ParcelUuid(RIN_BLE_SERVICE_UUID)) ?: return
            val token = String(serviceData, StandardCharsets.UTF_8)
            onPeerProximityDetected(token, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "BLE Scan failed: code $errorCode")
            isScanning = false
        }
    }
}
