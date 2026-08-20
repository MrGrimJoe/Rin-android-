package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.MeshEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {
    @Query("SELECT * FROM mesh_info WHERE id = 1 LIMIT 1")
    fun getMeshInfo(): Flow<MeshEntity?>

    @Query("SELECT * FROM mesh_info WHERE id = 1 LIMIT 1")
    suspend fun getMeshInfoSync(): MeshEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeshInfo(mesh: MeshEntity)

    @Query("DELETE FROM mesh_info")
    suspend fun clearMesh()
}
