package com.intj.layover.ble

import android.os.ParcelUuid
import java.util.UUID

object LayoverBleConstants {
    const val LAYOVER_SERVICE_UUID_STRING = "7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1"
    val LAYOVER_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(LAYOVER_SERVICE_UUID_STRING)

    const val PEER_STALE_TIMEOUT_MS = 60_000L
    const val MAX_NAME_BYTE_LENGTH = 8

    // Fallback 4-byte random instance ID generated once per app session
    val LOCAL_INSTANCE_ID_BYTES: ByteArray = ByteArray(4).apply {
        val randInt = UUID.randomUUID().mostSignificantBits.toInt()
        this[0] = (randInt ushr 24).toByte()
        this[1] = (randInt ushr 16).toByte()
        this[2] = (randInt ushr 8).toByte()
        this[3] = randInt.toByte()
    }
    val LOCAL_INSTANCE_ID_HEX: String = LOCAL_INSTANCE_ID_BYTES.joinToString("") { "%02X".format(it) }
}

object LayoverBlePayloadEncoder {

    data class DecodedPayload(
        val instanceIdHex: String,
        val displayName: String,
        val isVisible: Boolean
    )

    fun getInstanceIdBytes(handleOrDeviceId: String): ByteArray {
        val hash = handleOrDeviceId.hashCode()
        return byteArrayOf(
            (hash ushr 24).toByte(),
            (hash ushr 16).toByte(),
            (hash ushr 8).toByte(),
            hash.toByte()
        )
    }

    fun getInstanceIdHex(handleOrDeviceId: String): String {
        return getInstanceIdBytes(handleOrDeviceId).joinToString("") { "%02X".format(it) }
    }

    fun encodePayload(displayName: String, isVisible: Boolean, handleOrDeviceId: String = ""): ByteArray {
        val flags = if (isVisible) 0x01.toByte() else 0x00.toByte()

        val instanceIdBytes = if (handleOrDeviceId.isNotEmpty()) {
            getInstanceIdBytes(handleOrDeviceId)
        } else {
            LayoverBleConstants.LOCAL_INSTANCE_ID_BYTES
        }

        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        var nameLen = nameBytes.size.coerceAtMost(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
        while (nameLen > 0 && (nameBytes[nameLen - 1].toInt() and 0xC0) == 0x80) {
            nameLen--
        }

        val payload = ByteArray(1 + 4 + nameLen)
        payload[0] = flags
        System.arraycopy(instanceIdBytes, 0, payload, 1, 4)
        if (nameLen > 0) {
            System.arraycopy(nameBytes, 0, payload, 5, nameLen)
        }
        return payload
    }

    fun decodePayload(data: ByteArray?): DecodedPayload? {
        if (data == null || data.size < 5) return null
        val isVisible = (data[0].toInt() and 0x01) != 0

        val instanceIdBytes = ByteArray(4)
        System.arraycopy(data, 1, instanceIdBytes, 0, 4)
        val instanceIdHex = instanceIdBytes.joinToString("") { "%02X".format(it) }

        val nameLen = data.size - 5
        val rawName = if (nameLen > 0) {
            String(data, 5, nameLen, Charsets.UTF_8).trim('\u0000', ' ')
        } else {
            ""
        }

        val displayName = rawName.ifBlank { "Layover Peer ($instanceIdHex)" }

        return DecodedPayload(
            instanceIdHex = instanceIdHex,
            displayName = displayName,
            isVisible = isVisible
        )
    }
}
