package com.intj.layover.p2p

import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.model.GenderOption
import java.util.Base64

sealed class WireFrame {
    data class KeyExchange(
        val senderId: String,
        val publicKeyBase64: String,
        val algorithm: String,
        val isResponse: Boolean = false,
        val isReset: Boolean = false
    ) : WireFrame()

    data class ConnectionRequest(
        val senderId: String,
        val displayName: String,
        val gender: GenderOption? = null,
        val age: Int? = null
    ) : WireFrame()

    data class ConnectionAccept(
        val senderId: String
    ) : WireFrame()

    data class ConnectionDecline(
        val senderId: String
    ) : WireFrame()

    data class ProfileSync(
        val senderId: String,
        val displayName: String,
        val gender: GenderOption,
        val age: Int?,
        val compactAvatarBase64: String?
    ) : WireFrame()

    data class PeerBlockState(
        val senderId: String,
        val blocked: Boolean
    ) : WireFrame()

    data class ChatEncrypted(
        val msgId: String,
        val senderId: String,
        val timestamp: Long,
        val ivBase64: String,
        val ciphertextBase64: String
    ) : WireFrame()

    data class DeleteMessage(
        val senderId: String,
        val ivBase64: String,
        val ciphertextBase64: String
    ) : WireFrame()

    data class MessageDelivered(
        val senderId: String,
        val ivBase64: String,
        val ciphertextBase64: String
    ) : WireFrame()

    data class MessageSeen(
        val senderId: String,
        val ivBase64: String,
        val ciphertextBase64: String
    ) : WireFrame()

    data class PhotoPreview(
        val transferId: String,
        val senderId: String,
        val ivBase64: String,
        val ciphertextBase64: String
    ) : WireFrame()

    data class PhotoDownloadRequest(
        val transferId: String,
        val senderId: String
    ) : WireFrame()

    data class WifiApReady(
        val transferId: String,
        val senderId: String,
        val ssid: String,
        val passphrase: String,
        val port: Int,
        val hostIp: String = "",
        val isReceiverFallbackHost: Boolean = false
    ) : WireFrame()

    data class WifiApFallback(
        val transferId: String,
        val senderId: String,
        val reason: String
    ) : WireFrame()

    data class PhotoTransferDone(
        val transferId: String,
        val senderId: String,
        val success: Boolean
    ) : WireFrame()
}

object WireFrameCodec {

    val VALID_FRAME_PREFIXES: Set<String> = setOf(
        "KEY_EXCHANGE",
        "CONNECTION_REQUEST",
        "CONNECTION_ACCEPT",
        "CONNECTION_DECLINE",
        "PROFILE_SYNC",
        "PEER_BLOCK_STATE",
        "CHAT_ENCRYPTED",
        "DELETE_MESSAGE",
        "MESSAGE_DELIVERED",
        "MESSAGE_SEEN",
        "PHOTO_PREVIEW",
        "PHOTO_DOWNLOAD_REQUEST",
        "WIFI_AP_READY",
        "WIFI_AP_FALLBACK",
        "PHOTO_TRANSFER_DONE"
    )

    fun isValidOutboundFrame(text: String): Boolean {
        val frameType = text.substringBefore("|")
        if (frameType !in VALID_FRAME_PREFIXES) return false
        return decodeFrame(text) != null
    }

    fun encodeKeyExchange(
        senderId: String,
        publicKeyBase64: String,
        algorithm: String,
        isResponse: Boolean = false,
        isReset: Boolean = false
    ): String {
        val respFlag = if (isResponse) "1" else "0"
        return if (isReset) {
            "KEY_EXCHANGE|$senderId|$publicKeyBase64|$algorithm|$respFlag|1"
        } else {
            "KEY_EXCHANGE|$senderId|$publicKeyBase64|$algorithm|$respFlag"
        }
    }

    fun encodeConnectionRequest(
        senderId: String,
        displayName: String,
        gender: GenderOption? = null,
        age: Int? = null
    ): String {
        val safeName = displayName.replace("|", " ")
        val genderPart = gender?.name ?: ""
        val agePart = age?.toString() ?: ""
        return "CONNECTION_REQUEST|$senderId|$safeName|$genderPart|$agePart"
    }

    fun encodeConnectionAccept(senderId: String): String {
        return "CONNECTION_ACCEPT|$senderId"
    }

    fun encodeConnectionDecline(senderId: String): String {
        return "CONNECTION_DECLINE|$senderId"
    }

    fun encodeProfileSync(
        senderId: String,
        displayName: String,
        gender: GenderOption,
        age: Int?,
        compactAvatarBytes: ByteArray?
    ): String {
        val safeName = displayName.replace("|", " ")
        val agePart = age?.toString() ?: ""
        val avatarB64 = if (compactAvatarBytes != null && compactAvatarBytes.isNotEmpty()) {
            Base64.getEncoder().encodeToString(compactAvatarBytes)
        } else {
            ""
        }
        return "PROFILE_SYNC|$senderId|$safeName|${gender.name}|$agePart|$avatarB64"
    }

    fun encodePeerBlockState(senderId: String, blocked: Boolean): String {
        val flag = if (blocked) "1" else "0"
        return "PEER_BLOCK_STATE|$senderId|$flag"
    }

    fun encodeChatEncrypted(
        msgId: String,
        senderId: String,
        timestamp: Long,
        payload: EncryptedPayload
    ): String {
        val ivB64 = Base64.getEncoder().encodeToString(payload.iv)
        val cipherB64 = Base64.getEncoder().encodeToString(payload.ciphertext)
        return "CHAT_ENCRYPTED|$msgId|$senderId|$timestamp|$ivB64|$cipherB64"
    }

    fun encodeDeleteMessage(
        senderId: String,
        encryptedMsgIdPayload: EncryptedPayload
    ): String {
        val ivB64 = Base64.getEncoder().encodeToString(encryptedMsgIdPayload.iv)
        val cipherB64 = Base64.getEncoder().encodeToString(encryptedMsgIdPayload.ciphertext)
        return "DELETE_MESSAGE|$senderId|$ivB64|$cipherB64"
    }

    fun encodeMessageDelivered(
        senderId: String,
        encryptedMsgIdsPayload: EncryptedPayload
    ): String {
        val ivB64 = Base64.getEncoder().encodeToString(encryptedMsgIdsPayload.iv)
        val cipherB64 = Base64.getEncoder().encodeToString(encryptedMsgIdsPayload.ciphertext)
        return "MESSAGE_DELIVERED|$senderId|$ivB64|$cipherB64"
    }

    fun encodeMessageSeen(
        senderId: String,
        encryptedMsgIdsPayload: EncryptedPayload
    ): String {
        val ivB64 = Base64.getEncoder().encodeToString(encryptedMsgIdsPayload.iv)
        val cipherB64 = Base64.getEncoder().encodeToString(encryptedMsgIdsPayload.ciphertext)
        return "MESSAGE_SEEN|$senderId|$ivB64|$cipherB64"
    }

    fun encodePhotoPreview(
        transferId: String,
        senderId: String,
        encryptedPreviewBundle: EncryptedPayload
    ): String {
        val ivB64 = Base64.getEncoder().encodeToString(encryptedPreviewBundle.iv)
        val cipherB64 = Base64.getEncoder().encodeToString(encryptedPreviewBundle.ciphertext)
        return "PHOTO_PREVIEW|$transferId|$senderId|$ivB64|$cipherB64"
    }

    fun encodePhotoDownloadRequest(
        transferId: String,
        senderId: String
    ): String {
        return "PHOTO_DOWNLOAD_REQUEST|$transferId|$senderId"
    }

    fun encodeWifiApReady(
        transferId: String,
        senderId: String,
        ssid: String,
        passphrase: String,
        port: Int,
        hostIp: String = "",
        isReceiverFallbackHost: Boolean = false
    ): String {
        val ssidB64 = Base64.getEncoder().encodeToString(ssid.toByteArray(Charsets.UTF_8))
        val passB64 = Base64.getEncoder().encodeToString(passphrase.toByteArray(Charsets.UTF_8))
        val safeIp = hostIp.replace("|", "")
        val fallbackFlag = if (isReceiverFallbackHost) "1" else "0"
        return "WIFI_AP_READY|$transferId|$senderId|$ssidB64|$passB64|$port|$safeIp|$fallbackFlag"
    }

    fun encodeWifiApFallback(
        transferId: String,
        senderId: String,
        reason: String
    ): String {
        val safeReason = reason.replace("|", " ").take(80)
        return "WIFI_AP_FALLBACK|$transferId|$senderId|$safeReason"
    }

    fun encodePhotoTransferDone(
        transferId: String,
        senderId: String,
        success: Boolean
    ): String {
        val flag = if (success) "1" else "0"
        return "PHOTO_TRANSFER_DONE|$transferId|$senderId|$flag"
    }

    fun decodeFrame(text: String): WireFrame? {
        val parts = text.split("|")
        if (parts.isEmpty()) return null

        return when (parts[0]) {
            "KEY_EXCHANGE" -> {
                if (parts.size >= 4) {
                    val isResponse = parts.getOrNull(4)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
                    val isReset = parts.getOrNull(5)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
                    WireFrame.KeyExchange(
                        senderId = parts[1],
                        publicKeyBase64 = parts[2],
                        algorithm = parts[3],
                        isResponse = isResponse,
                        isReset = isReset
                    )
                } else null
            }

            "CONNECTION_REQUEST" -> {
                if (parts.size >= 3) {
                    val gender = parts.getOrNull(3)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { GenderOption.fromLabelOrName(it) }
                    val age = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    WireFrame.ConnectionRequest(
                        senderId = parts[1],
                        displayName = parts[2],
                        gender = gender,
                        age = age
                    )
                } else null
            }

            "CONNECTION_ACCEPT" -> {
                if (parts.size >= 2) {
                    WireFrame.ConnectionAccept(
                        senderId = parts[1]
                    )
                } else null
            }

            "CONNECTION_DECLINE" -> {
                if (parts.size >= 2) {
                    WireFrame.ConnectionDecline(
                        senderId = parts[1]
                    )
                } else null
            }

            "PROFILE_SYNC" -> {
                if (parts.size >= 5) {
                    val gender = GenderOption.fromLabelOrName(parts.getOrNull(3))
                    val age = parts.getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    val avatarB64 = parts.getOrNull(5)?.takeIf { it.isNotBlank() }
                    WireFrame.ProfileSync(
                        senderId = parts[1],
                        displayName = parts[2],
                        gender = gender,
                        age = age,
                        compactAvatarBase64 = avatarB64
                    )
                } else null
            }

            "PEER_BLOCK_STATE" -> {
                if (parts.size >= 3) {
                    val blocked = parts[2] == "1" || parts[2].equals("true", ignoreCase = true)
                    WireFrame.PeerBlockState(
                        senderId = parts[1],
                        blocked = blocked
                    )
                } else null
            }

            "CHAT_ENCRYPTED" -> {
                if (parts.size >= 6) {
                    WireFrame.ChatEncrypted(
                        msgId = parts[1],
                        senderId = parts[2],
                        timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                        ivBase64 = parts[4],
                        ciphertextBase64 = parts[5]
                    )
                } else null
            }

            "DELETE_MESSAGE" -> {
                if (parts.size >= 4 && parts[1].isNotBlank() && parts[2].isNotBlank() && parts[3].isNotBlank()) {
                    WireFrame.DeleteMessage(
                        senderId = parts[1],
                        ivBase64 = parts[2],
                        ciphertextBase64 = parts[3]
                    )
                } else null
            }

            "MESSAGE_DELIVERED" -> {
                if (parts.size >= 4 && parts[1].isNotBlank() && parts[2].isNotBlank() && parts[3].isNotBlank()) {
                    WireFrame.MessageDelivered(
                        senderId = parts[1],
                        ivBase64 = parts[2],
                        ciphertextBase64 = parts[3]
                    )
                } else null
            }

            "MESSAGE_SEEN" -> {
                if (parts.size >= 4 && parts[1].isNotBlank() && parts[2].isNotBlank() && parts[3].isNotBlank()) {
                    WireFrame.MessageSeen(
                        senderId = parts[1],
                        ivBase64 = parts[2],
                        ciphertextBase64 = parts[3]
                    )
                } else null
            }

            "PHOTO_PREVIEW" -> {
                if (parts.size >= 5) {
                    WireFrame.PhotoPreview(
                        transferId = parts[1],
                        senderId = parts[2],
                        ivBase64 = parts[3],
                        ciphertextBase64 = parts[4]
                    )
                } else null
            }

            "PHOTO_DOWNLOAD_REQUEST" -> {
                if (parts.size >= 3) {
                    WireFrame.PhotoDownloadRequest(
                        transferId = parts[1],
                        senderId = parts[2]
                    )
                } else null
            }

            "WIFI_AP_READY" -> {
                if (parts.size >= 6) {
                    val ssid = runCatching {
                        String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8)
                    }.getOrNull() ?: return null
                    val pass = runCatching {
                        String(Base64.getDecoder().decode(parts[4]), Charsets.UTF_8)
                    }.getOrNull() ?: return null
                    val port = parts[5].toIntOrNull() ?: return null
                    val hostIp = parts.getOrNull(6) ?: ""
                    val isFallback = parts.getOrNull(7)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
                    WireFrame.WifiApReady(
                        transferId = parts[1],
                        senderId = parts[2],
                        ssid = ssid,
                        passphrase = pass,
                        port = port,
                        hostIp = hostIp,
                        isReceiverFallbackHost = isFallback
                    )
                } else null
            }

            "WIFI_AP_FALLBACK" -> {
                if (parts.size >= 3) {
                    WireFrame.WifiApFallback(
                        transferId = parts[1],
                        senderId = parts[2],
                        reason = parts.getOrNull(3) ?: ""
                    )
                } else null
            }

            "PHOTO_TRANSFER_DONE" -> {
                if (parts.size >= 4) {
                    val success = parts[3] == "1" || parts[3].equals("true", ignoreCase = true)
                    WireFrame.PhotoTransferDone(
                        transferId = parts[1],
                        senderId = parts[2],
                        success = success
                    )
                } else null
            }

            else -> null
        }
    }
}
