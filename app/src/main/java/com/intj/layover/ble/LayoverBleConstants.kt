package com.intj.layover.ble

import android.os.ParcelUuid
import com.intj.layover.model.GenderOption
import java.util.UUID

object LayoverBleConstants {
    const val LAYOVER_SERVICE_UUID_STRING = "7f41a0d8-5526-4b6a-9f5b-6147e4b2a8d1"
    val LAYOVER_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(LAYOVER_SERVICE_UUID_STRING)

    const val PEER_STALE_TIMEOUT_MS = 60_000L
    // Matches the 7-char max username limit so age + name always fit in the 13B legacy scan-response budget
    const val MAX_NAME_BYTE_LENGTH = 7

    // Legacy BLE 4.x advertising / scan response byte budget constants
    const val LEGACY_BLE_MAX_PACKET_BYTES = 31
    const val LEGACY_BLE_FLAGS_BYTES = 3
    const val LEGACY_BLE_AD_HEADER_BYTES = 2
    const val UUID_128_BIT_BYTES = 16
    const val SERVICE_UUID_128_AD_BYTES = LEGACY_BLE_AD_HEADER_BYTES + UUID_128_BIT_BYTES // 18 bytes
    const val PRIMARY_ADVERTISE_BYTES = LEGACY_BLE_FLAGS_BYTES + SERVICE_UUID_128_AD_BYTES // 21 bytes
    // 128-bit Service Data AD header = 2 (len + type) + 16 (UUID) = 18 bytes -> 13 bytes remain in a 31-byte scan response
    const val MAX_SERVICE_DATA_PAYLOAD_BYTES = LEGACY_BLE_MAX_PACKET_BYTES - SERVICE_UUID_128_AD_BYTES // 13 bytes
    // Compact fallback budget (28 bytes total) for OEM BLE stacks that reserve 3 flags bytes in scan responses
    const val COMPACT_SERVICE_DATA_PAYLOAD_BYTES = MAX_SERVICE_DATA_PAYLOAD_BYTES - LEGACY_BLE_FLAGS_BYTES // 10 bytes

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
    private const val FLAG_VISIBLE = 0x01
    private const val FLAG_HAS_AGE = 0x02
    private const val FLAG_HAS_GENDER = 0x04
    private const val FLAG_GENDER_FEMALE = 0x08
    private const val MIN_PAYLOAD_HEADER_BYTES = 5 // 1 byte flags + 4 bytes instanceId

    data class DecodedPayload(
        val instanceIdHex: String,
        val displayName: String,
        val isVisible: Boolean,
        val age: Int? = null,
        val gender: GenderOption? = null,
    )

    fun calculatePrimaryAdvertiseBytes(includeFlags: Boolean = true): Int {
        return (if (includeFlags) LayoverBleConstants.LEGACY_BLE_FLAGS_BYTES else 0) +
            LayoverBleConstants.SERVICE_UUID_128_AD_BYTES
    }

    fun calculateScanResponseBytes(serviceDataPayloadBytes: Int): Int {
        if (serviceDataPayloadBytes <= 0) return 0
        return LayoverBleConstants.SERVICE_UUID_128_AD_BYTES + serviceDataPayloadBytes
    }

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

    internal fun clampUtf8ByteLength(utf8Bytes: ByteArray, maxBytes: Int): Int {
        if (maxBytes <= 0) return 0
        if (utf8Bytes.size <= maxBytes) return utf8Bytes.size
        var end = maxBytes
        // If cutting in the middle of a multi-byte UTF-8 sequence (next byte is a continuation byte 10xxxxxx),
        // walk back past all continuation bytes and drop the incomplete leading byte as well.
        if ((utf8Bytes[end].toInt() and 0xC0) == 0x80) {
            while (end > 0 && (utf8Bytes[end - 1].toInt() and 0xC0) == 0x80) {
                end--
            }
            if (end > 0) {
                end--
            }
        }
        return end
    }

    fun encodePayload(
        displayName: String,
        isVisible: Boolean,
        handleOrDeviceId: String = "",
        age: Int? = null,
        gender: GenderOption? = null,
        maxPayloadBytes: Int = LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES
    ): ByteArray {
        val safeMaxPayload = maxPayloadBytes
            .coerceAtLeast(MIN_PAYLOAD_HEADER_BYTES)
            .coerceAtMost(LayoverBleConstants.MAX_SERVICE_DATA_PAYLOAD_BYTES)
        val hasAge = (age != null) && (age in 1..255) && (safeMaxPayload >= MIN_PAYLOAD_HEADER_BYTES + 1)
        val hasGender = gender != null
        val isFemale = gender == GenderOption.FEMALE
        val flags = (
            (if (isVisible) FLAG_VISIBLE else 0x00) or
                (if (hasAge) FLAG_HAS_AGE else 0x00) or
                (if (hasGender) FLAG_HAS_GENDER else 0x00) or
                (if (isFemale) FLAG_GENDER_FEMALE else 0x00)
            ).toByte()

        val instanceIdBytes = if (handleOrDeviceId.isNotEmpty()) {
            getInstanceIdBytes(handleOrDeviceId)
        } else {
            LayoverBleConstants.LOCAL_INSTANCE_ID_BYTES
        }

        val ageByteSize = if (hasAge) 1 else 0
        val headerSize = MIN_PAYLOAD_HEADER_BYTES + ageByteSize
        val maxNameBytes = (safeMaxPayload - headerSize)
            .coerceAtMost(LayoverBleConstants.MAX_NAME_BYTE_LENGTH)
            .coerceAtLeast(0)

        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val nameLen = clampUtf8ByteLength(nameBytes, maxNameBytes)

        val payload = ByteArray(headerSize + nameLen)
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
        if (data == null || data.size < MIN_PAYLOAD_HEADER_BYTES) return null
        val flagsInt = data[0].toInt() and 0xFF
        val isVisible = (flagsInt and FLAG_VISIBLE) != 0
        val hasAgeFlag = (flagsInt and FLAG_HAS_AGE) != 0
        val hasGenderFlag = (flagsInt and FLAG_HAS_GENDER) != 0
        val isFemaleFlag = (flagsInt and FLAG_GENDER_FEMALE) != 0

        val instanceIdBytes = ByteArray(4)
        System.arraycopy(data, 1, instanceIdBytes, 0, 4)
        val instanceIdHex = instanceIdBytes.joinToString("") { "%02X".format(it) }

        var offset = 5
        val decodedAge: Int? = if (hasAgeFlag && data.size > offset) {
            val rawAge = data[offset].toInt() and 0xFF
            offset += 1
            rawAge.takeIf { it > 0 }
        } else {
            null
        }

        val decodedGender: GenderOption? = if (hasGenderFlag) {
            if (isFemaleFlag) GenderOption.FEMALE else GenderOption.MALE
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
            age = decodedAge,
            gender = decodedGender
        )
    }
}
