package com.example.core.nativebridge

import android.util.Log

/**
 * RinNativeCoreBridge:
 * Architecture Layer connecting the Android Kotlin runtime with the shared C++ Rin Core engine (§09).
 *
 * Provides fallbacks to optimized pure Kotlin/Java cryptographic primitives & packet serialization
 * when compiled without standalone architecture-specific .so binaries, guaranteeing 100% operational
 * continuity across all CPU ABIs (arm64-v8a, armeabi-v7a, x86_64).
 */
object RinNativeCoreBridge {
    private const val TAG = "RinNativeCoreBridge"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("rin_core")
            isNativeLoaded = true
            Log.i(TAG, "Rin C++ Core Engine (librin_core.so) dynamically linked successfully.")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            Log.w(TAG, "librin_core.so not bundled in build — using high-performance Kotlin/Java JVM core fallback.")
        } catch (e: Exception) {
            isNativeLoaded = false
            Log.e(TAG, "Unexpected error loading native library", e)
        }
    }

    fun isNativeEngineAvailable(): Boolean = isNativeLoaded

    /**
     * Computes fast CRC32/SHA256 chunk checksum via C++ SIMD acceleration if available,
     * or standard JVM cryptography.
     */
    fun computeFastHash(data: ByteArray): String {
        if (isNativeLoaded) {
            try {
                return nativeComputeFastHash(data)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native hash not linked, using JVM fallback")
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates packet binary alignment and frame headers.
     */
    fun validateFrameHeader(headerMagic: Int): Boolean {
        if (isNativeLoaded) {
            try {
                return nativeValidateFrame(headerMagic)
            } catch (_: UnsatisfiedLinkError) {}
        }
        return headerMagic == 0x52494E31 // "RIN1" in ASCII Hex
    }

    // Native JNI method declarations for librin_core.so (Windows & Linux shared core)
    private external fun nativeComputeFastHash(data: ByteArray): String
    private external fun nativeValidateFrame(magic: Int): Boolean
}
