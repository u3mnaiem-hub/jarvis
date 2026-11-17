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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000,
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
                ),
                AutomationStep(
                    order = 7,
                    title = "Set Parental Control Rule for $deviceName (OFF / Unblock)",
                    description = "Scans instName_ParentCtrl:0..10 for '$deviceName', clicks Enable:ParentCtrl:{index} radio (value '0'), and verifies after 2s",
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
                    timeoutSeconds = 15,
                    delayAfterMs = 2000,
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000,
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
                ),
                AutomationStep(
                    order = 7,
                    title = "Set Parental Control Rule for $deviceName (ON / Block)",
                    description = "Scans instName_ParentCtrl:0..10 for '$deviceName', clicks Enable:ParentCtrl:{index} radio (value '1'), and verifies after 2s",
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
                    timeoutSeconds = 15,
                    delayAfterMs = 2000,
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

    fun createRouterDeviceWorkflow(
        id: String,
        name: String,
        nameAr: String,
        deviceName: String,
        enableInternet: Boolean,
        voicePhrases: List<String>,
        routerIp: String = "192.168.1.1"
    ): Automation {
        val targetRadio = if (enableInternet) "0" else "1"
        val actionLabel = if (enableInternet) "OFF / Unblock" else "ON / Block"
        val spokenAction = if (enableInternet) "Enabling internet for $deviceName" else "Blocking internet for $deviceName"
        return Automation(
            id = id,
            name = name,
            nameAr = nameAr,
            description = "WebMacro on ZTE H188 ($routerIp): logs in, opens Parental Control, finds '$deviceName', sets Enable:ParentCtrl:{index} to '$targetRadio' ($actionLabel), verifies after 2s, and logs out.",
            category = "Internet & Router",
            iconName = if (enableInternet) "router" else "block",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = voicePhrases,
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
                    description = "Fills [id='Frm_Username'] via JS",
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
                    description = "Fills [id='Frm_Password'] from Android Keystore",
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
                    description = "Clicks [id='LoginId'] and waits for [id='mmInternet']",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='LoginId']",
                    candidateCssSelectors = listOf("[id='LoginId']", "input[type='submit']", "button[type='submit']"),
                    selectorText = "Login",
                    candidateTexts = listOf("Login", "Log in", "تسجيل الدخول", "دخول"),
                    waitAfterNavigationSelector = "[id='mmInternet']",
                    maxRetries = 2,
                    timeoutSeconds = 12,
                    delayAfterMs = 2000,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Internet Tab",
                    description = "Clicks [id='mmInternet'] and waits for [id='smParentCtrl']",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "[id='mmInternet']",
                    candidateCssSelectors = listOf("[id='mmInternet']", "a.MenuItem[id='mmInternet']"),
                    selectorText = "Internet",
                    candidateTexts = listOf("Internet", "الإنترنت", "الانترنت"),
                    waitAfterNavigationSelector = "[id='smParentCtrl']",
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
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
                    timeoutSeconds = 12,
                    delayAfterMs = 2000
                ),
                AutomationStep(
                    order = 7,
                    title = "Set Parental Control for $deviceName ($actionLabel)",
                    description = "Scans instName_ParentCtrl:0..10 for '$deviceName', sets Enable:ParentCtrl:{index} radio to '$targetRadio', verifies after 2s",
                    actionType = ActionType.SET_INDEXED_RADIO,
                    isWebStep = true,
                    rowContextText = deviceName,
                    selectorText = deviceName,
                    indexedNamePattern = "[id='instName_ParentCtrl:{index}']",
                    indexedTargetPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
                    indexRangeStart = 0,
                    indexRangeEnd = 10,
                    targetRadioValue = targetRadio,
                    maxRetries = 1,
                    timeoutSeconds = 15,
                    delayAfterMs = 2000,
                    spokenMessage = spokenAction
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

    fun getDeviceRouterWorkflows(routerIp: String = "192.168.1.1"): List<Automation> {
        return listOf(
            createRouterDeviceWorkflow(
                id = "demo_enable_toshiba_tv",
                name = "Enable Toshiba TV Internet",
                nameAr = "تشغيل الإنترنت لتلفزيون توشيبا",
                deviceName = "Toshiba",
                enableInternet = true,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Enable Toshiba TV internet",
                    "Unblock Toshiba",
                    "شغل النت لتلفزيون توشيبا",
                    "افتح النت لتوشيبا",
                    "شغل نت توشيبا"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_disable_toshiba_tv",
                name = "Disable Toshiba TV Internet",
                nameAr = "فصل الإنترنت عن تلفزيون توشيبا",
                deviceName = "Toshiba",
                enableInternet = false,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Disable Toshiba TV internet",
                    "Block Toshiba",
                    "اقفل النت عن تلفزيون توشيبا",
                    "افصل النت عن توشيبا",
                    "اقفل نت توشيبا"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_enable_sharp_tv",
                name = "Enable Sharp TV Internet",
                nameAr = "تشغيل الإنترنت لتلفزيون شارب",
                deviceName = "Sharp",
                enableInternet = true,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Enable Sharp TV internet",
                    "Unblock Sharp",
                    "شغل النت لتلفزيون شارب",
                    "افتح النت لشارب",
                    "شغل نت شارب"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_disable_sharp_tv",
                name = "Disable Sharp TV Internet",
                nameAr = "فصل الإنترنت عن تلفزيون شارب",
                deviceName = "Sharp",
                enableInternet = false,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Disable Sharp TV internet",
                    "Block Sharp",
                    "اقفل النت عن تلفزيون شارب",
                    "افصل النت عن شارب",
                    "اقفل نت شارب"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_enable_level_i8",
                name = "Enable Level i8 Internet",
                nameAr = "تشغيل الإنترنت لريسيفر ليفل i8",
                deviceName = "Level",
                enableInternet = true,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Enable Level i8 internet",
                    "Unblock Level i8",
                    "شغل النت لريسيفر ليفل",
                    "افتح النت لليفل",
                    "شغل نت ليفل i8"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_disable_level_i8",
                name = "Disable Level i8 Internet",
                nameAr = "فصل الإنترنت عن ريسيفر ليفل i8",
                deviceName = "Level",
                enableInternet = false,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Disable Level i8 internet",
                    "Block Level i8",
                    "اقفل النت عن ريسيفر ليفل",
                    "افصل النت عن ليفل",
                    "اقفل نت ليفل i8"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_enable_king_star",
                name = "Enable King Star Internet",
                nameAr = "تشغيل الإنترنت لريسيفر كينج ستار",
                deviceName = "King",
                enableInternet = true,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Enable King Star internet",
                    "Unblock King Star",
                    "شغل النت لريسيفر كينج ستار",
                    "افتح النت لكينج ستار",
                    "شغل نت كينج ستار"
                )
            ),
            createRouterDeviceWorkflow(
                id = "demo_disable_king_star",
                name = "Disable King Star Internet",
                nameAr = "فصل الإنترنت عن ريسيفر كينج ستار",
                deviceName = "King",
                enableInternet = false,
                routerIp = routerIp,
                voicePhrases = listOf(
                    "Disable King Star internet",
                    "Block King Star",
                    "اقفل النت عن ريسيفر كينج ستار",
                    "افصل النت عن كينج ستار",
                    "اقفل نت كينج ستار"
                )
            )
        )
    }

    fun createMyWeQuotaWorkflow(): Automation {
        return Automation(
            id = "macro_check_my_we_quota",
            name = "Check My WE Quota",
            nameAr = "استعلام عن باقة وي",
            description = "يفتح تطبيق My WE تلقائياً، ينتظر تحميل الصفحة الرئيسية، يستخرج الجيجابايت المتبقية وتاريخ تجديد الباقة، وينطقها بصوت جارفيس.",
            category = "Internet & Utilities",
            iconName = "wifi",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Check WE quota",
                "Check my internet quota",
                "Jarvis check we quota",
                "استعلم عن باقة وي",
                "باقي كام جيجا في النت",
                "شوف باقي كام في باقة وي",
                "باقي كام جيجا في وي",
                "استعلام عن باقة النت",
                "باقة النت وي",
                "اعرف رصيد النت",
                "رصيد باقة وي"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Launch My WE App",
                    description = "يفتح تطبيق My WE على الهاتف",
                    actionType = ActionType.LAUNCH_APP,
                    targetPackage = "prod.we.telecomegypt",
                    delayAfterMs = 4000,
                    spokenMessage = "جاري فتح تطبيق ماي وي للفحص"
                ),
                AutomationStep(
                    order = 2,
                    title = "Wait for Dashboard to Load",
                    description = "انتظار ظهور بيانات الباقة على الشاشة",
                    actionType = ActionType.DELAY,
                    delayAfterMs = 3500
                ),
                AutomationStep(
                    order = 3,
                    title = "Scroll Down for Remaining GB",
                    description = "تمرير الشاشة لإظهار الجيجابايت المتبقية",
                    actionType = ActionType.SCROLL_FORWARD,
                    stopOnFailure = false,
                    delayAfterMs = 1500
                ),
                AutomationStep(
                    order = 4,
                    title = "Read Quota & Announce",
                    description = "استخراج الجيجابايت وتاريخ التجديد ونطقها صوتياً",
                    actionType = ActionType.SCRAPE_AND_SPEAK,
                    delayAfterMs = 1000
                )
            )
        )
    }

    fun createAlexElectricityBillWorkflow(paymentCode: String = "8020110103637011"): Automation {
        return Automation(
            id = "macro_alex_electricity_bill",
            name = "Alexandria Electricity Bill",
            nameAr = "فاتورة كهرباء الإسكندرية",
            description = "استعلام عن فاتورة كهرباء الإسكندرية بالرقم الآلي ($paymentCode) ونطق النتيجة صوتياً.",
            category = "Utilities & Bills",
            iconName = "bolt",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Check electricity bill",
                "Alexandria electricity",
                "فاتورة الكهربا",
                "فاتورة الكهرباء",
                "استعلام فاتورة الكهرباء",
                "كام فاتورة الكهرباء",
                "كهرباء اسكندرية",
                "فاتورة كهرباء اسكندرية",
                "احسب فاتورة الكهرباء"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Open Electricity Portal",
                    description = "فتح بوابة الاستعلام عن فواتير كهرباء الإسكندرية",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "https://alex.ahram.org.eg/",
                    delayAfterMs = 2500,
                    spokenMessage = "جاري فتح بوابة كهرباء الإسكندرية"
                ),
                AutomationStep(
                    order = 2,
                    title = "Enter Payment Number",
                    description = "إدخال الرقم الآلي للسداد الإلكتروني",
                    actionType = ActionType.SET_TEXT,
                    isWebStep = true,
                    textPayload = paymentCode,
                    cssSelector = "#Reference",
                    candidateTexts = listOf("رقم السداد", "الرقم الالي", "الرقم الآلي", "كود السداد"),
                    candidateCssSelectors = listOf("#Reference", "input[name='Reference']", "input[placeholder*='الرقم' i]", "input[name*='pay' i]", "input[name*='code' i]", "input[type='text']"),
                    delayAfterMs = 1000
                ),
                AutomationStep(
                    order = 3,
                    title = "Click Search",
                    description = "الضغط على زر بحث / استعلام",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "#contact_form button[type='submit']",
                    candidateCssSelectors = listOf("#contact_form button[type='submit']", "button[type='submit']", "input[type='submit']"),
                    selectorText = "بحث",
                    candidateTexts = listOf("بحث", "استعلام", "عرض الفاتورة"),
                    delayAfterMs = 3500
                ),
                AutomationStep(
                    order = 4,
                    title = "Read & Announce Bill",
                    description = "قراءة قيمة الفاتورة وحالتها ونطقها صوتياً",
                    actionType = ActionType.SCRAPE_AND_SPEAK,
                    isWebStep = true,
                    delayAfterMs = 1000
                )
            )
        )
    }

    fun createAlexWaterBillWorkflow(paymentCode: String = "05010000054933"): Automation {
        return Automation(
            id = "macro_alex_water_bill",
            name = "Alexandria Water Bill",
            nameAr = "فاتورة مياه الإسكندرية",
            description = "استعلام عن فاتورة مياه الشرب بالإسكندرية برقم السداد ($paymentCode) ونطق النتيجة صوتياً.",
            category = "Utilities & Bills",
            iconName = "water",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Check water bill",
                "Alexandria water",
                "فاتورة المية",
                "فاتورة المياه",
                "استعلام فاتورة المياه",
                "كام فاتورة المية",
                "مياه اسكندرية",
                "فاتورة مياه اسكندرية",
                "احسب فاتورة المية"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Open Water Portal",
                    description = "فتح صفحة الاستعلام عن فاتورة مياه الإسكندرية",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "https://alexwater.com.eg/2016/fatora/",
                    delayAfterMs = 2500,
                    spokenMessage = "جاري فتح صفحة الاستعلام عن فاتورة المياه"
                ),
                AutomationStep(
                    order = 2,
                    title = "Enter Payment Code",
                    description = "إدخال كود السداد الإلكتروني",
                    actionType = ActionType.SET_TEXT,
                    isWebStep = true,
                    textPayload = paymentCode,
                    cssSelector = "#inquiryForm input[type='text']",
                    candidateTexts = listOf("رقم السداد الإلكتروني", "أدخل 14 رقم", "رقم السداد"),
                    candidateCssSelectors = listOf("#inquiryForm input[type='text']", "input[placeholder*='14']", "input[name*='code' i]", "input[name*='pay' i]"),
                    delayAfterMs = 1000
                ),
                AutomationStep(
                    order = 3,
                    title = "Click Search",
                    description = "الضغط على زر استعلام الآن",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "#submitBtn",
                    candidateCssSelectors = listOf("#submitBtn", "#inquiryForm button[type='submit']", "button[type='submit']"),
                    selectorText = "استعلام الآن",
                    candidateTexts = listOf("استعلام الآن", "استعلام", "بحث", "التالي"),
                    delayAfterMs = 3500
                ),
                AutomationStep(
                    order = 4,
                    title = "Read & Announce Bill",
                    description = "قراءة قيمة الفاتورة وحالتها ونطقها صوتياً",
                    actionType = ActionType.SCRAPE_AND_SPEAK,
                    isWebStep = true,
                    delayAfterMs = 1000
                )
            )
        )
    }

    fun createPetrotradeGasBillWorkflow(paymentCode: String = "0302050532061500"): Automation {
        val normalizedCode = paymentCode.filter { it.isDigit() }.padEnd(16, '0').take(16)
        val pairs = normalizedCode.chunked(2)
        val fieldLabels = listOf("محافظة", "منطقة", "يومية", "قطاع", "بلوك", "عمارة", "شقة", "فرعي")
        val inputSteps = pairs.mapIndexed { index, twoDigits ->
            val fieldNum = index + 1
            AutomationStep(
                order = index + 2,
                title = "Enter ${fieldLabels[index]} ($twoDigits)",
                description = "إدخال ${fieldLabels[index]} في الخانة T$fieldNum",
                actionType = ActionType.SET_TEXT,
                isWebStep = true,
                textPayload = twoDigits,
                cssSelector = "#T$fieldNum",
                candidateCssSelectors = listOf("#T$fieldNum", "input[name='T$fieldNum']"),
                delayAfterMs = 200
            )
        }
        return Automation(
            id = "macro_alex_gas_petrotrade",
            name = "Petrotrade Gas Bill",
            nameAr = "فاتورة غاز بتروتريد",
            description = "استعلام عن فاتورة غاز بتروتريد برقم المشترك ($paymentCode) مقسماً إلى 8 خانات ونطق النتيجة صوتياً.",
            category = "Utilities & Bills",
            iconName = "local_fire_department",
            isFavorite = true,
            isEnabled = true,
            voicePhrases = listOf(
                "Check gas bill",
                "Petrotrade gas",
                "فاتورة الغاز",
                "استعلام فاتورة الغاز",
                "كام فاتورة الغاز",
                "غاز بتروتريد",
                "فاتورة بتروتريد",
                "احسب فاتورة الغاز"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Open Petrotrade Portal",
                    description = "فتح موقع بتروتريد للاستعلام عن فواتير الغاز",
                    actionType = ActionType.OPEN_URL,
                    isWebStep = true,
                    url = "https://www.petrotrade.com.eg/billing/billq.aspx",
                    delayAfterMs = 2500,
                    spokenMessage = "جاري فتح موقع بتروتريد للغاز"
                )
            ) + inputSteps + listOf(
                AutomationStep(
                    order = inputSteps.size + 2,
                    title = "Click Search",
                    description = "الضغط على زر استعلام",
                    actionType = ActionType.CLICK_ELEMENT,
                    isWebStep = true,
                    cssSelector = "#BCrn",
                    candidateCssSelectors = listOf("#BCrn", "input[name='BCrn']", "input[type='submit']"),
                    selectorText = "استعلام",
                    candidateTexts = listOf("استعلام", "استعراض الفواتير", "عرض", "بحث"),
                    delayAfterMs = 3500
                ),
                AutomationStep(
                    order = inputSteps.size + 3,
                    title = "Read & Announce Bill",
                    description = "قراءة قيمة الفاتورة وحالتها ونطقها صوتياً",
                    actionType = ActionType.SCRAPE_AND_SPEAK,
                    isWebStep = true,
                    delayAfterMs = 1000
                )
            )
        )
    }

    fun getAllInitialAutomations(): List<Automation> {
        return listOf(
            createEnableTvInternetWorkflow(),
            createDisableTvInternetWorkflow(),
            createMasrofyExpenseWorkflow(),
            createMyWeQuotaWorkflow(),
            createAlexElectricityBillWorkflow(),
            createAlexWaterBillWorkflow(),
            createPetrotradeGasBillWorkflow()
        ) + getDeviceRouterWorkflows()
    }
}
