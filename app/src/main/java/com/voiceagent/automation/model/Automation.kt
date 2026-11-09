package com.voiceagent.automation.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "automations")
data class Automation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val nameAr: String = "",
    val description: String = "",
    val category: String = "Internet & Router", // Internet & Router, Phone Settings, Daily Tasks, Applications, Custom
    val iconName: String = "router",
    val isEnabled: Boolean = true,
    val isFavorite: Boolean = false,
    
    // Voice activation phrases (e.g. ["Enable TV Internet", "شغل النت للتلفزيون", "افتح نت الشاشة"])
    val voicePhrases: List<String> = emptyList(),
    
    // Ordered sequence of steps
    val steps: List<AutomationStep> = emptyList(),
    
    // Execution statistics
    val createdAt: Long = System.currentTimeMillis(),
    val lastExecutedAt: Long? = null,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    
    // Global execution settings
    val globalTimeoutSeconds: Int = 60,
    val requireConfirmationBeforeRun: Boolean = false,
    val speakStepProgress: Boolean = true
) {
    val successRate: Int
        get() = if (executionCount > 0) ((successCount.toDouble() / executionCount) * 100).toInt() else 100
}
