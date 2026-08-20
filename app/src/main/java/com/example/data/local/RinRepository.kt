package com.example.data.local

import com.example.core.protocol.ConnectionState
import com.example.core.protocol.TransportRail
import com.example.data.local.dao.ClipboardDao
import com.example.data.local.dao.MeshDao
import com.example.data.local.dao.PacketDao
import com.example.data.local.dao.TrustedDeviceDao
import com.example.data.local.entity.ClipboardItemEntity
import com.example.data.local.entity.MeshEntity
import com.example.data.local.entity.MeshPacketEntity
import com.example.data.local.entity.TrustedDeviceEntity
import kotlinx.coroutines.flow.Flow

class RinRepository(
    private val meshDao: MeshDao,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val packetDao: PacketDao,
    private val clipboardDao: ClipboardDao
) {
    val meshInfo: Flow<MeshEntity?> = meshDao.getMeshInfo()
    val trustedDevices: Flow<List<TrustedDeviceEntity>> = trustedDeviceDao.getAllDevices()
    val recentPackets: Flow<List<MeshPacketEntity>> = packetDao.getRecentPackets()
    val clipboardHistory: Flow<List<ClipboardItemEntity>> = clipboardDao.getRecentItems()

    suspend fun getMeshInfoSync(): MeshEntity? = meshDao.getMeshInfoSync()

    suspend fun saveMesh(mesh: MeshEntity) = meshDao.saveMeshInfo(mesh)

    suspend fun clearMesh() {
        meshDao.clearMesh()
        trustedDeviceDao.clearAllDevices()
        packetDao.clearPackets()
        clipboardDao.clearHistory()
    }

    suspend fun saveDevice(device: TrustedDeviceEntity) = trustedDeviceDao.insertOrUpdateDevice(device)

    suspend fun saveDevices(devices: List<TrustedDeviceEntity>) = trustedDeviceDao.insertDevices(devices)

    suspend fun updateDeviceState(publicKey: String, state: ConnectionState) =
        trustedDeviceDao.updateDeviceState(publicKey, state)

    suspend fun updateDeviceRail(publicKey: String, rail: TransportRail, latencyMs: Long) =
        trustedDeviceDao.updateDeviceRail(publicKey, rail, latencyMs)

    suspend fun removeDevice(publicKey: String) = trustedDeviceDao.deleteDevice(publicKey)

    suspend fun getRemoteDevicesSync(): List<TrustedDeviceEntity> = trustedDeviceDao.getRemoteDevicesSync()

    suspend fun recordPacket(packet: MeshPacketEntity) = packetDao.insertPacket(packet)

    suspend fun addClipboardItem(item: ClipboardItemEntity) = clipboardDao.insertItem(item)

    suspend fun clearPackets() = packetDao.clearPackets()

    suspend fun clearClipboardHistory() = clipboardDao.clearHistory()
}
