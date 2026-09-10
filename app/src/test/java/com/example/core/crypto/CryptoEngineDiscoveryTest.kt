package com.example.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the discovery-hardening crypto primitives added alongside
 * Windows' CryptoEngine::derive_beacon_tag / generate_discovery_nonce
 * (see rin-windows tests/test_discovery.cpp for the mirrored C++ tests,
 * and the corresponding two-device challenge/response integration test
 * there -- that level of integration test isn't duplicated here yet
 * since it requires mocking RinRepository/Context; see DESIGN_NOTES.md
 * "Discovery hardening" for that as a flagged follow-up).
 *
 * These are pure functions with no Android/Context dependency, so they
 * run as plain JVM unit tests -- no Robolectric needed.
 */
class CryptoEngineDiscoveryTest {

    private fun fixedKey(seed: Int): javax.crypto.SecretKey {
        val bytes = ByteArray(32) { i -> ((seed + i) and 0xFF).toByte() }
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }

    @Test
    fun `deriveBeaconTag is deterministic for the same key and time bucket`() {
        val key = fixedKey(0)
        val tag1 = CryptoEngine.deriveBeaconTag(key, 100L)
        val tag2 = CryptoEngine.deriveBeaconTag(key, 100L)
        assertTrue("tag should not be empty", tag1.isNotEmpty())
        assertEquals(
            "the SAME mesh key + time bucket must always produce the SAME tag " +
                "(so a legitimate member's beacon is recognizable, and so this stays " +
                "wire-compatible with Windows' derive_beacon_tag for the identical inputs)",
            tag1, tag2
        )
    }

    @Test
    fun `deriveBeaconTag rotates across time buckets`() {
        val key = fixedKey(0)
        val tagBucket100 = CryptoEngine.deriveBeaconTag(key, 100L)
        val tagBucket101 = CryptoEngine.deriveBeaconTag(key, 101L)
        assertNotEquals(
            "a DIFFERENT time bucket must produce a DIFFERENT tag -- tags rotate, " +
                "they are not a permanent tracking identifier",
            tagBucket100, tagBucket101
        )
    }

    @Test
    fun `deriveBeaconTag depends on the real mesh key`() {
        val keyA = fixedKey(0)
        val keyB = fixedKey(1)
        val tagA = CryptoEngine.deriveBeaconTag(keyA, 100L)
        val tagB = CryptoEngine.deriveBeaconTag(keyB, 100L)
        assertNotEquals(
            "a DIFFERENT mesh key must produce a DIFFERENT tag -- an outsider without " +
                "the real secret can't compute a matching tag, closing the old " +
                "\"cleartext mesh name in the beacon\" leak",
            tagA, tagB
        )
    }

    @Test
    fun `generateDiscoveryNonce produces distinct non-empty values`() {
        val n1 = CryptoEngine.generateDiscoveryNonce()
        val n2 = CryptoEngine.generateDiscoveryNonce()
        assertTrue(n1.isNotEmpty() && n2.isNotEmpty())
        assertNotEquals(
            "consecutive nonces must not collide -- a replayed/stale challenge " +
                "response shouldn't be able to echo back a nonce we're currently expecting",
            n1, n2
        )
    }

    @Test
    fun `discovery challenge and response payloads round-trip through the real mesh key`() {
        // Exercises the actual AES-GCM encrypt/decrypt path the
        // challenge/response handshake depends on, end to end, the same
        // way the real handshake would use it (just without the network
        // hop -- see the flagged follow-up above for the full two-device
        // version).
        val meshKey = CryptoEngine.deriveMeshEncryptionKey("shared-secret-for-test", "TestMesh")
        val nonce = CryptoEngine.generateDiscoveryNonce()

        val challengeBody = org.json.JSONObject().apply {
            put("nonce", nonce)
            put("ts", System.currentTimeMillis())
            put("challengerPort", 45990)
        }
        val encryptedChallenge = CryptoEngine.encryptPayload(challengeBody.toString(), meshKey)
        val decryptedChallenge = CryptoEngine.decryptPayload(encryptedChallenge, meshKey)
        val parsedChallenge = org.json.JSONObject(decryptedChallenge)
        assertEquals(nonce, parsedChallenge.optString("nonce"))

        val responseBody = org.json.JSONObject().apply {
            put("nonce", parsedChallenge.optString("nonce"))
            put("deviceName", "Device B")
            put("ts", System.currentTimeMillis())
        }
        val encryptedResponse = CryptoEngine.encryptPayload(responseBody.toString(), meshKey)
        val decryptedResponse = CryptoEngine.decryptPayload(encryptedResponse, meshKey)
        val parsedResponse = org.json.JSONObject(decryptedResponse)

        assertEquals("the echoed nonce must match what was sent", nonce, parsedResponse.optString("nonce"))
        assertEquals("Device B", parsedResponse.optString("deviceName"))
    }

    @Test(expected = Exception::class)
    fun `a discovery response encrypted with the wrong mesh key fails to decrypt`() {
        // This is the actual security property the whole handshake rests
        // on: a peer that doesn't hold the real mesh secret can produce
        // ciphertext, but decrypting it with OUR real key must fail
        // (AES-GCM's authentication tag check), not silently succeed
        // with garbage -- that's what makes "matched the beacon tag"
        // safely separable from "is actually trusted".
        val realMeshKey = CryptoEngine.deriveMeshEncryptionKey("real-secret", "TestMesh")
        val wrongMeshKey = CryptoEngine.deriveMeshEncryptionKey("a-different-secret", "TestMesh")

        val body = org.json.JSONObject().apply { put("nonce", CryptoEngine.generateDiscoveryNonce()) }
        val encrypted = CryptoEngine.encryptPayload(body.toString(), wrongMeshKey)

        // Must throw -- a stranger's challenge/response must never decrypt
        // successfully with our real key.
        CryptoEngine.decryptPayload(encrypted, realMeshKey)
    }
}
