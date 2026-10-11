package com.example.smsmd.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: ForwardJobEntity): Long

    @Query("SELECT * FROM forward_jobs WHERE id = :id")
    suspend fun getJob(id: Long): ForwardJobEntity?

    @Query("UPDATE forward_jobs SET state = :state, attemptCount = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateJob(id: Long, state: String, attempts: Int, error: String? = null)

    @Query("DELETE FROM forward_jobs WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("SELECT COUNT(*) FROM forward_jobs WHERE state IN ('QUEUED', 'SENDING', 'RETRYING')")
    fun pendingCount(): Flow<Int>

    @Insert
    suspend fun insertLog(log: DeliveryLogEntity)

    @Query("SELECT * FROM delivery_logs ORDER BY completedAt DESC LIMIT 1")
    fun observeLastDelivery(): Flow<DeliveryLogEntity?>

    @Query("DELETE FROM delivery_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM delivery_logs WHERE id NOT IN (SELECT id FROM delivery_logs ORDER BY completedAt DESC LIMIT 1)")
    suspend fun trimLogsToLatest()

    @Query("DELETE FROM forward_jobs WHERE state = 'FAILED'")
    suspend fun deleteFailedJobs()
}
