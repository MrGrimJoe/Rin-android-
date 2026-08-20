package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mesh_info")
data class MeshEntity(
    @PrimaryKey val id: Int = 1,
    val meshName: String,
    val localDeviceName: String,
    val localPublicKey: String,
    val localPrivateKey: String,
    val localFingerprint: String,
    val meshSecret: String = "",
    val port: Int = 45990,
    val createdAt: Long = System.currentTimeMillis()
)
