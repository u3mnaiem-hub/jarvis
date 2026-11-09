package com.voiceagent.automation.ui.navigation

sealed class Screen(val route: String, val titleEn: String, val titleAr: String) {
    object Home : Screen("home", "Home", "الرئيسية")
    object MyAutomations : Screen("my_automations", "My Automations", "مهامي")
    object WorkflowEditor : Screen("workflow_editor/{automationId}", "Edit Workflow", "تعديل المهمة") {
        fun createRoute(automationId: String) = "workflow_editor/$automationId"
    }
    object CreateWorkflow : Screen("create_workflow", "New Automation", "مهمة جديدة")
    object Recorder : Screen("recorder", "Record Automation", "تسجيل مهمة")
    object ExecutionHistory : Screen("history", "History", "السجل")
    object CredentialManager : Screen("credentials", "Passwords", "الخزينة")
    object Settings : Screen("settings", "Settings", "الإعدادات")
}
