package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.protocol.PacketType
import com.example.core.protocol.TransportRail

@Entity(tableName = "mesh_packets")
data class MeshPacketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sequence: Long,
    val type: PacketType,
    val senderKey: String,
    val senderName: String,
    val targetKey: String? = null,
    val payloadSummary: String,
    val rawPayload: String,
    val signature: String,
    val rail: TransportRail,
    val latencyMs: Long = 1,
    val isOutbound: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
