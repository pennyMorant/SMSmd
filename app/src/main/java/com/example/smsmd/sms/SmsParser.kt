package com.example.smsmd.sms

import android.content.Intent
import android.provider.Telephony

data class IncomingSms(
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val subscriptionId: Int,
)

object SmsParser {
    fun parse(intent: Intent): IncomingSms? {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return null
        val sender = messages.firstNotNullOfOrNull {
            it.displayOriginatingAddress ?: it.originatingAddress
        }.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        if (sender.isBlank() || body.isBlank()) return null
        return IncomingSms(
            sender = sender,
            body = body,
            receivedAt = messages.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis(),
            subscriptionId = intent.getIntExtra("subscription", -1),
        )
    }
}
