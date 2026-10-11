package com.example.smsmd.core.database

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

enum class JobState { QUEUED, SENDING, RETRYING, SENT, FAILED }

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
)
