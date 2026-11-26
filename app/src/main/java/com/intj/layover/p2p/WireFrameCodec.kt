package com.intj.layover.p2p

import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload
import com.intj.layover.model.GenderOption

sealed class WireFrame {
    data class KeyExchange(
        val senderId: String,
        val publicKeyBase64: String,
        val algorithm: String,
        val isResponse: Boolean = false
    ) : WireFrame()

    data class ConnectionRequest(
        val senderId: String,
        val displayName: String,
        val gender: GenderOption = GenderOption.MALE,
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
}

object WireFrameCodec {

    fun encodeKeyExchange(
        senderId: String,
        publicKeyBase64: String,
        algorithm: String,
        isResponse: Boolean = false
    ): String {
        val respFlag = if (isResponse) "1" else "0"
        return "KEY_EXCHANGE|$senderId|$publicKeyBase64|$algorithm|$respFlag"
    }

    fun encodeConnectionRequest(
        senderId: String,
        displayName: String,
        gender: GenderOption = GenderOption.MALE,
        age: Int? = null
    ): String {
        val safeName = displayName.replace("|", " ")
        val agePart = age?.toString() ?: ""
        return "CONNECTION_REQUEST|$senderId|$safeName|${gender.name}|$agePart"
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
            Base64.encodeToString(compactAvatarBytes, Base64.NO_WRAP)
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
        val ivB64 = Base64.encodeToString(payload.iv, Base64.NO_WRAP)
        val cipherB64 = Base64.encodeToString(payload.ciphertext, Base64.NO_WRAP)
        return "CHAT_ENCRYPTED|$msgId|$senderId|$timestamp|$ivB64|$cipherB64"
    }

    fun decodeFrame(text: String): WireFrame? {
        val parts = text.split("|")
        if (parts.isEmpty()) return null

        return when (parts[0]) {
            "KEY_EXCHANGE" -> {
                if (parts.size >= 4) {
                    val isResponse = parts.getOrNull(4)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
                    WireFrame.KeyExchange(
                        senderId = parts[1],
                        publicKeyBase64 = parts[2],
                        algorithm = parts[3],
                        isResponse = isResponse
                    )
                } else null
            }

            "CONNECTION_REQUEST" -> {
                if (parts.size >= 3) {
                    val gender = GenderOption.fromLabelOrName(parts.getOrNull(3))
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

            else -> null
        }
    }
}
