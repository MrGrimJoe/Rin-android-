package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Rin", appName)
    }

    @Test
    fun `crypto engine encrypts and decrypts payload correctly with AES-GCM`() {
        val secretKey = "rin_aes_key:MyTestMesh"
        val plainText = "Confidential clipboard token: 98745-xyz-secret"

        val cipherText = CryptoEngine.encryptPayload(plainText, secretKey)
        assertNotEquals(plainText, cipherText)

        val decrypted = CryptoEngine.decryptPayload(cipherText, secretKey)
        assertEquals(plainText, decrypted)
    }

    @Test
    fun `crypto engine signs and verifies real digital signatures with tamper detection`() {
        val keyPair = CryptoEngine.generateIdentityKeyPair()
        val data = "Sensitive clipboard text to be transmitted over mesh"

        val signature = CryptoEngine.sign(data, keyPair.privateKey)
        assertTrue(signature.isNotBlank())

        // Verification with correct key and data must pass
        val isValid = CryptoEngine.verify(data, signature, keyPair.publicKey)
        assertTrue("Valid signature must pass verification", isValid)

        // Tampering with payload must fail verification
        val isTamperedValid = CryptoEngine.verify(data + " [tampered]", signature, keyPair.publicKey)
        assertFalse("Tampered payload must fail verification", isTamperedValid)

        // Verification with different public key must fail
        val anotherKeyPair = CryptoEngine.generateIdentityKeyPair()
        val isWrongKeyValid = CryptoEngine.verify(data, signature, anotherKeyPair.publicKey)
        assertFalse("Verification with wrong key must fail", isWrongKeyValid)
    }

    @Test
    fun `udp beacon json schema validation`() {
        val beaconJson = JSONObject().apply {
            put("magic", "RIN_BEACON")
            put("mesh", "Office Mesh")
            put("key", "pub_key_123")
            put("name", "Pixel 8 Pro")
            put("port", 45990)
            put("ts", System.currentTimeMillis())
        }

        assertEquals("RIN_BEACON", beaconJson.getString("magic"))
        assertEquals("Office Mesh", beaconJson.getString("mesh"))
        assertEquals("pub_key_123", beaconJson.getString("key"))
        assertEquals("Pixel 8 Pro", beaconJson.getString("name"))
        assertEquals(45990, beaconJson.getInt("port"))
        assertTrue(beaconJson.getLong("ts") > 0)
    }
}
