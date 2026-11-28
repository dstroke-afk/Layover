package com.intj.layover.crypto

import android.os.Build
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.EnumMap
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
    EC_P256("EC", "ECDH");

    companion object {
        fun fromWireName(name: String): KeyAlgorithm {
            return if (name.equals("X25519", ignoreCase = true) || name.equals("XDH", ignoreCase = true)) {
                X25519
            } else {
                EC_P256
            }
        }
    }
}

enum class KeyEstablishStatus {
    ESTABLISHED,
    ALREADY_ESTABLISHED_SAME_KEY,
    IGNORED_EXISTING_SESSION,
    IGNORED_STALE_KEY,
    ALGORITHM_UNSUPPORTED,
    FAILED
}

data class KeyEstablishOutcome(
    val status: KeyEstablishStatus,
    val fingerprint: String? = null,
    val localPublicKeyBase64: String? = null,
    val localAlgorithm: String? = null
) {
    val isUsableSession: Boolean
        get() = status == KeyEstablishStatus.ESTABLISHED ||
            status == KeyEstablishStatus.ALREADY_ESTABLISHED_SAME_KEY
}

class SessionCryptoManager(
    private val forceEcOnly: Boolean = false
) {
    companion object {
        private const val TAG = "LayoverBLE"
        private const val DEFAULT_PEER_KEY = "__default_peer__"
    }

    private data class PeerSession(
        val peerKey: String,
        val aliases: MutableSet<String>,
        val algorithm: KeyAlgorithm,
        val localPublicKeyBase64: String,
        val remotePublicKeyBase64: String,
        val remotePublicKey: PublicKey,
        val secretKey: SecretKey,
        val fingerprint: String,
        var allowRefreshOnReconnect: Boolean = false
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val localKeyPairs = EnumMap<KeyAlgorithm, KeyPair>(KeyAlgorithm::class.java)
    private val peerSessions = LinkedHashMap<String, PeerSession>()
    private val supersededRemoteKeys = HashMap<String, MutableSet<String>>()

    private var x25519SupportedCache: Boolean? = null
    private var currentAlgorithm: KeyAlgorithm = KeyAlgorithm.EC_P256
    private var activePeerKey: String? = null

    val sessionFingerprint: String?
        get() = synchronized(lock) {
            activePeerKey?.let { peerSessions[it]?.fingerprint }
        }

    val isSessionKeyEstablished: Boolean
        get() = synchronized(lock) {
            activePeerKey?.let { peerSessions[it] != null } == true
        }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }

    private fun isX25519Supported(): Boolean {
        if (forceEcOnly) return false
        x25519SupportedCache?.let { return it }

        val sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
        val supported = if (sdkInt in 1 until Build.VERSION_CODES.TIRAMISU) {
            false
        } else {
            runCatching {
                KeyPairGenerator.getInstance("X25519")
                KeyFactory.getInstance("X25519")
                KeyAgreement.getInstance("X25519")
                true
            }.getOrDefault(false)
        }
        x25519SupportedCache = supported
        return supported
    }

    private fun preferredAlgorithm(): KeyAlgorithm {
        return if (isX25519Supported()) KeyAlgorithm.X25519 else KeyAlgorithm.EC_P256
    }

    private fun createKeyPair(algorithm: KeyAlgorithm): KeyPair {
        return when (algorithm) {
            KeyAlgorithm.X25519 -> {
                val kpg = KeyPairGenerator.getInstance("X25519")
                val generated = kpg.generateKeyPair()
                logDebug("Generated ephemeral X25519 key pair.")
                generated
            }
            KeyAlgorithm.EC_P256 -> {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec("secp256r1"))
                val generated = kpg.generateKeyPair()
                logDebug("Generated ephemeral EC secp256r1 key pair.")
                generated
            }
        }
    }

    private fun getOrCreateLocalKeyPairLocked(algorithm: KeyAlgorithm): KeyPair {
        localKeyPairs[algorithm]?.let { return it }
        val effectiveAlgo = if (algorithm == KeyAlgorithm.X25519 && !isX25519Supported()) {
            KeyAlgorithm.EC_P256
        } else {
            algorithm
        }
        localKeyPairs[effectiveAlgo]?.let { return it }
        val created = try {
            createKeyPair(effectiveAlgo)
        } catch (e: Exception) {
            logWarn("${effectiveAlgo.algorithmName} key gen failed, falling back to EC secp256r1", e)
            createKeyPair(KeyAlgorithm.EC_P256)
        }
        val actualAlgo = if (created.public.algorithm.equals("XDH", ignoreCase = true) ||
            created.public.algorithm.equals("X25519", ignoreCase = true)
        ) {
            KeyAlgorithm.X25519
        } else {
            KeyAlgorithm.EC_P256
        }
        localKeyPairs[actualAlgo] = created
        return created
    }

    private fun encodePublicKey(keyPair: KeyPair): String {
        return Base64.getEncoder().encodeToString(keyPair.public.encoded)
    }

    /**
     * Ensures a stable local key pair exists without clearing established peer sessions.
     */
    fun ensureLocalKeyPair(targetAlgo: String? = null): String = synchronized(lock) {
        val desired = if (targetAlgo != null) {
            val parsed = KeyAlgorithm.fromWireName(targetAlgo)
            if (parsed == KeyAlgorithm.X25519 && !isX25519Supported()) KeyAlgorithm.EC_P256 else parsed
        } else {
            if (localKeyPairs.containsKey(currentAlgorithm)) currentAlgorithm else preferredAlgorithm()
        }
        val pair = getOrCreateLocalKeyPairLocked(desired)
        currentAlgorithm = if (localKeyPairs[KeyAlgorithm.X25519] === pair) {
            KeyAlgorithm.X25519
        } else {
            KeyAlgorithm.EC_P256
        }
        encodePublicKey(pair)
    }

    /**
     * Generates a fresh local key pair and clears existing peer sessions.
     */
    fun generateKeyPair(): String = synchronized(lock) {
        clearSessionLocked()
        val desired = preferredAlgorithm()
        val pair = getOrCreateLocalKeyPairLocked(desired)
        currentAlgorithm = if (localKeyPairs[KeyAlgorithm.X25519] === pair) {
            KeyAlgorithm.X25519
        } else {
            KeyAlgorithm.EC_P256
        }
        encodePublicKey(pair)
    }

    fun generateKeyPairForAlgorithm(targetAlgo: String): String = synchronized(lock) {
        val parsed = KeyAlgorithm.fromWireName(targetAlgo)
        val desired = if (parsed == KeyAlgorithm.X25519 && !isX25519Supported()) {
            logWarn("Requested X25519 not supported, using EC secp256r1")
            KeyAlgorithm.EC_P256
        } else {
            parsed
        }
        val pair = createKeyPair(desired)
        localKeyPairs[desired] = pair
        currentAlgorithm = desired
        encodePublicKey(pair)
    }

    fun getLocalPublicKeyBase64(): String? = synchronized(lock) {
        val pair = localKeyPairs[currentAlgorithm] ?: localKeyPairs.values.firstOrNull() ?: return null
        encodePublicKey(pair)
    }

    fun getLocalPublicKeyBase64ForAlgorithm(algorithmName: String): String? = synchronized(lock) {
        val algo = KeyAlgorithm.fromWireName(algorithmName)
        val pair = localKeyPairs[algo] ?: return null
        encodePublicKey(pair)
    }

    fun getAlgorithmName(): String = synchronized(lock) { currentAlgorithm.algorithmName }

    private fun normalizeAliases(peerId: String?, peerAliases: Collection<String>): Set<String> {
        val out = LinkedHashSet<String>()
        fun addIfValid(raw: String?) {
            val trimmed = raw?.trim()?.lowercase() ?: return
            if (trimmed.isNotEmpty() &&
                trimmed != "local" &&
                trimmed != "user" &&
                trimmed != "remote_peer" &&
                trimmed != "unknown_mac" &&
                trimmed != "00000000"
            ) {
                out.add(trimmed)
            }
        }
        addIfValid(peerId)
        peerAliases.forEach { addIfValid(it) }
        return out
    }

    private fun hasConflictingDevId(a: Set<String>, b: Set<String>): Boolean {
        val devA = a.filter { it.startsWith("dev_") }.toSet()
        val devB = b.filter { it.startsWith("dev_") }.toSet()
        return devA.isNotEmpty() && devB.isNotEmpty() && devA.intersect(devB).isEmpty()
    }

    private fun findSessionLocked(candidates: Set<String>, allowSingleSessionFallback: Boolean): PeerSession? {
        if (candidates.isNotEmpty()) {
            val directMatch = peerSessions.values.firstOrNull { session ->
                candidates.any { cand -> cand == session.peerKey || session.aliases.contains(cand) }
            }
            if (directMatch != null) {
                if (!hasConflictingDevId(directMatch.aliases, candidates)) {
                    directMatch.aliases.addAll(candidates)
                }
                return directMatch
            }
            if (allowSingleSessionFallback && peerSessions.size == 1) {
                val sole = peerSessions.values.first()
                if (sole.peerKey == DEFAULT_PEER_KEY || !hasConflictingDevId(sole.aliases, candidates)) {
                    return sole
                }
            }
            return null
        }
        return activePeerKey?.let { peerSessions[it] } ?: peerSessions.values.lastOrNull()
    }

    fun establishRemoteKey(remotePublicKeyBase64: String, algorithmName: String = "EC"): Boolean {
        val outcome = establishRemoteKeyForPeer(
            peerId = DEFAULT_PEER_KEY,
            peerAliases = emptySet(),
            remotePublicKeyBase64 = remotePublicKeyBase64,
            algorithmName = algorithmName,
            allowOverwrite = false
        )
        return outcome.isUsableSession
    }

    fun establishRemoteKeyForPeer(
        peerId: String,
        peerAliases: Set<String> = emptySet(),
        remotePublicKeyBase64: String,
        algorithmName: String = "EC",
        allowOverwrite: Boolean = false
    ): KeyEstablishOutcome = synchronized(lock) {
        val trimmedB64 = remotePublicKeyBase64.trim()
        if (trimmedB64.isEmpty()) {
            return KeyEstablishOutcome(status = KeyEstablishStatus.FAILED)
        }

        val targetAlgo = KeyAlgorithm.fromWireName(algorithmName)
        if (targetAlgo == KeyAlgorithm.X25519 && !isX25519Supported()) {
            val fallbackPair = getOrCreateLocalKeyPairLocked(KeyAlgorithm.EC_P256)
            currentAlgorithm = KeyAlgorithm.EC_P256
            val fallbackPub = encodePublicKey(fallbackPair)
            logWarn("Key algorithm unsupported on this device: remote=X25519, local fallback=EC")
            return KeyEstablishOutcome(
                status = KeyEstablishStatus.ALGORITHM_UNSUPPORTED,
                localPublicKeyBase64 = fallbackPub,
                localAlgorithm = KeyAlgorithm.EC_P256.algorithmName
            )
        }

        val normalizedAliases = normalizeAliases(peerId, peerAliases)
        val canonicalKey = normalizedAliases.firstOrNull { it.startsWith("dev_") }
            ?: normalizedAliases.firstOrNull()
            ?: DEFAULT_PEER_KEY

        val existingSession = findSessionLocked(
            candidates = normalizedAliases,
            allowSingleSessionFallback = canonicalKey == DEFAULT_PEER_KEY
        )

        if (existingSession != null) {
            existingSession.aliases.addAll(normalizedAliases)
            activePeerKey = existingSession.peerKey

            if (existingSession.remotePublicKeyBase64 == trimmedB64 && existingSession.algorithm == targetAlgo) {
                existingSession.allowRefreshOnReconnect = false
                currentAlgorithm = existingSession.algorithm
                return KeyEstablishOutcome(
                    status = KeyEstablishStatus.ALREADY_ESTABLISHED_SAME_KEY,
                    fingerprint = existingSession.fingerprint,
                    localPublicKeyBase64 = existingSession.localPublicKeyBase64,
                    localAlgorithm = existingSession.algorithm.algorithmName
                )
            }

            val retiredKeys = supersededRemoteKeys[existingSession.peerKey]
            if (!allowOverwrite && retiredKeys != null && retiredKeys.contains(trimmedB64)) {
                logWarn("Ignoring stale superseded remote public key for peer=${existingSession.peerKey}")
                return KeyEstablishOutcome(
                    status = KeyEstablishStatus.IGNORED_STALE_KEY,
                    fingerprint = existingSession.fingerprint,
                    localPublicKeyBase64 = existingSession.localPublicKeyBase64,
                    localAlgorithm = existingSession.algorithm.algorithmName
                )
            }

            if (!allowOverwrite && !existingSession.allowRefreshOnReconnect) {
                logWarn(
                    "Preserving existing E2EE session for peer=${existingSession.peerKey} " +
                        "(fingerprint=${existingSession.fingerprint}); ignoring non-reset different key."
                )
                return KeyEstablishOutcome(
                    status = KeyEstablishStatus.IGNORED_EXISTING_SESSION,
                    fingerprint = existingSession.fingerprint,
                    localPublicKeyBase64 = existingSession.localPublicKeyBase64,
                    localAlgorithm = existingSession.algorithm.algorithmName
                )
            }
        }

        return try {
            val keyBytes = Base64.getDecoder().decode(trimmedB64)
            val localPair = getOrCreateLocalKeyPairLocked(targetAlgo)
            val localPubB64 = encodePublicKey(localPair)

            val keyFactory = KeyFactory.getInstance(targetAlgo.algorithmName)
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))

            val keyAgreement = KeyAgreement.getInstance(targetAlgo.agreementName)
            keyAgreement.init(localPair.private)
            keyAgreement.doPhase(pubKey, true)
            val rawSharedSecret = keyAgreement.generateSecret()

            // Derive 256-bit AES key via SHA-256 context-bound digest
            val md = MessageDigest.getInstance("SHA-256")
            md.update("Layover-E2EE-v1".toByteArray(Charsets.UTF_8))
            md.update(rawSharedSecret)
            val sessionKeyBytes = md.digest()

            val derivedSecret = SecretKeySpec(sessionKeyBytes, "AES")
            val fingerprint = computeFingerprint(localPair.public.encoded, pubKey.encoded)

            val sessionKey = existingSession?.peerKey ?: canonicalKey
            if (existingSession != null && existingSession.remotePublicKeyBase64 != trimmedB64) {
                supersededRemoteKeys
                    .getOrPut(sessionKey) { LinkedHashSet() }
                    .add(existingSession.remotePublicKeyBase64)
            }

            val mergedAliases = LinkedHashSet<String>().apply {
                existingSession?.aliases?.let { addAll(it) }
                addAll(normalizedAliases)
                add(sessionKey)
            }

            val newSession = PeerSession(
                peerKey = sessionKey,
                aliases = mergedAliases,
                algorithm = targetAlgo,
                localPublicKeyBase64 = localPubB64,
                remotePublicKeyBase64 = trimmedB64,
                remotePublicKey = pubKey,
                secretKey = derivedSecret,
                fingerprint = fingerprint,
                allowRefreshOnReconnect = false
            )
            peerSessions[sessionKey] = newSession
            activePeerKey = sessionKey
            currentAlgorithm = targetAlgo

            logDebug("E2EE AES-256 session key established for peer=$sessionKey (${targetAlgo.algorithmName}). Fingerprint=$fingerprint")
            KeyEstablishOutcome(
                status = KeyEstablishStatus.ESTABLISHED,
                fingerprint = fingerprint,
                localPublicKeyBase64 = localPubB64,
                localAlgorithm = targetAlgo.algorithmName
            )
        } catch (e: Exception) {
            logError("Failed to establish remote key for E2EE (peer=$canonicalKey, algo=${targetAlgo.algorithmName})", e)
            KeyEstablishOutcome(status = KeyEstablishStatus.FAILED)
        }
    }

    fun hasSessionForPeer(peerCandidates: Collection<String> = emptyList()): Boolean = synchronized(lock) {
        val normalized = normalizeAliases(null, peerCandidates)
        findSessionLocked(normalized, allowSingleSessionFallback = true) != null
    }

    fun getSessionFingerprintForPeer(peerCandidates: Collection<String> = emptyList()): String? = synchronized(lock) {
        val normalized = normalizeAliases(null, peerCandidates)
        findSessionLocked(normalized, allowSingleSessionFallback = true)?.fingerprint
    }

    fun needsReconnectConfirmation(peerCandidates: Collection<String> = emptyList()): Boolean = synchronized(lock) {
        val normalized = normalizeAliases(null, peerCandidates)
        val session = findSessionLocked(normalized, allowSingleSessionFallback = true) ?: return true
        session.allowRefreshOnReconnect
    }

    fun markPeerDisconnected(peerCandidates: Collection<String> = emptyList()) = synchronized(lock) {
        val normalized = normalizeAliases(null, peerCandidates)
        if (normalized.isEmpty()) {
            peerSessions.values.forEach { it.allowRefreshOnReconnect = true }
        } else {
            val session = findSessionLocked(normalized, allowSingleSessionFallback = false)
            if (session != null) {
                session.allowRefreshOnReconnect = true
            } else {
                peerSessions.values.forEach { it.allowRefreshOnReconnect = true }
            }
        }
    }

    fun clearPeerSession(peerCandidates: Collection<String> = emptyList()) = synchronized(lock) {
        val normalized = normalizeAliases(null, peerCandidates)
        val target = findSessionLocked(normalized, allowSingleSessionFallback = true)
        if (target != null) {
            supersededRemoteKeys
                .getOrPut(target.peerKey) { LinkedHashSet() }
                .add(target.remotePublicKeyBase64)
            peerSessions.remove(target.peerKey)
            if (activePeerKey == target.peerKey) {
                activePeerKey = peerSessions.keys.lastOrNull()
            }
        } else if (normalized.isEmpty()) {
            peerSessions.clear()
            activePeerKey = null
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

    fun encryptBytes(plaintext: ByteArray, peerCandidates: Collection<String> = emptyList()): EncryptedPayload? {
        val key = synchronized(lock) {
            val normalized = normalizeAliases(null, peerCandidates)
            findSessionLocked(normalized, allowSingleSessionFallback = true)?.secretKey
        } ?: return null

        return try {
            val iv = ByteArray(12)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(plaintext)
            EncryptedPayload(iv = iv, ciphertext = ciphertext)
        } catch (e: Exception) {
            logError("AES-GCM Byte Encryption failed", e)
            null
        }
    }

    fun decryptBytes(payload: EncryptedPayload, peerCandidates: Collection<String> = emptyList()): ByteArray? {
        val key = synchronized(lock) {
            val normalized = normalizeAliases(null, peerCandidates)
            findSessionLocked(normalized, allowSingleSessionFallback = true)?.secretKey
        } ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, payload.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            cipher.doFinal(payload.ciphertext)
        } catch (e: Exception) {
            logError("AES-GCM Byte Decryption failed", e)
            null
        }
    }

    fun encryptText(plaintext: String, peerCandidates: Collection<String> = emptyList()): EncryptedPayload? {
        return encryptBytes(plaintext.toByteArray(Charsets.UTF_8), peerCandidates)
    }

    fun decryptText(payload: EncryptedPayload, peerCandidates: Collection<String> = emptyList()): String? {
        val plaintextBytes = decryptBytes(payload, peerCandidates) ?: return null
        return String(plaintextBytes, Charsets.UTF_8)
    }

    private fun clearSessionLocked() {
        peerSessions.clear()
        supersededRemoteKeys.clear()
        localKeyPairs.clear()
        activePeerKey = null
    }

    fun clearSession() = synchronized(lock) {
        clearSessionLocked()
    }
}
