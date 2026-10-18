package io.github.pennymorant.smsmd

import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramClientTest {
    @Test
    fun smsTextIncludesFullBodyWithoutExtractingCodes() {
        val text = TelegramClient.smsText("Bank", "Code 123456. Do not share.", 0L)

        assertTrue(text.contains("From: Bank"))
        assertTrue(text.endsWith("Code 123456. Do not share."))
    }

    @Test
    fun shortMessageIsNotSplit() {
        assertEquals(listOf("hello"), TelegramClient.splitMessage("hello"))
    }

    @Test
    fun longUnicodeMessageIsSplitWithinTelegramLimit() {
        val message = "A".repeat(4_090) + "😀".repeat(20)

        val parts = TelegramClient.splitMessage(message)

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.codePointCount(0, it.length) <= 4_096 })
        assertEquals(message, parts.joinToString("") { it.substringAfter("\n\n") })
    }
}
