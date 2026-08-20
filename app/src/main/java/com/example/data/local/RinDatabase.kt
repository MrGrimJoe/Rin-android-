package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.ClipboardDao
import com.example.data.local.dao.MeshDao
import com.example.data.local.dao.PacketDao
import com.example.data.local.dao.TrustedDeviceDao
import com.example.data.local.entity.ClipboardItemEntity
import com.example.data.local.entity.MeshEntity
import com.example.data.local.entity.MeshPacketEntity
import com.example.data.local.entity.TrustedDeviceEntity

@Database(
    entities = [
        MeshEntity::class,
        TrustedDeviceEntity::class,
        MeshPacketEntity::class,
        ClipboardItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RinDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun packetDao(): PacketDao
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: RinDatabase? = null

        fun getDatabase(context: Context): RinDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RinDatabase::class.java,
                    "rin_mesh_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
