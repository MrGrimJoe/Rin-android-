package com.example.core.protocol

import org.json.JSONObject

enum class ConnectionState(val isOnline: Boolean) {
    CONNECTED(true),
    ACTIVE(true),
    RECONNECTING(false),
    IDLE(true),
    OFFLINE(false),
    LOST(false),
    DISCOVERED(false),
    AUTHENTICATING(false)
}

enum class TransportRail(val label: String, val latencyIndicator: String) {
    LAN("LAN", "< 2ms"),
    WIFI_DIRECT("Wi-Fi Direct", "< 10ms"),
    BLE("BLE", "< 40ms"),
    INTERNET_P2P("Internet P2P", "Variable"),
    RELAY("Relay Fallback", "Encrypted")
}

enum class PlatformType(val displayName: String) {
    ANDROID("Android"),
    WINDOWS("Windows"),
    LINUX("Linux"),
    MACOS("macOS"),
    TABLET("Tablet")
}

enum class PacketType {
    HELLO,
    JOIN_REQUEST,
    JOIN_ACCEPT,
    CLIPBOARD_SYNC,
    BROWSER_HANDOFF,
    FILE_START,
    FILE_CHUNK,
    FILE_COMPLETE,
    REVOCATION,
    HEARTBEAT,
    ACK
}

data class FileTransferMetadata(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val sha256Checksum: String? = null
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("fileId", fileId)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("mimeType", mimeType)
            put("totalChunks", totalChunks)
            put("chunkSize", chunkSize)
            sha256Checksum?.let { put("checksum", it) }
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileTransferMetadata? {
            return try {
                val json = JSONObject(jsonStr)
                FileTransferMetadata(
                    fileId = json.getString("fileId"),
                    fileName = json.getString("fileName"),
                    fileSize = json.getLong("fileSize"),
                    mimeType = json.optString("mimeType", "*/*"),
                    totalChunks = json.getInt("totalChunks"),
                    chunkSize = json.optInt("chunkSize", 65536),
                    sha256Checksum = json.optString("checksum", null)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class FileChunkPayload(
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val dataBase64: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("fileId", fileId)
            put("chunkIndex", chunkIndex)
            put("totalChunks", totalChunks)
            put("data", dataBase64)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): FileChunkPayload? {
            return try {
                val json = JSONObject(jsonStr)
                FileChunkPayload(
                    fileId = json.getString("fileId"),
                    chunkIndex = json.getInt("chunkIndex"),
                    totalChunks = json.getInt("totalChunks"),
                    dataBase64 = json.getString("data")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class MeshPacket(
    val version: Int = 1,
    val sessionId: String,
    val sequence: Long,
    val type: PacketType,
    val senderKey: String,
    val senderName: String,
    val targetKey: String? = null,
    val payload: String,
    val signature: String,
    val rail: TransportRail = TransportRail.LAN,
    val timestamp: Long = System.currentTimeMillis()
)

data class QrJoinToken(
    val meshName: String,
    val hostPublicKey: String,
    val hostDeviceName: String,
    val ephemeralToken: String,
    val hostPort: Int = 45990,
    val hostIp: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("meshName", meshName)
            put("hostPublicKey", hostPublicKey)
            put("hostDeviceName", hostDeviceName)
            put("ephemeralToken", ephemeralToken)
            put("hostPort", hostPort)
            hostIp?.let { put("hostIp", it) }
            put("timestamp", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): QrJoinToken? {
            return try {
                val json = JSONObject(jsonStr)
                QrJoinToken(
                    meshName = json.getString("meshName"),
                    hostPublicKey = json.getString("hostPublicKey"),
                    hostDeviceName = json.getString("hostDeviceName"),
                    ephemeralToken = json.getString("ephemeralToken"),
                    hostPort = json.optInt("hostPort", 45990),
                    hostIp = json.optString("hostIp", null),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
