package com.intj.layover.ble

import android.os.ParcelUuid

object LayoverBleConstants {
    const val LAYOVER_SERVICE_UUID_STRING = "7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1"
    val LAYOVER_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(LAYOVER_SERVICE_UUID_STRING)

    const val PEER_STALE_TIMEOUT_MS = 60_000L
    const val MAX_NAME_BYTE_LENGTH = 8
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
        val shortIdBytes = ByteArray(4)
        val idCopyLen = peerIdBytes.size.coerceAtMost(4)
        System.arraycopy(peerIdBytes, 0, shortIdBytes, 0, idCopyLen)

        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        var nameLen = nameBytes.size.coerceAtMost(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        while (nameLen > 0 && (nameBytes[nameLen - 1].toInt() and 0xC0) == 0x80) {
            nameLen--
        }

        val payload = ByteArray(1 + 4 + nameLen)
        payload[0] = flags
        System.arraycopy(shortIdBytes, 0, payload, 1, 4)
        if (nameLen > 0) {
            System.arraycopy(nameBytes, 0, payload, 5, nameLen)
        }
        return payload
    }

    fun decodePayload(data: ByteArray?): DecodedPayload? {
        if (data == null || data.size < 5) return null
        val isVisible = (data[0].toInt() and 0x01) != 0
        val rawPeerId = String(data, 1, 4, Charsets.UTF_8).trim('\u0000', ' ')
        val peerId = if (rawPeerId.isNotBlank()) rawPeerId else "unknown"

        val nameLen = data.size - 5
        val rawName = if (nameLen > 0) {
            String(data, 5, nameLen, Charsets.UTF_8).trim('\u0000', ' ')
        } else {
            ""
        }

        return DecodedPayload(
            peerId = peerId,
            displayName = rawName,
            isVisible = isVisible
        )
    }
}
