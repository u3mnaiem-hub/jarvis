package com.voiceagent.automation.model

import java.util.UUID

data class AutomationStep(
    val id: String = UUID.randomUUID().toString(),
    val order: Int = 0,
    val title: String = "",
    val description: String = "",
    val actionType: ActionType = ActionType.CLICK_ELEMENT,
    val isStepEnabled: Boolean = true,
    
    // Application target
    val targetPackage: String? = null,
    val targetActivity: String? = null,
    
    // Web / URL / Deep Link target
    val url: String? = null,
    val deepLinkUri: String? = null,
    
    // Element selectors (Accessibility)
    val selectorResourceId: String? = null,
    val selectorText: String? = null,
    val selectorContentDescription: String? = null,
    val selectorClassName: String? = null,

    // Web / JS DOM selectors (for WebMacro)
    val isWebStep: Boolean = false,
    val cssSelector: String? = null,
    val candidateCssSelectors: List<String> = emptyList(),
    
    // Semantic / Multi-text candidate matching (e.g. ["Login", "Log in", "تسجيل الدخول", "دخول"])
    val candidateTexts: List<String> = emptyList(),
    
    // Post-action state verification
    val verificationText: String? = null,
    val verificationResourceId: String? = null,
    
    // Coordinate fallbacks (x, y)
    val clickX: Float? = null,
    val clickY: Float? = null,
    
    // Gesture swipe coordinates
    val swipeStartX: Float? = null,
    val swipeStartY: Float? = null,
    val swipeEndX: Float? = null,
    val swipeEndY: Float? = null,
    val swipeDurationMs: Long = 300,
    
    // Text input payload or Credential reference
    val textPayload: String? = null,
    val credentialKey: String? = null,
    
    // Timing & Wait
    val delayBeforeMs: Long = 200,
    val delayAfterMs: Long = 400,
    val timeoutSeconds: Int = 10,
    val maxRetries: Int = 2,
    
    // Conditional logic
    val isCondition: Boolean = false,
    val onConditionMetJumpToStepId: String? = null,
    val onConditionFailedJumpToStepId: String? = null,
    val stopOnFailure: Boolean = true,
    
    // Spoken feedback
    val spokenMessage: String? = null
)
