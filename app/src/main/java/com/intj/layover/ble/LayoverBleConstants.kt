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
        val isVisible: Boolean,
        val age: Int? = null
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

    fun encodePayload(
        displayName: String,
        isVisible: Boolean,
        handleOrDeviceId: String = "",
        age: Int? = null
    ): ByteArray {
        val hasAge = age != null && age in 1..255
        val flags = ((if (isVisible) 0x01 else 0x00) or (if (hasAge) 0x02 else 0x00)).toByte()

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

        val ageByteSize = if (hasAge) 1 else 0
        val payload = ByteArray(1 + 4 + ageByteSize + nameLen)
        payload[0] = flags
        System.arraycopy(instanceIdBytes, 0, payload, 1, 4)
        var offset = 5
        if (hasAge) {
            payload[offset] = age.toByte()
            offset += 1
        }
        if (nameLen > 0) {
            System.arraycopy(nameBytes, 0, payload, offset, nameLen)
        }
        return payload
    }

    fun decodePayload(data: ByteArray?): DecodedPayload? {
        if (data == null || data.size < 5) return null
        val flagsInt = data[0].toInt()
        val isVisible = (flagsInt and 0x01) != 0
        val hasAgeFlag = (flagsInt and 0x02) != 0

        val instanceIdBytes = ByteArray(4)
        System.arraycopy(data, 1, instanceIdBytes, 0, 4)
        val instanceIdHex = instanceIdBytes.joinToString("") { "%02X".format(it) }

        var offset = 5
        val decodedAge: Int? = if (hasAgeFlag && data.size > offset) {
            (data[offset].toInt() and 0xFF).takeIf { it > 0 }?.also { offset += 1 }
        } else {
            null
        }

        val nameLen = data.size - offset
        val rawName = if (nameLen > 0) {
            String(data, offset, nameLen, Charsets.UTF_8).trim('\u0000', ' ')
        } else {
            ""
        }

        val displayName = rawName.ifBlank { "Layover Peer ($instanceIdHex)" }

        return DecodedPayload(
            instanceIdHex = instanceIdHex,
            displayName = displayName,
            isVisible = isVisible,
            age = decodedAge
        )
    }
}
