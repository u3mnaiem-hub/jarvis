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

    @Volatile
    private var lastStepTimestampMs: Long = 0L

    /** Creation time of every recorded step (keyed by step id). Used to trim steps right before an overlay stop tap. */
    private val stepCreatedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Name / voice phrase currently typed in the Recorder screen, so that a macro stopped from the
     * floating overlay button (without returning to Jarvis) can be saved with the user's chosen metadata.
     */
    @Volatile
    var pendingWorkflowName: String = ""
        private set

    @Volatile
    var pendingWorkflowNameAr: String = ""
        private set

    @Volatile
    var pendingVoicePhrase: String = ""
        private set

    fun updatePendingMetadata(name: String, nameAr: String, voicePhrase: String) {
        pendingWorkflowName = name
        pendingWorkflowNameAr = nameAr
        pendingVoicePhrase = voicePhrase
    }

    val isRecording: Boolean
        get() = _isRecordingFlow.value

    val isPaused: Boolean
        get() = _isPausedFlow.value

    val isRecordingActive: Boolean
        get() = _isRecordingFlow.value && !_isPausedFlow.value

    fun startRecording(initialTitle: String = "Recorded Automation") {
        Log.i(TAG, "Starting automation recorder: $initialTitle")
        _recordedStepsFlow.value = emptyList()
        stepCreatedAtMs.clear()
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
        stepCreatedAtMs.clear()
    }

    /**
     * Safety net applied when the recording is stopped from the floating overlay button:
     *  - drops every trailing step created within [trailingWindowMs] before the stop tap,
     *  - drops any step targeting the overlay (view id / content description),
     *  - drops trailing steps that would end the macro inside Jarvis' own UI (only when the macro
     *    also contains steps in other apps, so pure in-app-browser recordings are preserved).
     * Returns the re-indexed list and publishes it as the current recorded steps.
     */
    fun sanitizeStepsForOverlayStop(
        steps: List<AutomationStep>,
        stopTapTimeMs: Long,
        ownPackage: String,
        overlayViewIdSuffix: String,
        overlayContentDescription: String,
        trailingWindowMs: Long = 500L
    ): List<AutomationStep> {
        val result = steps.filterNot { s ->
            (s.selectorResourceId?.endsWith(overlayViewIdSuffix) == true) ||
                (s.selectorContentDescription == overlayContentDescription) ||
                (s.selectorText == overlayContentDescription)
        }.toMutableList()

        val cutoff = stopTapTimeMs - trailingWindowMs
        while (result.isNotEmpty()) {
            val createdAt = stepCreatedAtMs[result.last().id] ?: break
            if (createdAt >= cutoff) {
                Log.d(TAG, "Overlay stop: dropping trailing step '${result.last().title}' (${stopTapTimeMs - createdAt} ms before stop)")
                result.removeAt(result.lastIndex)
            } else {
                break
            }
        }

        if (result.any { it.targetPackage != null && it.targetPackage != ownPackage }) {
            while (result.isNotEmpty() && result.last().targetPackage == ownPackage) {
                Log.d(TAG, "Overlay stop: dropping trailing Jarvis step '${result.last().title}'")
                result.removeAt(result.lastIndex)
            }
        }

        val reindexed = result.mapIndexed { idx, s -> s.copy(order = idx + 1) }
        _recordedStepsFlow.value = reindexed
        Log.i(TAG, "Overlay stop: kept ${reindexed.size} of ${steps.size} steps")
        return reindexed
    }

    fun addRecordedStep(step: AutomationStep) {
        if (!isRecordingActive) return

        val currentList = _recordedStepsFlow.value.toMutableList()
        val nextOrder = currentList.size + 1
        val indexedStep = step.copy(order = nextOrder)

        val lastStep = currentList.lastOrNull()

        // 1. Merge sequential text typing on the same field instead of 1-letter steps
        if (indexedStep.actionType == com.voiceagent.automation.model.ActionType.SET_TEXT &&
            lastStep != null && lastStep.actionType == com.voiceagent.automation.model.ActionType.SET_TEXT
        ) {
            val isSameField = (indexedStep.selectorResourceId != null && indexedStep.selectorResourceId == lastStep.selectorResourceId) ||
                    (indexedStep.targetPackage == lastStep.targetPackage &&
                            Math.abs((indexedStep.clickX ?: 0f) - (lastStep.clickX ?: 0f)) < 80f &&
                            Math.abs((indexedStep.clickY ?: 0f) - (lastStep.clickY ?: 0f)) < 80f)

            if (isSameField) {
                currentList[currentList.size - 1] = lastStep.copy(
                    textPayload = indexedStep.textPayload,
                    title = "Type: ${indexedStep.textPayload}"
                )
                _recordedStepsFlow.value = currentList
                Log.d(TAG, "Aggregated typing step #${lastStep.order}: ${indexedStep.textPayload}")
                return
            }
        }

        // 2. Deduplicate rapid duplicate clicks on the exact same element within 400ms
        val now = System.currentTimeMillis()
        if (lastStep != null &&
            (now - lastStepTimestampMs) < 400L &&
            lastStep.actionType == indexedStep.actionType &&
            lastStep.selectorResourceId == indexedStep.selectorResourceId &&
            lastStep.selectorText == indexedStep.selectorText &&
            indexedStep.selectorResourceId != null
        ) {
            return
        }

        lastStepTimestampMs = now
        stepCreatedAtMs[indexedStep.id] = now
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
