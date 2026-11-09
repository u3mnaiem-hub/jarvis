package com.voiceagent.automation.data.db

import androidx.room.*
import com.voiceagent.automation.model.ExecutionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionLogDao {
    @Query("SELECT * FROM execution_logs ORDER BY startedAt DESC LIMIT 100")
    fun getAllLogsFlow(): Flow<List<ExecutionLog>>

    @Query("SELECT * FROM execution_logs WHERE automationId = :automationId ORDER BY startedAt DESC")
    fun getLogsForAutomation(automationId: String): Flow<List<ExecutionLog>>

    @Query("SELECT * FROM execution_logs ORDER BY startedAt DESC LIMIT 1")
    fun getLatestLogFlow(): Flow<ExecutionLog?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ExecutionLog)

    @Query("DELETE FROM execution_logs")
    suspend fun clearAllLogs()
}
