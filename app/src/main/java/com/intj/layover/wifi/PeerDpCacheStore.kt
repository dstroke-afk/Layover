package com.intj.layover.wifi

import android.content.Context
import com.intj.layover.ble.LayoverBlePayloadEncoder
import com.intj.layover.diagnostics.LayoverFileLogger
import java.io.File

/**
 * Payload served by the local device when a peer requests our full-resolution DP.
 */
data class OwnFullResAvatarPayload(
    val bytes: ByteArray,
    val sha256Hex: String,
    val mimeType: String = "image/jpeg"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OwnFullResAvatarPayload
        return sha256Hex == other.sha256Hex &&
            mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sha256Hex.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/**
 * Local disk cache for:
 * 1. The local user's full-resolution profile picture (<= 3 MB) + SHA-256 digest.
 * 2. Verified full-resolution peer profile pictures keyed by peer identifier + current BLE avatar digest.
 *
 * When a peer changes their DP, their BLE avatar digest changes so old cached files for that peer
 * are automatically bypassed and pruned when the new full-res DP is cached.
 */
class PeerDpCacheStore(
    private val cacheDir: File
) {
    constructor(context: Context) : this(File(context.filesDir, "layover_dp_cache"))

    companion object {
        private const val LEGACY_OWN_FULL_DP_FILE_NAME = "self_full_dp.bin"
        internal const val OWN_FULL_DP_FILE_NAME = "self_cropped_full_dp_v2.jpg"
        private const val LEGACY_PEER_DP_PREFIX = "dp_"
        private const val PEER_DP_PREFIX = "dp_v2_"

        fun isCroppedOwnFullResPath(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            return File(path).name == OWN_FULL_DP_FILE_NAME
        }

        fun isCroppedPeerDpPath(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            return File(path).name.startsWith(PEER_DP_PREFIX)
        }

        fun computeAvatarDigest(avatarBytes: ByteArray?): String? {
            if (avatarBytes == null || avatarBytes.isEmpty()) return null
            return EncryptedPhotoStreamProtocol.sha256Hex(avatarBytes).take(16)
        }

        internal fun sanitizeKeySegment(raw: String): String {
            return raw.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "_")
                .take(40)
                .ifBlank { "peer" }
        }
    }

    init {
        runCatching {
            cacheDir.mkdirs()
            purgeLegacyUncroppedCacheFiles()
        }
    }

    /**
     * Removes legacy uncropped DP files (`self_full_dp.bin` and pre-v2 `dp_*` files) so stale
     * uncropped originals saved by earlier builds are never served or displayed.
     */
    private fun purgeLegacyUncroppedCacheFiles() {
        if (!cacheDir.exists()) return
        val legacySelf = File(cacheDir, LEGACY_OWN_FULL_DP_FILE_NAME)
        if (legacySelf.exists()) {
            runCatching { legacySelf.delete() }
        }
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(LEGACY_PEER_DP_PREFIX) && !file.name.startsWith(PEER_DP_PREFIX)) {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * Persists the local user's full-resolution avatar bytes (enforcing the 3 MB cap)
     * and returns `(savedFile, sha256Hex)`.
     */
    fun saveOwnFullResAvatar(bytes: ByteArray): Pair<File, String>? {
        val validation = PhotoSizeValidator.validateSize(bytes.size.toLong())
        if (validation !is PhotoSizeValidationResult.Valid) {
            return null
        }
        return runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val outFile = File(cacheDir, OWN_FULL_DP_FILE_NAME)
            outFile.writeBytes(bytes)
            val sha256 = EncryptedPhotoStreamProtocol.sha256Hex(bytes)
            outFile to sha256
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed saving own full-res avatar", err)
        }.getOrNull()
    }

    /**
     * Loads the local user's full-resolution DP bytes for serving over LocalOnlyHotspot.
     * Falls back gracefully to stored [fallbackAvatarBytes] or [fallbackCompactBytes] when
     * the user saved an avatar before full-res storage existed.
     */
    fun loadOwnFullResAvatar(
        fullResFilePath: String?,
        fallbackAvatarBytes: ByteArray?,
        fallbackCompactBytes: ByteArray?
    ): OwnFullResAvatarPayload? {
        val diskBytes = fullResFilePath
            ?.takeIf { isCroppedOwnFullResPath(it) }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() in 1L..PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES }
            ?.let { file -> runCatching { file.readBytes() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

        val defaultDiskBytes = if (diskBytes == null) {
            val defaultFile = File(cacheDir, OWN_FULL_DP_FILE_NAME)
            defaultFile.takeIf { it.exists() && it.length() in 1L..PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES }
                ?.let { file -> runCatching { file.readBytes() }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val resolvedBytes = diskBytes
            ?: defaultDiskBytes
            ?: fallbackAvatarBytes?.takeIf {
                it.isNotEmpty() && it.size.toLong() <= PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
            }
            ?: fallbackCompactBytes?.takeIf {
                it.isNotEmpty() && it.size.toLong() <= PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES
            }
            ?: return null

        val sha = EncryptedPhotoStreamProtocol.sha256Hex(resolvedBytes)
        return OwnFullResAvatarPayload(
            bytes = resolvedBytes,
            sha256Hex = sha,
            mimeType = detectMimeType(resolvedBytes)
        )
    }

    /**
     * Looks up a cached full-res peer DP matching any alias in [peerAliases] (including deterministic
     * 8-hex BLE advertisement keys derived from `dev_...` IDs) and the exact [avatarDigest].
     */
    fun getCachedPeerDpFile(peerAliases: Collection<String>, avatarDigest: String): File? {
        val cleanDigest = sanitizeKeySegment(avatarDigest)
        if (cleanDigest.isBlank() || !cacheDir.exists()) return null
        val expandedAliases = expandPeerCacheKeys(peerAliases)
        for (alias in expandedAliases) {
            val candidate = File(cacheDir, buildPeerDpFileName(alias, cleanDigest))
            if (candidate.exists() && candidate.length() > 0L) {
                return candidate
            }
        }
        return null
    }

    /**
     * Saves a verified full-res peer DP under a deterministic peer key + [avatarDigest] and removes
     * any stale cached full-res DPs for [peerAliases] with older digests.
     *
     * Prefers the deterministic 8-hex BLE advertisement key (derived from `dev_...` or provided in
     * [peerAliases]) so cache hits work seamlessly between **People Around** and **Peer Profile**
     * even across app restarts before a GATT session reconnects.
     */
    fun savePeerDp(
        canonicalPeerKey: String,
        peerAliases: Collection<String>,
        avatarDigest: String,
        bytes: ByteArray
    ): File? {
        if (bytes.isEmpty() || bytes.size.toLong() > PhotoTransferConfig.MAX_PHOTO_SIZE_BYTES) return null
        val cleanDigest = sanitizeKeySegment(avatarDigest)
        return runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val allKeys = expandPeerCacheKeys(peerAliases + canonicalPeerKey)
            val storageKey = resolvePreferredStorageKey(canonicalPeerKey, allKeys)

            // Prune outdated digests for this peer across all known alias keys so stale DPs don't accumulate
            val peerPrefixes = (allKeys + storageKey)
                .filter { it.isNotBlank() }
                .map { "$PEER_DP_PREFIX${sanitizeKeySegment(it)}_" }
                .distinct()
            val targetName = buildPeerDpFileName(storageKey, cleanDigest)
            cacheDir.listFiles()?.forEach { existing ->
                val matchesPeer = peerPrefixes.any { prefix -> existing.name.startsWith(prefix) }
                if (matchesPeer && existing.name != targetName) {
                    runCatching { existing.delete() }
                }
            }

            val outFile = File(cacheDir, targetName)
            outFile.writeBytes(bytes)
            outFile
        }.onFailure { err ->
            LayoverFileLogger.logError("Failed saving cached peer full-res DP for $canonicalPeerKey", err)
        }.getOrNull()
    }

    private fun expandPeerCacheKeys(rawAliases: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in rawAliases) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            out.add(trimmed)
            if (trimmed.startsWith("dev_", ignoreCase = true)) {
                val derivedHex = LayoverBlePayloadEncoder.getInstanceIdHex(trimmed)
                if (derivedHex.isNotBlank() && !derivedHex.equals("00000000", ignoreCase = true)) {
                    out.add(derivedHex)
                }
            }
        }
        return out.toList()
    }

    private fun resolvePreferredStorageKey(canonicalPeerKey: String, expandedKeys: List<String>): String {
        val hexRegex = Regex("^[0-9A-Fa-f]{8}$")
        val derivedFromCanonical = if (canonicalPeerKey.trim().startsWith("dev_", ignoreCase = true)) {
            LayoverBlePayloadEncoder.getInstanceIdHex(canonicalPeerKey.trim())
                .takeIf { hexRegex.matches(it) && !it.equals("00000000", ignoreCase = true) }
        } else {
            null
        }
        return derivedFromCanonical
            ?: expandedKeys.firstOrNull { hexRegex.matches(it) && !it.equals("00000000", ignoreCase = true) }
            ?: canonicalPeerKey
    }

    private fun buildPeerDpFileName(peerKey: String, cleanDigest: String): String {
        return "$PEER_DP_PREFIX${sanitizeKeySegment(peerKey)}_${cleanDigest}.jpg"
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        return "image/jpeg"
    }
}
