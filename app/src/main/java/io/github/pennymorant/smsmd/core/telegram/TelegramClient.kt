package io.github.pennymorant.smsmd.core.telegram

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

data class TelegramChat(
    val id: String,
    val title: String,
    val type: String,
)

class TelegramApiException(
    message: String,
    val permanent: Boolean,
    val retryAfterSeconds: Int? = null,
) : IOException(message)

class TelegramClient {
    fun sendSms(token: String, chatId: String, sender: String, body: String, receivedAt: Long) {
        val message = smsText(sender, body, receivedAt)
        splitMessage(message).forEach { part -> sendMessage(token, chatId, part) }
    }

    fun sendTest(token: String, chatId: String) {
        request(token, "getMe", JSONObject())
        sendMessage(
            token,
            chatId,
            "SMSmd Telegram test\n\nYour Telegram forwarding is ready to use.",
        )
    }

    fun recentChats(token: String): List<TelegramChat> {
        val result = request(token, "getUpdates", JSONObject().put("limit", 100))
        val chats = linkedMapOf<String, TelegramChat>()
        for (index in 0 until result.length()) {
            val update = result.optJSONObject(index) ?: continue
            val message = update.optJSONObject("message")
                ?: update.optJSONObject("channel_post")
                ?: update.optJSONObject("edited_message")
                ?: continue
            val chat = message.optJSONObject("chat") ?: continue
            val id = chat.optLong("id").toString()
            val type = chat.optString("type", "chat")
            val title = chat.optString("title").ifBlank {
                listOf(chat.optString("first_name"), chat.optString("last_name"))
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                    .ifBlank { chat.optString("username").ifBlank { id } }
            }
            chats[id] = TelegramChat(id, title, type)
        }
        return chats.values.toList().asReversed()
    }

    private fun sendMessage(token: String, chatId: String, text: String) {
        require(chatId.isNotBlank()) { "Chat ID is required" }
        request(
            token,
            "sendMessage",
            JSONObject()
                .put("chat_id", chatId.trim())
                .put("text", text),
        )
    }

    private fun request(token: String, method: String, body: JSONObject): org.json.JSONArray {
        require(TOKEN_PATTERN.matches(token.trim())) { "Bot token is invalid" }
        val connection = URL("https://api.telegram.org/bot${token.trim()}/$method")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val response = runCatching { JSONObject(responseText) }.getOrElse {
                throw TelegramApiException("Telegram returned an invalid response", status in 400..499)
            }
            if (!response.optBoolean("ok")) {
                val retryAfter = response.optJSONObject("parameters")?.optInt("retry_after")
                    ?.takeIf { it > 0 }
                val description = response.optString("description").ifBlank { "Telegram request failed" }
                throw TelegramApiException(
                    message = description.removePrefix("Bad Request: ").take(180),
                    permanent = status in 400..499 && status != 408 && status != 429,
                    retryAfterSeconds = retryAfter,
                )
            }
            response.optJSONArray("result") ?: org.json.JSONArray()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val MAX_MESSAGE_CODE_POINTS = 4096
        private val TOKEN_PATTERN = Regex("^[0-9]{6,}:[A-Za-z0-9_-]{20,}$")

        internal fun smsText(sender: String, body: String, receivedAt: Long): String {
            val received = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(receivedAt))
            return "New SMS\n\nFrom: $sender\nReceived: $received\n\n$body"
        }

        internal fun splitMessage(message: String): List<String> {
            if (message.codePointCount(0, message.length) <= MAX_MESSAGE_CODE_POINTS) return listOf(message)
            val payloadSize = MAX_MESSAGE_CODE_POINTS - 16
            val rawParts = mutableListOf<String>()
            var start = 0
            while (start < message.length) {
                val remaining = message.codePointCount(start, message.length)
                val count = minOf(payloadSize, remaining)
                val end = message.offsetByCodePoints(start, count)
                rawParts += message.substring(start, end)
                start = end
            }
            return rawParts.mapIndexed { index, part -> "Part ${index + 1}/${rawParts.size}\n\n$part" }
        }

        fun safeError(error: Throwable): String = when (error) {
            is TelegramApiException -> error.message ?: "Telegram request failed"
            is IllegalArgumentException -> error.message ?: "Invalid Telegram configuration"
            else -> "Telegram network error"
        }
    }
}
