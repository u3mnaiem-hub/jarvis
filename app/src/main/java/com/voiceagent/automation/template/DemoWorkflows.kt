package com.voiceagent.automation.template

import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep

object DemoWorkflows {

    /**
     * Verified ZTE H188 (WE Egypt) WebMacro for enabling a device's internet access (Parental Control rule OFF = "0" or ON = "1").
     * All selectors live in step configuration (loaded from verified router_macro_config.json spec).
     */
    fun createEnableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        deviceName: String = "Smart-TV"
    ): Automation {
        return Automation(
            id = "demo_enable_tv_internet",
            name = "Enable TV Internet",
            nameAr = "تشغيل الإنترنت للتلفزيون",
            description = "WebMacro on ZTE H188 ($routerIp): logs in via [id='LoginId'], opens [id='mmInternet'] -> [id='smParentCtrl'], scans instName_ParentCtrl:0..10 for $deviceName, sets Enable:ParentCtrl:{index} radio, verifies persistence, and logs out.",
            category = "Internet & Router",
            iconName = "router",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Enable TV internet",
                "Unblock {device_name}",
                "Jarvis unblock {device_name}",
                "Turn on the TV connection",
                "Allow internet for {device_name}",
                "شغل النت للتلفزيون",
                "شغل النت لـ {device_name}",
                "افتح الانترنت للشاشة",
                "شغل نت التلفزيون",
                "فعل نت الشاشة",
                "شغل نت التي في"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Open Router Admin Page",
                    description = "Loads http://$routerIp inside in-app WebView",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "http://$routerIp",
                    delayAfterMs = 400,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 2,
                    title = "Enter Router Username",
                    description = "Fills [id='Frm_Username'] via JS and dispatches input/change events",
                    actionType = ActionType.SET_TEXT,
                    isWebStep = true,
                    cssSelector = "[id='Frm_Username']",
                    candidateCssSelectors = listOf("[id='Frm_Username']", "input[name='Username']"),
                    candidateTexts = listOf("Username", "User Name", "اسم المستخدم"),
                    textPayload = "admin",
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 3,
                    title = "Enter Router Password",
                    description = "Fills [id='Frm_Password'] from Android Keystore encrypted vault",
                    actionType = ActionType.INJECT_CREDENTIAL,
                    isWebStep = true,
                    credentialKey = "router_admin_pass",
                    cssSelector = "[id='Frm_Password']",
                    candidateCssSelectors = listOf("[id='Frm_Password']", "input[type='password']"),
                    candidateTexts = listOf("Password", "كلمة المرور"),
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 4,
                    title = "Click Login Control",
                    description = "Clicks [id='LoginId'] via JS MouseEvents + waits for [id='mmInternet'] (max 2 retries)",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='LoginId']",
                    candidateCssSelectors = listOf("[id='LoginId']", "input[type='submit']", "button[type='submit']"),
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "تسجيل الدخول", "دخول"),
                    waitAfterNavigationSelector = "[id='mmInternet']",
                    maxRetries = 2,
                    timeoutSeconds = 10,
                    delayAfterMs = 500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Internet Tab",
                    description = "Clicks <a class='MenuItem' id='mmInternet'> and waits for [id='smParentCtrl'] submenu",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='mmInternet']",
                    candidateCssSelectors = listOf("[id='mmInternet']", "a.MenuItem[id='mmInternet']"),
                    selectorText = "Internet",
                    candidateTexts = listOf("Internet", "الإنترنت", "الانترنت"),
                    waitAfterNavigationSelector = "[id='smParentCtrl']",
                    timeoutSeconds = 10,
                    delayAfterMs = 400
                ),
                AutomationStep(
                    order = 6,
                    title = "Open Parental Control",
                    description = "Clicks [id='smParentCtrl'] and waits for [id='instName_ParentCtrl:0']",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='smParentCtrl']",
                    candidateCssSelectors = listOf("[id='smParentCtrl']"),
                    selectorText = "Parental Control",
                    candidateTexts = listOf("Parental Control", "Parental Controls", "التحكم الأبوي"),
                    waitAfterNavigationSelector = "[id='instName_ParentCtrl:0']",
                    timeoutSeconds = 10,
                    delayAfterMs = 400
                ),
                AutomationStep(
                    order = 7,
                    title = "Set Parental Control Rule for $deviceName (OFF / Unblock)",
                    description = "Scans instName_ParentCtrl:0..10 for '$deviceName', clicks Enable:ParentCtrl:{index} radio (value '0'), and verifies after 1.5s",
                    actionType = ActionType.SET_INDEXED_RADIO,
                    isWebStep = true,
                    rowContextText = deviceName,
                    selectorText = deviceName,
                    indexedNamePattern = "[id='instName_ParentCtrl:{index}']",
                    indexedTargetPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
                    indexRangeStart = 0,
                    indexRangeEnd = 10,
                    targetRadioValue = "0",
                    maxRetries = 1,
                    timeoutSeconds = 12,
                    delayAfterMs = 500,
                    spokenMessage = "Enabling internet for $deviceName"
                ),
                AutomationStep(
                    order = 8,
                    title = "Log Out of Router",
                    description = "Releases the single ZTE admin session cleanly",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='Logout']",
                    candidateCssSelectors = listOf("[id='Logout']", "[id='logOut']", "a[onclick*='logout']"),
                    candidateTexts = listOf("Log Out", "Logout", "Exit", "تسجيل الخروج", "خروج"),
                    stopOnFailure = false,
                    timeoutSeconds = 6
                )
            )
        )
    }

    /**
     * Verified ZTE H188 (WE Egypt) WebMacro for blocking a device via Parental Control (rule ON = "1").
     */
    fun createDisableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        deviceName: String = "Smart-TV"
    ): Automation {
        return Automation(
            id = "demo_disable_tv_internet",
            name = "Disable TV Internet (Block Device)",
            nameAr = "إيقاف الإنترنت عن التلفزيون / حظر جهاز",
            description = "WebMacro on ZTE H188 ($routerIp): logs in via [id='LoginId'], opens [id='mmInternet'] -> [id='smParentCtrl'], scans instName_ParentCtrl:0..10 for $deviceName, sets Enable:ParentCtrl:{index} radio to '1' (Rule ON), verifies persistence, and logs out.",
            category = "Internet & Router",
            iconName = "block",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Disable TV internet",
                "Block {device_name}",
                "Jarvis block {device_name}",
                "Turn off the TV connection",
                "Block internet for {device_name}",
                "اقفل النت عن التلفزيون",
                "اقفل النت عن {device_name}",
                "افصل النت عن {device_name}",
                "اقفل النت عن الشاشة",
                "افصل نت التلفزيون",
                "عطل نت الشاشة",
                "اقفل نت التي في"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Open Router Admin Page",
                    description = "Loads http://$routerIp inside in-app WebView",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "http://$routerIp",
                    delayAfterMs = 400,
                    spokenMessage = "Opening router page"
                ),
                AutomationStep(
                    order = 2,
                    title = "Enter Router Username",
                    description = "Fills [id='Frm_Username'] via JS and dispatches input/change events",
                    actionType = ActionType.SET_TEXT,
                    isWebStep = true,
                    cssSelector = "[id='Frm_Username']",
                    candidateCssSelectors = listOf("[id='Frm_Username']", "input[name='Username']"),
                    candidateTexts = listOf("Username", "User Name", "اسم المستخدم"),
                    textPayload = "admin",
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 3,
                    title = "Enter Router Password",
                    description = "Fills [id='Frm_Password'] from Android Keystore encrypted vault",
                    actionType = ActionType.INJECT_CREDENTIAL,
                    isWebStep = true,
                    credentialKey = "router_admin_pass",
                    cssSelector = "[id='Frm_Password']",
                    candidateCssSelectors = listOf("[id='Frm_Password']", "input[type='password']"),
                    candidateTexts = listOf("Password", "كلمة المرور"),
                    maxRetries = 1,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 4,
                    title = "Click Login Control",
                    description = "Clicks [id='LoginId'] via JS MouseEvents + waits for [id='mmInternet'] (max 2 retries)",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='LoginId']",
                    candidateCssSelectors = listOf("[id='LoginId']", "input[type='submit']", "button[type='submit']"),
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "تسجيل الدخول", "دخول"),
                    waitAfterNavigationSelector = "[id='mmInternet']",
                    maxRetries = 2,
                    timeoutSeconds = 10,
                    delayAfterMs = 500,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Internet Tab",
                    description = "Clicks <a class='MenuItem' id='mmInternet'> and waits for [id='smParentCtrl'] submenu",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='mmInternet']",
                    candidateCssSelectors = listOf("[id='mmInternet']", "a.MenuItem[id='mmInternet']"),
                    selectorText = "Internet",
                    candidateTexts = listOf("Internet", "الإنترنت", "الانترنت"),
                    waitAfterNavigationSelector = "[id='smParentCtrl']",
                    timeoutSeconds = 10,
                    delayAfterMs = 400
                ),
                AutomationStep(
                    order = 6,
                    title = "Open Parental Control",
                    description = "Clicks [id='smParentCtrl'] and waits for [id='instName_ParentCtrl:0']",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='smParentCtrl']",
                    candidateCssSelectors = listOf("[id='smParentCtrl']"),
                    selectorText = "Parental Control",
                    candidateTexts = listOf("Parental Control", "Parental Controls", "التحكم الأبوي"),
                    waitAfterNavigationSelector = "[id='instName_ParentCtrl:0']",
                    timeoutSeconds = 10,
                    delayAfterMs = 400
                ),
                AutomationStep(
                    order = 7,
                    title = "Set Parental Control Rule for $deviceName (ON / Block)",
                    description = "Scans instName_ParentCtrl:0..10 for '$deviceName', clicks Enable:ParentCtrl:{index} radio (value '1'), and verifies after 1.5s",
                    actionType = ActionType.SET_INDEXED_RADIO,
                    isWebStep = true,
                    rowContextText = deviceName,
                    selectorText = deviceName,
                    indexedNamePattern = "[id='instName_ParentCtrl:{index}']",
                    indexedTargetPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
                    indexRangeStart = 0,
                    indexRangeEnd = 10,
                    targetRadioValue = "1",
                    maxRetries = 1,
                    timeoutSeconds = 12,
                    delayAfterMs = 500,
                    spokenMessage = "Blocking internet for $deviceName"
                ),
                AutomationStep(
                    order = 8,
                    title = "Log Out of Router",
                    description = "Releases the single ZTE admin session cleanly",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='Logout']",
                    candidateCssSelectors = listOf("[id='Logout']", "[id='logOut']", "a[onclick*='logout']"),
                    candidateTexts = listOf("Log Out", "Logout", "Exit", "تسجيل الخروج", "خروج"),
                    stopOnFailure = false,
                    timeoutSeconds = 6
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
