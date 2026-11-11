package com.voiceagent.automation.ui.navigation

sealed class Screen(val route: String, val titleEn: String, val titleAr: String) {
    object Home : Screen("home", "Home", "الرئيسية")
    object MyAutomations : Screen("my_automations", "My Automations", "مهامي")
    object Finance : Screen("finance", "Masrofy Finance", "مصروفي")
    object WorkflowEditor : Screen("workflow_editor/{automationId}", "Edit Workflow", "تعديل المهمة") {
        fun createRoute(automationId: String) = "workflow_editor/$automationId"
    }
    object CreateWorkflow : Screen("create_workflow", "New Automation", "مهمة جديدة")
    object Recorder : Screen("recorder", "Record Automation", "تسجيل مهمة")
    object ExecutionHistory : Screen("history", "History", "السجل")
    object FailureDetails : Screen("failure_details/{logId}", "Failure Details", "تفاصيل التشخيص") {
        fun createRoute(logId: String) = "failure_details/$logId"
    }
    object BankingProtection : Screen("banking_protection", "Banking Protection", "حماية البنوك وإنستاباي")
    object PermissionsDashboard : Screen("permissions", "Permissions", "لوحة الصلاحيات")
    object WorkflowBackup : Screen("backup", "Backup & Restore", "النسخ الاحتياطي")
    object CredentialManager : Screen("credentials", "Passwords", "الخزينة")
    object Settings : Screen("settings", "Settings", "الإعدادات")
}
