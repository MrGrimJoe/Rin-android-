package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.protocol.ConnectionState
import com.example.core.protocol.PlatformType
import com.example.core.protocol.TransportRail

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    @PrimaryKey val publicKey: String,
    val name: String,
    val platform: PlatformType,
    val connectionState: ConnectionState,
    val activeRail: TransportRail,
    val ipAddress: String? = null,
    val port: Int = 45990,
    val latencyMs: Long = 2,
    val batteryLevel: Int = 85,
    val isSelf: Boolean = false,
    val fingerprint: String = "ed25519:" + publicKey.take(6) + "..." + publicKey.takeLast(4),
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)
