package com.voiceagent.automation.engine

import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.model.ExecutionLog

data class ExecutionReport(
    val title: String,
    val statusLabel: String,
    val amountLabel: String? = null,
    val isPaid: Boolean? = null,
    val details: String,
    val spokenText: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class ExecutionState {
    object Idle : ExecutionState()
    data class Running(
        val automation: Automation,
        val currentStepIndex: Int,
        val currentStep: AutomationStep,
        val totalSteps: Int
    ) : ExecutionState()
    data class Completed(val log: ExecutionLog) : ExecutionState()
    data class Failed(val log: ExecutionLog, val errorMessage: String) : ExecutionState()
    object Stopped : ExecutionState()
}
