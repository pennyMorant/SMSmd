package io.github.pennymorant.smsmd.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: ForwardJobEntity): Long

    @Query("SELECT * FROM forward_jobs WHERE id = :id")
    suspend fun getJob(id: Long): ForwardJobEntity?

    @Query("UPDATE forward_jobs SET state = :state, attemptCount = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateJob(id: Long, state: String, attempts: Int, error: String? = null)

    @Query("UPDATE forward_jobs SET smtpState = :smtpState, telegramState = :telegramState WHERE id = :id")
    suspend fun updateChannelStates(id: Long, smtpState: String, telegramState: String)

    @Query("UPDATE forward_jobs SET telegramPartIndex = :partIndex WHERE id = :id")
    suspend fun updateTelegramPartIndex(id: Long, partIndex: Int)

    @Query("DELETE FROM forward_jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("SELECT COUNT(*) FROM forward_jobs WHERE state IN ('QUEUED', 'SENDING', 'RETRYING')")
    fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM forward_jobs WHERE state IN ('QUEUED', 'SENDING', 'RETRYING') ORDER BY createdAt")
    suspend fun pendingJobs(): List<ForwardJobEntity>

    @Insert
    suspend fun insertLog(log: DeliveryLogEntity)

    @Query("SELECT * FROM delivery_logs ORDER BY completedAt DESC LIMIT 1")
    fun observeLastDelivery(): Flow<DeliveryLogEntity?>

    @Query("DELETE FROM delivery_logs")
    suspend fun clearLogs()

    @Query("INSERT OR IGNORE INTO delivery_stats (id, forwardedCount) VALUES (1, 0)")
    suspend fun ensureDeliveryStats()

    @Query("UPDATE delivery_stats SET forwardedCount = forwardedCount + 1 WHERE id = 1")
    suspend fun incrementForwardedCount()

    @Query("SELECT COALESCE((SELECT forwardedCount FROM delivery_stats WHERE id = 1), 0)")
    suspend fun forwardedCount(): Long

    @Query("SELECT COALESCE((SELECT forwardedCount FROM delivery_stats WHERE id = 1), 0)")
    fun observeForwardedCount(): Flow<Long>

    @Transaction
    suspend fun initializeForwardedCount(): Long {
        ensureDeliveryStats()
        return forwardedCount()
    }

    @Transaction
    suspend fun completeJob(
        log: DeliveryLogEntity,
        jobId: Long,
        countAsForwarded: Boolean,
    ): Long {
        ensureDeliveryStats()
        clearLogs()
        insertLog(log)
        deleteJob(jobId)
        if (countAsForwarded) incrementForwardedCount()
        return forwardedCount()
    }

    @Query("DELETE FROM delivery_logs WHERE id NOT IN (SELECT id FROM delivery_logs ORDER BY completedAt DESC LIMIT 1)")
    suspend fun trimLogsToLatest()

    @Query("DELETE FROM forward_jobs WHERE state = 'FAILED'")
    suspend fun deleteFailedJobs()
}
