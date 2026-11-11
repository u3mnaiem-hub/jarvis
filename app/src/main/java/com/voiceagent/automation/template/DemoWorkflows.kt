package com.voiceagent.automation.template

import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep

object DemoWorkflows {

    /**
     * Pre-configured, fully editable demonstration workflow for Router Parental Control & TV Internet.
     * Compatible with Firefox saved passwords (where Username and Password are automatically pre-populated).
     */
    fun createEnableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        deviceName: String = "Smart-TV"
    ): Automation {
        return Automation(
            id = "demo_enable_tv_internet",
            name = "Enable TV Internet",
            nameAr = "تشغيل الإنترنت للتلفزيون",
            description = "Opens Firefox, navigates to router ($routerIp), clicks Login with Firefox pre-saved credentials, navigates to Parental Control, enables $deviceName internet access, and verifies.",
            category = "Internet & Router",
            iconName = "router",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Enable TV internet",
                "Turn on the TV connection",
                "Allow internet for television",
                "شغل النت للتلفزيون",
                "افتح الانترنت للشاشة",
                "شغل نت التلفزيون",
                "فعل نت الشاشة",
                "شغل نت التي في"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Launch Firefox Browser",
                    description = "Opens Firefox to connect to router",
                    actionType = ActionType.LAUNCH_APP,
                    targetPackage = "org.mozilla.firefox",
                    delayAfterMs = 1200,
                    spokenMessage = "Opening Firefox"
                ),
                AutomationStep(
                    order = 2,
                    title = "Navigate to Router Page",
                    description = "Navigates to http://$routerIp",
                    actionType = ActionType.OPEN_URL,
                    targetPackage = "org.mozilla.firefox",
                    url = "http://$routerIp",
                    delayAfterMs = 2000,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 3,
                    title = "Detect & Click Router Login Button",
                    description = "Clicks Login button using Firefox pre-populated credentials",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"),
                    timeoutSeconds = 12,
                    delayAfterMs = 2500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 4,
                    title = "Verify Router Authentication",
                    description = "Verifies router authenticated dashboard appears",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Status", "Parental Control", "Settings", "حالة الاتصال", "التحكم الأبوي", "الأجهزة"),
                    timeoutSeconds = 12
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Parental Control Menu",
                    description = "Navigates to device restriction controls",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Parental Control", "التحكم الأبوي", "Device Management", "إدارة الأجهزة"),
                    delayAfterMs = 1200
                ),
                AutomationStep(
                    order = 6,
                    title = "Locate Television Device ($deviceName)",
                    description = "Finds $deviceName row in the device list",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    selectorText = deviceName,
                    timeoutSeconds = 8
                ),
                AutomationStep(
                    order = 7,
                    title = "Enable TV Internet Access",
                    description = "Toggles or sets internet access switch to ON",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Allow", "Enable", "تفعيل", "سماح", "ON"),
                    delayAfterMs = 1000,
                    spokenMessage = "Enabling TV internet"
                ),
                AutomationStep(
                    order = 8,
                    title = "Save Router Settings",
                    description = "Applies settings on the router",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Apply", "Save", "حفظ", "تطبيق"),
                    delayAfterMs = 1500,
                    spokenMessage = "TV internet has been successfully enabled"
                )
            )
        )
    }

    /**
     * Disable TV Internet workflow
     */
    fun createDisableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        deviceName: String = "Smart-TV"
    ): Automation {
        return Automation(
            id = "demo_disable_tv_internet",
            name = "Disable TV Internet",
            nameAr = "إيقاف الإنترنت عن التلفزيون",
            description = "Opens Firefox, logs into router ($routerIp), and blocks internet access for $deviceName.",
            category = "Internet & Router",
            iconName = "block",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Disable TV internet",
                "Turn off the TV connection",
                "Block internet for television",
                "اقفل النت عن التلفزيون",
                "اقفل النت عن الشاشة",
                "افصل نت التلفزيون",
                "عطل نت الشاشة",
                "اقفل نت التي في"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Launch Firefox Browser",
                    description = "Opens Firefox to connect to router",
                    actionType = ActionType.LAUNCH_APP,
                    targetPackage = "org.mozilla.firefox",
                    delayAfterMs = 1200,
                    spokenMessage = "Opening Firefox"
                ),
                AutomationStep(
                    order = 2,
                    title = "Navigate to Router Page",
                    description = "Navigates to http://$routerIp",
                    actionType = ActionType.OPEN_URL,
                    targetPackage = "org.mozilla.firefox",
                    url = "http://$routerIp",
                    delayAfterMs = 2000,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 3,
                    title = "Detect & Click Router Login Button",
                    description = "Clicks Login button using Firefox pre-populated credentials",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"),
                    timeoutSeconds = 12,
                    delayAfterMs = 2500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 4,
                    title = "Verify Router Authentication",
                    description = "Verifies router authenticated dashboard appears",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Status", "Parental Control", "Settings", "حالة الاتصال", "التحكم الأبوي", "الأجهزة"),
                    timeoutSeconds = 12
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Parental Control Menu",
                    description = "Navigates to device restriction controls",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Parental Control", "التحكم الأبوي", "Device Management", "إدارة الأجهزة"),
                    delayAfterMs = 1200
                ),
                AutomationStep(
                    order = 6,
                    title = "Locate Television Device ($deviceName)",
                    description = "Finds $deviceName row in the device list",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    selectorText = deviceName,
                    timeoutSeconds = 8
                ),
                AutomationStep(
                    order = 7,
                    title = "Block TV Internet Access",
                    description = "Toggles or sets internet access switch to Block/Off",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Block", "Disable", "حظر", "تعطيل", "OFF"),
                    delayAfterMs = 1000,
                    spokenMessage = "Blocking TV internet"
                ),
                AutomationStep(
                    order = 8,
                    title = "Save Router Settings",
                    description = "Applies settings on the router",
                    actionType = ActionType.CLICK_ELEMENT,
                    targetPackage = "org.mozilla.firefox",
                    candidateTexts = listOf("Apply", "Save", "حفظ", "تطبيق"),
                    delayAfterMs = 1500,
                    spokenMessage = "TV internet has been successfully disabled"
                )
            )
        )
    }

    /**
     * Pre-configured workflow to directly link Voice Agent with Masrofy
     */
    fun createMasrofyExpenseWorkflow(): Automation {
        return Automation(
            id = "demo_masrofy_expense",
            name = "Add Expense to Masrofy",
            nameAr = "تسجيل مصروف في مصروفي",
            description = "Links Voice Agent directly with Masrofy app to record expenses seamlessly.",
            category = "Applications",
            iconName = "account_balance_wallet",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Add expense to masrofy",
                "Open masrofy",
                "سجل مصروف في مصروفي",
                "افتح مصروفي",
                "سجل مصاريف"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Launch Masrofy Application",
                    description = "Opens Masrofy app cleanly",
                    actionType = ActionType.LAUNCH_APP,
                    targetPackage = "com.masrofy.app",
                    delayAfterMs = 1000,
                    spokenMessage = "Opening Masrofy"
                )
            )
        )
    }

    fun getAllInitialAutomations(): List<Automation> {
        return listOf(
            createEnableTvInternetWorkflow(),
            createDisableTvInternetWorkflow(),
            createMasrofyExpenseWorkflow()
        )
    }
}
