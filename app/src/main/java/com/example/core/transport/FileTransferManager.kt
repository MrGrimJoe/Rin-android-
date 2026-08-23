package com.example.core.transport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.core.crypto.CryptoEngine
import com.example.core.network.MeshNotificationHelper
import com.example.core.protocol.FileChunkPayload
import com.example.core.protocol.FileTransferMetadata
import com.example.core.protocol.MeshPacket
import com.example.core.protocol.PacketType
import com.example.core.protocol.TransportRail
import com.example.data.local.RinRepository
import com.example.data.local.entity.MeshPacketEntity
import com.example.data.local.entity.TrustedDeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ReceivedFileRecord(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val senderName: String,
    val localFilePath: String,
    val receivedTimestamp: Long = System.currentTimeMillis()
)

class FileTransferManager(
    private val context: Context,
    private val repository: RinRepository,
    private val transmitToDevice: suspend (TrustedDeviceEntity, MeshPacket) -> Long?
) {
    private val tag = "FileTransferManager"
    private val activeReceivingFiles = ConcurrentHashMap<String, InFlightReceive>()
    private val sequenceNumber = AtomicLong(100)

    private val _fileReceivedEvents = MutableSharedFlow<ReceivedFileRecord>(extraBufferCapacity = 10)
    val fileReceivedEvents = _fileReceivedEvents.asSharedFlow()

    private class InFlightReceive(
        val metadata: FileTransferMetadata,
        val senderKey: String,
        val senderName: String,
        val tempFile: File,
        val receivedChunks: ConcurrentHashMap<Int, Boolean> = ConcurrentHashMap()
    )

    fun getDisplayNameAndSize(uri: Uri): Pair<String, Long> {
        var name = "mesh_file_${System.currentTimeMillis()}"
        var size: Long = 0
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx != -1) name = cursor.getString(nameIdx)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to resolve file metadata from Uri", e)
        }
        if (size <= 0) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    size = stream.available().toLong()
                }
            } catch (_: Exception) {}
        }
        return Pair(name, size)
    }

    suspend fun sendUri(
        uri: Uri,
        targetDevice: TrustedDeviceEntity,
        onProgress: (Float, Long, Long) -> Unit // (progressRatio, bytesSent, totalBytes)
    ): Boolean = withContext(Dispatchers.IO) {
        val meshInfo = repository.getMeshInfoSync() ?: return@withContext false
        val sessionKey = try {
            CryptoEngine.derivePeerSessionKey(meshInfo.localPrivateKey, targetDevice.publicKey)
        } catch (_: Exception) {
            CryptoEngine.deriveMeshEncryptionKey(meshInfo.meshSecret, meshInfo.meshName)
        }

        val (fileName, totalSize) = getDisplayNameAndSize(uri)
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val chunkSize = 64 * 1024 // 64 KB chunks for balanced encryption + TCP throughput
        val totalChunks = if (totalSize <= 0) 1 else ((totalSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val fileId = UUID.randomUUID().toString()

        // 1. Compute digest and prepare transfer metadata
        var inputStream: InputStream? = null
        var sha256Hex: String? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
                sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Digest computation failed: ${e.message}")
        }

        val metadata = FileTransferMetadata(
            fileId = fileId,
            fileName = fileName,
            fileSize = totalSize,
            mimeType = mimeType,
            totalChunks = totalChunks,
            chunkSize = chunkSize,
            sha256Checksum = sha256Hex
        )

        // 2. Dispatch FILE_START Packet
        val metaJson = metadata.toJson()
        val encMeta = CryptoEngine.encryptPayload(metaJson, sessionKey)
        val sigMeta = CryptoEngine.sign(encMeta, meshInfo.localPrivateKey)

        val startPacket = MeshPacket(
            sessionId = CryptoEngine.generateSessionId(),
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.FILE_START,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = targetDevice.publicKey,
            payload = encMeta,
            signature = sigMeta,
            rail = targetDevice.activeRail
        )

        val startLatency = transmitToDevice(targetDevice, startPacket)

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = startPacket.sessionId,
                sequence = startPacket.sequence,
                type = PacketType.FILE_START,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                targetKey = targetDevice.publicKey,
                payloadSummary = "AirDrop Sending: $fileName (${formatSize(totalSize)})",
                rawPayload = encMeta,
                signature = sigMeta,
                rail = targetDevice.activeRail,
                latencyMs = startLatency ?: 2,
                isOutbound = true
            )
        )

        // 3. Dispatch Chunk by Chunk
        var bytesSent = 0L
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            val buffer = ByteArray(chunkSize)

            for (chunkIdx in 0 until totalChunks) {
                val readCount = inputStream?.read(buffer) ?: -1
                if (readCount <= 0 && chunkIdx > 0) break

                val chunkBytes = if (readCount > 0 && readCount < chunkSize) {
                    buffer.copyOf(readCount)
                } else if (readCount > 0) {
                    buffer
                } else {
                    ByteArray(0)
                }

                val base64Data = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)
                val chunkPayload = FileChunkPayload(
                    fileId = fileId,
                    chunkIndex = chunkIdx,
                    totalChunks = totalChunks,
                    dataBase64 = base64Data
                ).toJson()

                val encChunk = CryptoEngine.encryptPayload(chunkPayload, sessionKey)
                val sigChunk = CryptoEngine.sign(encChunk, meshInfo.localPrivateKey)

                val chunkPacket = MeshPacket(
                    sessionId = startPacket.sessionId,
                    sequence = sequenceNumber.incrementAndGet(),
                    type = PacketType.FILE_CHUNK,
                    senderKey = meshInfo.localPublicKey,
                    senderName = meshInfo.localDeviceName,
                    targetKey = targetDevice.publicKey,
                    payload = encChunk,
                    signature = sigChunk,
                    rail = targetDevice.activeRail
                )

                transmitToDevice(targetDevice, chunkPacket)

                bytesSent += chunkBytes.size
                val ratio = if (totalSize > 0) (bytesSent.toFloat() / totalSize).coerceIn(0f, 1f) else ((chunkIdx + 1f) / totalChunks)
                onProgress(ratio, bytesSent, totalSize)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error streaming file chunks", e)
            return@withContext false
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }

        // 4. Dispatch FILE_COMPLETE Packet
        val completePayload = metadata.toJson()
        val encComplete = CryptoEngine.encryptPayload(completePayload, sessionKey)
        val sigComplete = CryptoEngine.sign(encComplete, meshInfo.localPrivateKey)

        val completePacket = MeshPacket(
            sessionId = startPacket.sessionId,
            sequence = sequenceNumber.incrementAndGet(),
            type = PacketType.FILE_COMPLETE,
            senderKey = meshInfo.localPublicKey,
            senderName = meshInfo.localDeviceName,
            targetKey = targetDevice.publicKey,
            payload = encComplete,
            signature = sigComplete,
            rail = targetDevice.activeRail
        )

        transmitToDevice(targetDevice, completePacket)

        repository.recordPacket(
            MeshPacketEntity(
                sessionId = completePacket.sessionId,
                sequence = completePacket.sequence,
                type = PacketType.FILE_COMPLETE,
                senderKey = meshInfo.localPublicKey,
                senderName = meshInfo.localDeviceName,
                targetKey = targetDevice.publicKey,
                payloadSummary = "AirDrop Complete: $fileName sent successfully",
                rawPayload = encComplete,
                signature = sigComplete,
                rail = targetDevice.activeRail,
                latencyMs = 2,
                isOutbound = true
            )
        )

        return@withContext true
    }

    suspend fun handleFileStart(plainJson: String, senderKey: String, senderName: String) {
        val metadata = FileTransferMetadata.fromJson(plainJson) ?: return
        val tempDir = File(context.cacheDir, "rin_incoming_transfers").apply { mkdirs() }
        val tempFile = File(tempDir, "${metadata.fileId}.tmp")
        if (tempFile.exists()) tempFile.delete()
        tempFile.createNewFile()

        val inFlight = InFlightReceive(
            metadata = metadata,
            senderKey = senderKey,
            senderName = senderName,
            tempFile = tempFile
        )
        activeReceivingFiles[metadata.fileId] = inFlight
        Log.d(tag, "Prepared to receive ${metadata.fileName} (${metadata.fileSize} bytes in ${metadata.totalChunks} chunks) from $senderName")
    }

    suspend fun handleFileChunk(plainJson: String, senderKey: String) {
        val chunk = FileChunkPayload.fromJson(plainJson) ?: return
        val inFlight = activeReceivingFiles[chunk.fileId] ?: return

        try {
            val rawBytes = Base64.decode(chunk.dataBase64, Base64.NO_WRAP)
            val offset = chunk.chunkIndex.toLong() * inFlight.metadata.chunkSize

            RandomAccessFile(inFlight.tempFile, "rw").use { raf ->
                raf.seek(offset)
                raf.write(rawBytes)
            }
            inFlight.receivedChunks[chunk.chunkIndex] = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to write chunk ${chunk.chunkIndex} for ${chunk.fileId}", e)
        }
    }

    suspend fun handleFileComplete(plainJson: String, senderName: String): File? {
        val meta = FileTransferMetadata.fromJson(plainJson) ?: return null
        val inFlight = activeReceivingFiles.remove(meta.fileId) ?: return null

        try {
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Downloads")
            targetDir.mkdirs()

            var destFile = File(targetDir, meta.fileName)
            if (destFile.exists()) {
                val nameWithoutExt = meta.fileName.substringBeforeLast(".", meta.fileName)
                val ext = if (meta.fileName.contains(".")) ".${meta.fileName.substringAfterLast(".")}" else ""
                destFile = File(targetDir, "${nameWithoutExt}_${System.currentTimeMillis()}$ext")
            }

            inFlight.tempFile.copyTo(destFile, overwrite = true)
            inFlight.tempFile.delete()

            Log.i(tag, "Successfully saved file to ${destFile.absolutePath} (${destFile.length()} bytes)")

            MeshNotificationHelper.showFileReceivedNotification(context, destFile, senderName, meta.mimeType)

            val record = ReceivedFileRecord(
                fileId = meta.fileId,
                fileName = destFile.name,
                fileSize = destFile.length(),
                mimeType = meta.mimeType,
                senderName = senderName,
                localFilePath = destFile.absolutePath
            )
            _fileReceivedEvents.emit(record)
            return destFile
        } catch (e: Exception) {
            Log.e(tag, "Error finalizing file receive", e)
            return null
        }
    }

    fun openFileWithSystem(context: Context, file: File, mimeType: String? = null) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Could not open file with external viewer", e)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format("%.1f MB", mb)
        } else if (kb >= 1.0) {
            String.format("%.1f KB", kb)
        } else {
            "$bytes B"
        }
    }
}
