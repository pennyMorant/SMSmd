package io.github.pennymorant.smsmd.core.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.ColumnInfo

enum class JobState { QUEUED, SENDING, RETRYING, SENT, FAILED }

enum class ChannelState { PENDING, SENT, FAILED, SKIPPED }

@Entity(
    tableName = "forward_jobs",
    indices = [Index(value = ["fingerprint"], unique = true)],
)
data class ForwardJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val encryptedSender: String,
    val encryptedBody: String,
    val receivedAt: Long,
    val subscriptionId: Int,
    val state: String = JobState.QUEUED.name,
    val attemptCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val smtpState: String = ChannelState.PENDING.name,
    @ColumnInfo(defaultValue = "'SKIPPED'") val telegramState: String = ChannelState.SKIPPED.name,
)

@Entity(tableName = "delivery_logs")
data class DeliveryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val maskedSender: String,
    val receivedAt: Long,
    val completedAt: Long = System.currentTimeMillis(),
    val result: String,
    val attemptCount: Int,
    val errorSummary: String? = null,
    @ColumnInfo(defaultValue = "'Email sent'") val channelSummary: String = "Email sent",
)
