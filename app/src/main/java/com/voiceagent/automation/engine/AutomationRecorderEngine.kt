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

    private val _isPausedFlow = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPausedFlow.asStateFlow()

    private val _recordedStepsFlow = MutableStateFlow<List<AutomationStep>>(emptyList())
    val recordedStepsFlow: StateFlow<List<AutomationStep>> = _recordedStepsFlow.asStateFlow()

    val isRecording: Boolean
        get() = _isRecordingFlow.value

    val isPaused: Boolean
        get() = _isPausedFlow.value

    val isRecordingActive: Boolean
        get() = _isRecordingFlow.value && !_isPausedFlow.value

    fun startRecording(initialTitle: String = "Recorded Automation") {
        Log.i(TAG, "Starting automation recorder: $initialTitle")
        _recordedStepsFlow.value = emptyList()
        _isPausedFlow.value = false
        _isRecordingFlow.value = true
    }

    fun pauseRecording() {
        if (_isRecordingFlow.value) {
            Log.i(TAG, "Recording paused")
            _isPausedFlow.value = true
        }
    }

    fun resumeRecording() {
        if (_isRecordingFlow.value) {
            Log.i(TAG, "Recording resumed")
            _isPausedFlow.value = false
        }
    }

    fun stopRecording(): List<AutomationStep> {
        Log.i(TAG, "Stopping recorder with ${_recordedStepsFlow.value.size} steps")
        _isRecordingFlow.value = false
        _isPausedFlow.value = false
        return _recordedStepsFlow.value
    }

    fun cancelRecording() {
        _isRecordingFlow.value = false
        _isPausedFlow.value = false
        _recordedStepsFlow.value = emptyList()
    }

    fun addRecordedStep(step: AutomationStep) {
        if (!isRecordingActive) return

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
