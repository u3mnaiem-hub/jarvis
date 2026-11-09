package com.voiceagent.automation.template

import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep

object DemoWorkflows {

    /**
     * Pre-configured, fully editable demonstration workflow for Router Parental Control & TV Internet
     */
    fun createEnableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        routerUsername: String = "admin",
        deviceName: String = "Smart-TV",
        credentialKey: String = "router_admin_pass"
    ): Automation {
        return Automation(
            id = "demo_enable_tv_internet",
            name = "Enable TV Internet",
            nameAr = "تشغيل الإنترنت للتلفزيون",
            description = "Opens Firefox, logs into router ($routerIp), navigates to Parental Control, enables $deviceName internet access, and verifies.",
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
                    title = "Launch Browser (Firefox)",
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
                    delayAfterMs = 1500,
                    spokenMessage = "Navigating to router address"
                ),
                AutomationStep(
                    order = 3,
                    title = "Wait for Router Login Screen",
                    description = "Checks that login page is loaded",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    selectorText = "Login",
                    selectorResourceId = "login_button",
                    timeoutSeconds = 12
                ),
                AutomationStep(
                    order = 4,
                    title = "Locate & Enter Username",
                    description = "Enters router admin username",
                    actionType = ActionType.SET_TEXT,
                    selectorResourceId = "username",
                    selectorText = "Username",
                    textPayload = routerUsername,
                    delayAfterMs = 300
                ),
                AutomationStep(
                    order = 5,
                    title = "Retrieve & Inject Password from Keystore",
                    description = "Injects encrypted router password into password field",
                    actionType = ActionType.INJECT_CREDENTIAL,
                    selectorResourceId = "password",
                    selectorText = "Password",
                    credentialKey = credentialKey,
                    delayAfterMs = 300
                ),
                AutomationStep(
                    order = 6,
                    title = "Press Login Button",
                    description = "Submits login credentials",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorResourceId = "login_button",
                    selectorText = "Login",
                    delayAfterMs = 2000,
                    spokenMessage = "Logging into router"
                ),
                AutomationStep(
                    order = 7,
                    title = "Wait for Successful Dashboard",
                    description = "Waits for router status dashboard to load",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    selectorText = "Status",
                    timeoutSeconds = 15
                ),
                AutomationStep(
                    order = 8,
                    title = "Open Parental Control Menu",
                    description = "Navigates to device restriction controls",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Parental Control",
                    delayAfterMs = 1000
                ),
                AutomationStep(
                    order = 9,
                    title = "Select Connected Devices",
                    description = "Opens list of managed devices",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Connected Devices",
                    delayAfterMs = 800
                ),
                AutomationStep(
                    order = 10,
                    title = "Locate Television Device ($deviceName)",
                    description = "Finds $deviceName row in the device list",
                    actionType = ActionType.WAIT_FOR_ELEMENT,
                    selectorText = deviceName,
                    timeoutSeconds = 10
                ),
                AutomationStep(
                    order = 11,
                    title = "Change Internet Access to Enabled",
                    description = "Toggles internet access permission to Enabled",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Enabled",
                    delayAfterMs = 500,
                    spokenMessage = "Enabling television internet"
                ),
                AutomationStep(
                    order = 12,
                    title = "Press Save Settings",
                    description = "Applies router configuration change",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Save",
                    selectorResourceId = "save_btn",
                    delayAfterMs = 1500
                ),
                AutomationStep(
                    order = 13,
                    title = "Verify New Status",
                    description = "Confirms that $deviceName status is now Enabled",
                    actionType = ActionType.CHECK_TEXT_EXISTS,
                    selectorText = "Enabled",
                    stopOnFailure = true
                ),
                AutomationStep(
                    order = 14,
                    title = "Announce Completion",
                    description = "Spoken feedback confirming success",
                    actionType = ActionType.SPEAK_TEXT,
                    textPayload = "TV internet has been successfully enabled."
                )
            )
        )
    }

    /**
     * Disable TV Internet Workflow
     */
    fun createDisableTvInternetWorkflow(
        routerIp: String = "192.168.1.1",
        routerUsername: String = "admin",
        deviceName: String = "Smart-TV",
        credentialKey: String = "router_admin_pass"
    ): Automation {
        return Automation(
            id = "demo_disable_tv_internet",
            name = "Disable TV Internet",
            nameAr = "إيقاف الإنترنت عن التلفزيون",
            description = "Logs into router ($routerIp) and disables $deviceName internet access.",
            category = "Internet & Router",
            iconName = "router_off",
            isFavorite = false,
            isEnabled = true,
            voicePhrases = listOf(
                "Disable TV internet",
                "Turn off the TV connection",
                "Block internet for television",
                "اقفل النت عن التلفزيون",
                "عطل الانترنت للشاشة",
                "افصل نت التلفزيون",
                "اقفل نت الشاشة"
            ),
            steps = listOf(
                AutomationStep(
                    order = 1,
                    title = "Launch Browser",
                    actionType = ActionType.LAUNCH_APP,
                    targetPackage = "org.mozilla.firefox",
                    delayAfterMs = 1000
                ),
                AutomationStep(
                    order = 2,
                    title = "Navigate to Router",
                    actionType = ActionType.OPEN_URL,
                    targetPackage = "org.mozilla.firefox",
                    url = "http://$routerIp",
                    delayAfterMs = 1500
                ),
                AutomationStep(
                    order = 3,
                    title = "Inject Router Password",
                    actionType = ActionType.INJECT_CREDENTIAL,
                    selectorResourceId = "password",
                    selectorText = "Password",
                    credentialKey = credentialKey,
                    delayAfterMs = 400
                ),
                AutomationStep(
                    order = 4,
                    title = "Click Login",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Login",
                    delayAfterMs = 2000
                ),
                AutomationStep(
                    order = 5,
                    title = "Open Parental Control",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Parental Control",
                    delayAfterMs = 1000
                ),
                AutomationStep(
                    order = 6,
                    title = "Set TV Access to Disabled",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Disabled",
                    delayAfterMs = 500
                ),
                AutomationStep(
                    order = 7,
                    title = "Save Changes",
                    actionType = ActionType.CLICK_ELEMENT,
                    selectorText = "Save",
                    delayAfterMs = 1500
                ),
                AutomationStep(
                    order = 8,
                    title = "Announce Disconnection",
                    actionType = ActionType.SPEAK_TEXT,
                    textPayload = "TV internet has been disabled."
                )
            )
        )
    }
}
