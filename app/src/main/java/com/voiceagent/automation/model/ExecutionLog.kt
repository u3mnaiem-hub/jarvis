package com.voiceagent.automation.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "execution_logs")
data class ExecutionLog(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val automationId: String,
    val automationName: String,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val isSuccess: Boolean = false,
    val completedStepsCount: Int = 0,
    val totalStepsCount: Int = 0,
    val failedStepTitle: String? = null,
    val failureReason: String? = null,
    val failureCategory: String? = null,
    val suggestedAction: String? = null,
    
    // Step by step breakdown log
    val stepDetails: List<StepExecutionDetail> = emptyList()
)

data class StepExecutionDetail(
    val stepOrder: Int,
    val stepTitle: String,
    val actionType: ActionType,
    val status: StepStatus,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = System.currentTimeMillis(),
    val expectedElement: String? = null,
    val elementFound: Boolean = true,
    val actionAttempted: Boolean = true,
    val actionConfirmed: Boolean = true,
    val retryCount: Int = 0,
    val failureReason: String? = null,
    val suggestedAction: String? = null,
    val message: String? = null
)
