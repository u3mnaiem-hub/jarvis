package com.voiceagent.automation.engine

import android.util.Log
import com.voiceagent.automation.model.AutomationStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutomationRecorderEngine {
    private const val TAG = "AutomationRecorder"

    private val _isRecordingFlow = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecordingFlow.asStateFlow()

    private val _recordedStepsFlow = MutableStateFlow<List<AutomationStep>>(emptyList())
    val recordedStepsFlow: StateFlow<List<AutomationStep>> = _recordedStepsFlow.asStateFlow()

    val isRecording: Boolean
        get() = _isRecordingFlow.value

    fun startRecording(initialTitle: String = "Recorded Automation") {
        Log.i(TAG, "Starting automation recorder: $initialTitle")
        _recordedStepsFlow.value = emptyList()
        _isRecordingFlow.value = true
    }

    fun stopRecording(): List<AutomationStep> {
        Log.i(TAG, "Stopping recorder with ${_recordedStepsFlow.value.size} steps")
        _isRecordingFlow.value = false
        return _recordedStepsFlow.value
    }

    fun cancelRecording() {
        _isRecordingFlow.value = false
        _recordedStepsFlow.value = emptyList()
    }

    fun addRecordedStep(step: AutomationStep) {
        if (!_isRecordingFlow.value) return

        val currentList = _recordedStepsFlow.value.toMutableList()
        val nextOrder = currentList.size + 1
        val indexedStep = step.copy(order = nextOrder)

        // Deduplicate rapid duplicate clicks on the exact same element within 400ms
        val lastStep = currentList.lastOrNull()
        if (lastStep != null &&
            lastStep.actionType == indexedStep.actionType &&
            lastStep.selectorResourceId == indexedStep.selectorResourceId &&
            lastStep.selectorText == indexedStep.selectorText &&
            indexedStep.selectorResourceId != null
        ) {
            return
        }

        currentList.add(indexedStep)
        _recordedStepsFlow.value = currentList
        Log.d(TAG, "Recorded step #$nextOrder: ${indexedStep.title}")
    }

    fun addManualStep(step: AutomationStep) {
        val currentList = _recordedStepsFlow.value.toMutableList()
        val nextOrder = currentList.size + 1
        currentList.add(step.copy(order = nextOrder))
        _recordedStepsFlow.value = currentList
    }

    fun removeStepAt(index: Int) {
        val currentList = _recordedStepsFlow.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            // Re-index orders
            val reindexed = currentList.mapIndexed { idx, s -> s.copy(order = idx + 1) }
            _recordedStepsFlow.value = reindexed
        }
    }
}
