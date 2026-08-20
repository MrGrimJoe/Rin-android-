package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.protocol.TransportRail

@Entity(tableName = "clipboard_items")
data class ClipboardItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val senderName: String,
    val rail: TransportRail = TransportRail.LAN,
    val isLocal: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
