package com.intj.layover.crypto

import android.os.Build
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedPayload

        if (!iv.contentEquals(other.iv)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

enum class KeyAlgorithm(val algorithmName: String, val agreementName: String) {
    X25519("X25519", "X25519"),
    EC_P256("EC", "ECDH")
}

class SessionCryptoManager {

    private var currentAlgorithm: KeyAlgorithm = KeyAlgorithm.EC_P256
    private var localKeyPair: KeyPair? = null
    private var remotePublicKey: PublicKey? = null
    private var secretKey: SecretKey? = null
    private val random = SecureRandom()

    var sessionFingerprint: String? = null
        private set

    val isSessionKeyEstablished: Boolean
        get() = secretKey != null

    fun generateKeyPair(): String {
        clearSession()

        val pair = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                currentAlgorithm = KeyAlgorithm.X25519
                val kpg = KeyPairGenerator.getInstance("X25519")
                kpg.generateKeyPair()
            } catch (e: Exception) {
                generateEcFallback()
            }
        } else {
            generateEcFallback()
        }

        localKeyPair = pair
        return Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
    }

    private fun generateEcFallback(): KeyPair {
        currentAlgorithm = KeyAlgorithm.EC_P256
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun getLocalPublicKeyBase64(): String? {
        val pub = localKeyPair?.public ?: return null
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    fun getAlgorithmName(): String = currentAlgorithm.algorithmName

    fun establishRemoteKey(remotePublicKeyBase64: String, algorithmName: String = "EC"): Boolean {
        val localPair = localKeyPair ?: return false
        return try {
            val keyBytes = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
            val algo = if (algorithmName == "X25519" || algorithmName == "XDH") "X25519" else "EC"
            val keyFactory = KeyFactory.getInstance(algo)
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))

            remotePublicKey = pubKey

            val keyAgreement = KeyAgreement.getInstance(
                if (algo == "X25519") "X25519" else "ECDH"
            )
            keyAgreement.init(localPair.private)
            keyAgreement.doPhase(pubKey, true)
            val rawSharedSecret = keyAgreement.generateSecret()

            // Derive 256-bit AES key via SHA-256 HKDF-like context digest
            val md = MessageDigest.getInstance("SHA-256")
            md.update("Layover-E2EE-v1".toByteArray(Charsets.UTF_8))
            md.update(rawSharedSecret)
            val sessionKeyBytes = md.digest()

            secretKey = SecretKeySpec(sessionKeyBytes, "AES")
            sessionFingerprint = computeFingerprint(localPair.public.encoded, pubKey.encoded)
            true
        } catch (e: Exception) {
            clearSession()
            false
        }
    }

    private fun computeFingerprint(pub1: ByteArray, pub2: ByteArray): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            if (compareByteArrays(pub1, pub2) <= 0) {
                md.update(pub1)
                md.update(pub2)
            } else {
                md.update(pub2)
                md.update(pub1)
            }
            val hash = md.digest()
            val hex = hash.take(6).joinToString("") { "%02X".format(it) }
            "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
        } catch (e: Exception) {
            "E2EE-SECURE"
        }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = a.size.coerceAtMost(b.size)
        for (i in 0 until minLen) {
            val res = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (res != 0) return res
        }
        return a.size.compareTo(b.size)
    }

    fun encryptText(plaintext: String): EncryptedPayload? {
        val key = secretKey ?: return null
        return try {
            val iv = ByteArray(12)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            EncryptedPayload(iv = iv, ciphertext = ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    fun decryptText(payload: EncryptedPayload): String? {
        val key = secretKey ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, payload.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plaintextBytes = cipher.doFinal(payload.ciphertext)
            String(plaintextBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun clearSession() {
        secretKey = null
        localKeyPair = null
        remotePublicKey = null
        sessionFingerprint = null
    }
}
