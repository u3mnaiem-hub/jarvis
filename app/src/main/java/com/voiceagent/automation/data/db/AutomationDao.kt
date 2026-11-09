package com.voiceagent.automation.data.db

import androidx.room.*
import com.voiceagent.automation.model.Automation
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY isFavorite DESC, createdAt DESC")
    fun getAllAutomationsFlow(): Flow<List<Automation>>

    @Query("SELECT * FROM automations WHERE isEnabled = 1")
    suspend fun getEnabledAutomations(): List<Automation>

    @Query("SELECT * FROM automations WHERE id = :id LIMIT 1")
    suspend fun getAutomationById(id: String): Automation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(automation: Automation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(automations: List<Automation>)

    @Delete
    suspend fun delete(automation: Automation)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE automations SET lastExecutedAt = :time, executionCount = executionCount + 1, successCount = successCount + :successIncr, failureCount = failureCount + :failIncr WHERE id = :id")
    suspend fun updateExecutionStats(id: String, time: Long, successIncr: Int, failIncr: Int)
}
