package com.intj.layover

import com.intj.layover.ble.LayoverAppRuntime
import com.intj.layover.model.ChatMessage
import com.intj.layover.ui.screens.formatMessageBubbleTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ChatMessageTimestampTest {

    private val testZone = ZoneId.of("Asia/Kolkata")
    private val testLocale = Locale.US

    private fun epochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, testZone)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun formatMessageBubbleTimestamp_returnsTimeOnlyForTodayWithin24Hours() {
        val nowMillis = epochMillis(2026, 11, 28, 13, 35)
        val justArrivedMillis = epochMillis(2026, 11, 28, 13, 35)
        val earlierTodayMillis = epochMillis(2026, 11, 28, 9, 5)

        val formattedNow = formatMessageBubbleTimestamp(
            timestampMillis = justArrivedMillis,
            nowMillis = nowMillis,
            zoneId = testZone,
            locale = testLocale
        )
        val formattedEarlier = formatMessageBubbleTimestamp(
            timestampMillis = earlierTodayMillis,
            nowMillis = nowMillis,
            zoneId = testZone,
            locale = testLocale
        )

        assertEquals("1:35 PM", formattedNow)
        assertEquals("9:05 AM", formattedEarlier)
        assertFalse(formattedNow.contains("Just now", ignoreCase = true))
    }

    @Test
    fun formatMessageBubbleTimestamp_returnsDateAndTimeForPreviousDayOrOlderThan24Hours() {
        val nowMillis = epochMillis(2026, 11, 28, 13, 35)
        val yesterdaySameTimeMillis = epochMillis(2026, 11, 27, 13, 35)
        val yesterdayLateNightMillis = epochMillis(2026, 11, 27, 23, 50)
        val fiveDaysAgoMillis = epochMillis(2026, 11, 23, 8, 15)

        assertEquals(
            "27 Nov, 1:35 PM",
            formatMessageBubbleTimestamp(
                timestampMillis = yesterdaySameTimeMillis,
                nowMillis = nowMillis,
                zoneId = testZone,
                locale = testLocale
            )
        )
        assertEquals(
            "27 Nov, 11:50 PM",
            formatMessageBubbleTimestamp(
                timestampMillis = yesterdayLateNightMillis,
                nowMillis = nowMillis,
                zoneId = testZone,
                locale = testLocale
            )
        )
        assertEquals(
            "23 Nov, 8:15 AM",
            formatMessageBubbleTimestamp(
                timestampMillis = fiveDaysAgoMillis,
                nowMillis = nowMillis,
                zoneId = testZone,
                locale = testLocale
            )
        )
    }

    @Test
    fun formatMessageBubbleTimestamp_guardsInvalidOrZeroTimestampsWithoutReturningJustNow() {
        val nowMillis = epochMillis(2026, 11, 28, 13, 35)

        val zeroFormatted = formatMessageBubbleTimestamp(
            timestampMillis = 0L,
            nowMillis = nowMillis,
            zoneId = testZone,
            locale = testLocale
        )
        val negativeFormatted = formatMessageBubbleTimestamp(
            timestampMillis = -5_000L,
            nowMillis = nowMillis,
            zoneId = testZone,
            locale = testLocale
        )

        assertEquals("1:35 PM", zeroFormatted)
        assertEquals("1:35 PM", negativeFormatted)
        assertFalse(zeroFormatted.equals("Just now", ignoreCase = true))
    }

    @Test
    fun chatHistorySerialization_preservesOriginalTimestampMillisAcrossRestarts() {
        val yesterdayMillis = epochMillis(2026, 11, 27, 13, 35)
        val todayMillis = epochMillis(2026, 11, 28, 13, 35)

        val original = mapOf(
            "dev_peer_01" to listOf(
                ChatMessage(
                    id = "msg_old",
                    senderId = "dev_peer_01",
                    text = "See you at gate B4!",
                    isOutgoing = false,
                    timestampMillis = yesterdayMillis
                ),
                ChatMessage(
                    id = "msg_new",
                    senderId = "user",
                    text = "On my way",
                    isOutgoing = true,
                    replyToText = "See you at gate B4!",
                    timestampMillis = todayMillis
                )
            )
        )

        val encoded = LayoverAppRuntime.serializeChatHistory(original)
        val restored = LayoverAppRuntime.deserializeChatHistory(encoded, fallbackNowMillis = todayMillis)

        assertTrue(restored.containsKey("dev_peer_01"))
        val restoredMessages = restored["dev_peer_01"]!!
        assertEquals(2, restoredMessages.size)
        assertEquals(yesterdayMillis, restoredMessages[0].timestampMillis)
        assertEquals("See you at gate B4!", restoredMessages[0].text)
        assertFalse(restoredMessages[0].isOutgoing)
        assertEquals(todayMillis, restoredMessages[1].timestampMillis)
        assertEquals("On my way", restoredMessages[1].text)
        assertEquals("See you at gate B4!", restoredMessages[1].replyToText)
        assertTrue(restoredMessages[1].isOutgoing)
    }
}
