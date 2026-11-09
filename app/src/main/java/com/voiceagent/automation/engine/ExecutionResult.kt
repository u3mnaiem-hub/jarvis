package com.voiceagent.automation.engine

import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.model.ExecutionLog

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
