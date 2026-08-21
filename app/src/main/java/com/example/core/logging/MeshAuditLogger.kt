package com.example.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Severity level for mesh transport and security events.
 */
enum class AuditLevel {
    INFO,
    HANDSHAKE,
    SECURITY_SUCCESS,
    SECURITY_WARNING,
    SECURITY_ERROR
}

/**
 * Event category for detailed triage.
 */
enum class AuditCategory {
    CONNECTION,
    DISCOVERY_NSD,
    DISCOVERY_UDP,
    DISCOVERY_WIFI_DIRECT,
    DISCOVERY_STUN,
    HANDSHAKE,
    DECRYPTION_ECDH,
    DECRYPTION_BROADCAST,
    SIGNATURE_VERIFICATION,
    PACKET_ROUTING,
    DEVICE_REVOCATION
}

/**
 * Structured audit record for mesh operations.
 */
data class MeshAuditEvent(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: AuditLevel,
    val category: AuditCategory,
    val peerName: String? = null,
    val peerKeyFingerprint: String? = null,
    val message: String,
    val details: String? = null,
    val error: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Structured, secure logging utility for the MeshRuntimeEngine.
 *
 * Captures connection attempts, socket lifecycles, and cryptographic decryption/verification outcomes
 * with clear categorization to solve debuggability wrinkles while strictly sanitizing sensitive secrets.
 */
object MeshAuditLogger {
    private const val TAG = "MeshAudit"
    private const val MAX_EVENT_BUFFER = 100

    private val _auditEvents = MutableStateFlow<List<MeshAuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<MeshAuditEvent>> = _auditEvents.asStateFlow()

    fun log(
        level: AuditLevel,
        category: AuditCategory,
        message: String,
        peerName: String? = null,
        peerKey: String? = null,
        details: String? = null,
        error: Throwable? = null
    ) {
        val safeKeyFingerprint = sanitizeKey(peerKey)
        val errorMsg = error?.let { "${it.javaClass.simpleName}: ${it.message}" }

        val event = MeshAuditEvent(
            level = level,
            category = category,
            peerName = peerName,
            peerKeyFingerprint = safeKeyFingerprint,
            message = message,
            details = details,
            error = errorMsg
        )

        // Android Logcat output
        val logLine = "[${category.name}] $message" +
            (if (peerName != null) " (Peer: $peerName $safeKeyFingerprint)" else "") +
            (if (details != null) " | Details: $details" else "") +
            (if (errorMsg != null) " | Error: $errorMsg" else "")

        when (level) {
            AuditLevel.INFO -> Log.i(TAG, logLine)
            AuditLevel.HANDSHAKE -> Log.i(TAG, "🤝 $logLine")
            AuditLevel.SECURITY_SUCCESS -> Log.d(TAG, "🛡️ $logLine")
            AuditLevel.SECURITY_WARNING -> Log.w(TAG, "⚠️ $logLine")
            AuditLevel.SECURITY_ERROR -> Log.e(TAG, "❌ $logLine", error)
        }

        // Update in-memory reactive buffer
        synchronized(this) {
            val current = _auditEvents.value.toMutableList()
            if (current.size >= MAX_EVENT_BUFFER) {
                current.removeAt(0)
            }
            current.add(event)
            _auditEvents.value = current
        }
    }

    fun logConnectionAttempt(rail: String, targetIp: String, port: Int, peerName: String? = null) {
        log(
            level = AuditLevel.INFO,
            category = AuditCategory.CONNECTION,
            message = "Initiating connection over $rail to $targetIp:$port",
            peerName = peerName
        )
    }

    fun logConnectionEstablished(rail: String, targetIp: String, port: Int, peerName: String? = null) {
        log(
            level = AuditLevel.INFO,
            category = AuditCategory.CONNECTION,
            message = "Connection established over $rail with $targetIp:$port",
            peerName = peerName
        )
    }

    fun logConnectionFailed(rail: String, targetIp: String, port: Int, error: Throwable, peerName: String? = null) {
        log(
            level = AuditLevel.SECURITY_WARNING,
            category = AuditCategory.CONNECTION,
            message = "Connection failed over $rail to $targetIp:$port",
            peerName = peerName,
            error = error
        )
    }

    fun logNsdDiscovered(serviceName: String, host: String, port: Int, meshName: String?) {
        log(
            level = AuditLevel.INFO,
            category = AuditCategory.DISCOVERY_NSD,
            message = "NSD ZeroConf discovered peer service '$serviceName' at $host:$port (Mesh: ${meshName ?: "any"})"
        )
    }

    fun logHandshakeInitiated(targetIp: String, peerName: String?, ephemeralToken: String?) {
        log(
            level = AuditLevel.HANDSHAKE,
            category = AuditCategory.HANDSHAKE,
            message = "Beginning automatic cryptographic handshake with $targetIp",
            peerName = peerName,
            details = "Token: ${ephemeralToken?.take(8) ?: "auto"}..."
        )
    }

    fun logHandshakeCompleted(peerName: String, peerKey: String, rail: String) {
        log(
            level = AuditLevel.HANDSHAKE,
            category = AuditCategory.HANDSHAKE,
            message = "Cryptographic handshake completed successfully over $rail",
            peerName = peerName,
            peerKey = peerKey
        )
    }

    fun logEcdhDecryptionSuccess(seq: Long, senderName: String, senderKey: String, sessionId: String) {
        log(
            level = AuditLevel.SECURITY_SUCCESS,
            category = AuditCategory.DECRYPTION_ECDH,
            message = "Targeted packet #$seq decrypted via ECDH session key",
            peerName = senderName,
            peerKey = senderKey,
            details = "Session: ${sessionId.take(12)}"
        )
    }

    fun logEcdhDecryptionFailure(seq: Long, senderName: String, senderKey: String, sessionId: String, error: Throwable) {
        log(
            level = AuditLevel.SECURITY_ERROR,
            category = AuditCategory.DECRYPTION_ECDH,
            message = "Targeted ECDH decryption failed on packet #$seq: ${error.message}",
            peerName = senderName,
            peerKey = senderKey,
            details = "Session: $sessionId. Tag authentication mismatch or invalid peer public key.",
            error = error
        )
    }

    fun logBroadcastDecryptionSuccess(seq: Long, packetType: String, senderName: String) {
        log(
            level = AuditLevel.SECURITY_SUCCESS,
            category = AuditCategory.DECRYPTION_BROADCAST,
            message = "Broadcast $packetType packet #$seq decrypted via 256-bit Mesh Master Key",
            peerName = senderName
        )
    }

    fun logBroadcastDecryptionFailure(seq: Long, packetType: String, senderName: String, error: Throwable) {
        log(
            level = AuditLevel.SECURITY_ERROR,
            category = AuditCategory.DECRYPTION_BROADCAST,
            message = "Mesh Master Key decryption failed for $packetType #$seq: ${error.message}",
            peerName = senderName,
            details = "Dropped unauthenticated/corrupted broadcast packet.",
            error = error
        )
    }

    fun logSignatureVerification(isValid: Boolean, senderName: String, senderKey: String, seq: Long) {
        if (isValid) {
            log(
                level = AuditLevel.SECURITY_SUCCESS,
                category = AuditCategory.SIGNATURE_VERIFICATION,
                message = "Strict ECDSA signature verified for packet #$seq",
                peerName = senderName,
                peerKey = senderKey
            )
        } else {
            log(
                level = AuditLevel.SECURITY_ERROR,
                category = AuditCategory.SIGNATURE_VERIFICATION,
                message = "ECDSA signature validation FAILED for packet #$seq from $senderName",
                peerName = senderName,
                peerKey = senderKey,
                details = "Packet rejected. Possible tampering or key mismatch."
            )
        }
    }

    fun clear() {
        synchronized(this) {
            _auditEvents.value = emptyList()
        }
    }

    private fun sanitizeKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return if (key.length > 12) {
            "key:${key.take(4)}...${key.takeLast(4)}"
        } else {
            "key:$key"
        }
    }
}
