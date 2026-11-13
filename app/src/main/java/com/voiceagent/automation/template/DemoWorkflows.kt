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
                    title = "Open Router Page",
                    description = "Loads http://$routerIp inside in-app WebView",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "http://$routerIp",
                    delayAfterMs = 500,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 2,
                    title = "Enter Router Username",
                    description = "Fills username field via JS with input/change events",
                    actionType = ActionType.SET_TEXT,
                    isWebStep = true,
                    cssSelector = "#Frm_Username",
                    candidateCssSelectors = listOf("#Frm_Username", "input[name='Username']", "input[type='text']"),
                    candidateTexts = listOf("Username", "User Name", "اسم المستخدم"),
                    textPayload = "admin",
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 3,
                    title = "Enter Router Password",
                    description = "Fills encrypted password from Keystore vault",
                    actionType = ActionType.INJECT_CREDENTIAL,
                    isWebStep = true,
                    credentialKey = "router_password",
                    cssSelector = "#Frm_Password",
                    candidateCssSelectors = listOf("#Frm_Password", "input[name='Password']", "input[type='password']"),
                    candidateTexts = listOf("Password", "كلمة المرور"),
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 4,
                    title = "Tap Router Login",
                    description = "Clicks Login button/div or submits form",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "#LoginId",
                    candidateCssSelectors = listOf("#LoginId", "input[type='submit']", "button[type='submit']"),
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"),
                    maxRetries = 1,
                    timeoutSeconds = 10,
                    delayAfterMs = 1500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Internet Tab",
                    description = "Navigates to Internet tab (including inside iframes)",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    selectorText = "Internet",
                    candidateTexts = listOf("Internet", "الإنترنت", "الانترنت"),
                    timeoutSeconds = 12,
                    delayAfterMs = 800
                ),
                AutomationStep(
                    order = 6,
                    title = "Open Parental Control",
                    description = "Navigates to Parental Control section",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    selectorText = "Parental Control",
                    candidateTexts = listOf("Parental Control", "التحكم الأبوي", "Device Management"),
                    timeoutSeconds = 12,
                    delayAfterMs = 800
                ),
                AutomationStep(
                    order = 7,
                    title = "Locate Television Device ($deviceName)",
                    description = "Waits for $deviceName row in the device list",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    isWebStep = true,
                    selectorText = deviceName,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 8,
                    title = "Enable TV Internet Access",
                    description = "Clicks Allow / Enable for $deviceName",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    candidateTexts = listOf("Allow", "Enable", "تفعيل", "سماح", "ON"),
                    delayAfterMs = 800,
                    spokenMessage = "Enabling TV internet"
                ),
                AutomationStep(
                    order = 9,
                    title = "Save Router Settings",
                    description = "Applies settings on the router",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    candidateTexts = listOf("Apply", "Save", "حفظ", "تطبيق"),
                    delayAfterMs = 1200,
                    spokenMessage = "TV internet has been successfully enabled"
                ),
                AutomationStep(
                    order = 10,
                    title = "Log Out of Router",
                    description = "Releases the single admin session cleanly",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    candidateTexts = listOf("Log Out", "Logout", "Sign Out", "تسجيل الخروج"),
                    stopOnFailure = false,
                    timeoutSeconds = 6
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
                    title = "Open Router Page",
                    description = "Navigates to router page http://$routerIp in default browser",
                    actionType = ActionType.OPEN_URL,
                    url = "http://$routerIp",
                    delayAfterMs = 2500,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 2,
                    title = "Detect & Click Router Login Button",
                    description = "Clicks Login button using saved or auto-filled credentials",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "Sign in", "تسجيل الدخول", "دخول"),
                    timeoutSeconds = 15,
                    delayAfterMs = 2500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 3,
                    title = "Verify Router Authentication",
                    description = "Verifies router authenticated dashboard appears",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    candidateTexts = listOf("Status", "Parental Control", "Settings", "حالة الاتصال", "التحكم الأبوي", "الأجهزة"),
                    timeoutSeconds = 12
                ),
                AutomationStep(
                    order = 4,
                    title = "Open Parental Control Menu",
                    description = "Navigates to device restriction controls",
                    actionType = ActionType.CLICK_ELEMENT,
                    candidateTexts = listOf("Parental Control", "التحكم الأبوي", "Device Management", "إدارة الأجهزة"),
                    delayAfterMs = 1200
                ),
                AutomationStep(
                    order = 5,
                    title = "Locate Television Device ($deviceName)",
                    description = "Finds $deviceName row in the device list",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    selectorText = deviceName,
                    timeoutSeconds = 8
                ),
                AutomationStep(
                    order = 6,
                    title = "Block TV Internet Access",
                    description = "Toggles or sets internet access switch to Block/Off",
                    actionType = ActionType.CLICK_ELEMENT,
                    candidateTexts = listOf("Block", "Disable", "حظر", "تعطيل", "OFF"),
                    delayAfterMs = 1000,
                    spokenMessage = "Blocking TV internet"
                ),
                AutomationStep(
                    order = 7,
                    title = "Save Router Settings",
                    description = "Applies settings on the router",
                    actionType = ActionType.CLICK_ELEMENT,
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
