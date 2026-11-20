package com.intj.layover.p2p

import android.util.Base64
import com.intj.layover.crypto.EncryptedPayload

sealed class WireFrame {
    data class KeyExchange(
        val senderId: String,
        val publicKeyBase64: String,
        val algorithm: String
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

    fun encodeKeyExchange(senderId: String, publicKeyBase64: String, algorithm: String): String {
        return "KEY_EXCHANGE|$senderId|$publicKeyBase64|$algorithm"
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
                    WireFrame.KeyExchange(
                        senderId = parts[1],
                        publicKeyBase64 = parts[2],
                        algorithm = parts[3]
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
