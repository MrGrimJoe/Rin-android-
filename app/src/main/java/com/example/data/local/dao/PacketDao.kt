package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MeshPacketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    @Query("SELECT * FROM mesh_packets ORDER BY timestamp DESC LIMIT 100")
    fun getRecentPackets(): Flow<List<MeshPacketEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacket(packet: MeshPacketEntity)

    @Query("DELETE FROM mesh_packets")
    suspend fun clearPackets()
}
