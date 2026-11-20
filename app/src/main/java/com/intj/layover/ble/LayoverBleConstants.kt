package com.intj.layover.ble

import android.os.ParcelUuid

object LayoverBleConstants {
    const val LAYOVER_SERVICE_UUID_STRING = "7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1"
    val LAYOVER_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(LAYOVER_SERVICE_UUID_STRING)

    const val PEER_STALE_TIMEOUT_MS = 60_000L
    const val MAX_NAME_BYTE_LENGTH = 12
}

object LayoverBlePayloadEncoder {

    data class DecodedPayload(
        val peerId: String,
        val displayName: String,
        val isVisible: Boolean
    )

    fun encodePayload(peerId: String, displayName: String, isVisible: Boolean): ByteArray {
        val flags = if (isVisible) 0x01.toByte() else 0x00.toByte()

        val cleanPeerId = peerId.ifEmpty { "node_${System.currentTimeMillis() % 10000}" }
        val peerIdBytes = cleanPeerId.toByteArray(Charsets.UTF_8)
        val paddedIdBytes = ByteArray(6)
        val idCopyLen = peerIdBytes.size.coerceAtMost(6)
        System.arraycopy(peerIdBytes, 0, paddedIdBytes, 0, idCopyLen)

        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val truncatedNameBytes = if (nameBytes.size > LayoverBleConstants.MAX_NAME_BYTE_LENGTH) {
            nameBytes.copyOf(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        } else {
            nameBytes
        }

        val payload = ByteArray(1 + 6 + truncatedNameBytes.size)
        payload[0] = flags
        System.arraycopy(paddedIdBytes, 0, payload, 1, 6)
        System.arraycopy(truncatedNameBytes, 0, payload, 7, truncatedNameBytes.size)
        return payload
    }

    fun decodePayload(data: ByteArray?): DecodedPayload? {
        if (data == null || data.size < 7) return null
        val isVisible = (data[0].toInt() and 0x01) != 0
        val rawPeerId = String(data, 1, 6, Charsets.UTF_8).trim('\u0000', ' ')
        val peerId = if (rawPeerId.isNotBlank()) rawPeerId else "unknown"

        val nameLen = data.size - 7
        val rawName = if (nameLen > 0) {
            String(data, 7, nameLen, Charsets.UTF_8).trim('\u0000', ' ')
        } else {
            peerId
        }
        val displayName = if (rawName.isNotBlank()) rawName else "Nearby Peer"

        return DecodedPayload(
            peerId = peerId,
            displayName = displayName,
            isVisible = isVisible
        )
    }
}
