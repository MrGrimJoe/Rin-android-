package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.core.protocol.ConnectionState
import com.example.core.protocol.TransportRail
import com.example.data.local.entity.TrustedDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY isSelf DESC, lastSeen DESC")
    fun getAllDevices(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE isSelf = 0")
    suspend fun getRemoteDevicesSync(): List<TrustedDeviceEntity>

    @Query("SELECT * FROM trusted_devices WHERE publicKey = :publicKey LIMIT 1")
    suspend fun getDeviceByPublicKey(publicKey: String): TrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDevice(device: TrustedDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevices(devices: List<TrustedDeviceEntity>)

    @Query("UPDATE trusted_devices SET connectionState = :state, lastSeen = :lastSeen WHERE publicKey = :publicKey")
    suspend fun updateDeviceState(publicKey: String, state: ConnectionState, lastSeen: Long = System.currentTimeMillis())

    @Query("UPDATE trusted_devices SET activeRail = :rail, latencyMs = :latencyMs WHERE publicKey = :publicKey")
    suspend fun updateDeviceRail(publicKey: String, rail: TransportRail, latencyMs: Long)

    @Query("DELETE FROM trusted_devices WHERE publicKey = :publicKey")
    suspend fun deleteDevice(publicKey: String)

    @Query("DELETE FROM trusted_devices")
    suspend fun clearAllDevices()
}
