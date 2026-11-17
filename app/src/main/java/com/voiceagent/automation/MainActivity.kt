package com.voiceagent.automation

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.engine.WebMacroEngine
import com.voiceagent.automation.security.SafeModeManager
import com.voiceagent.automation.service.AccessibilityControlHelper
import com.voiceagent.automation.ui.navigation.Screen
import com.voiceagent.automation.ui.screens.*
import com.voiceagent.automation.ui.theme.VoiceAgentTheme
import com.voiceagent.automation.voice.TextToSpeechManager

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val labelRes: Int
)

class MainActivity : ComponentActivity() {
    private var autoListenTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (shouldAutoListen(intent)) {
            autoListenTrigger++
}
        setContent {
            VoiceAgentTheme {
                val navController = rememberNavController()
                val currentBackStack by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStack?.destination?.route

                val bottomNavItems = listOf(
                    BottomNavItem(Screen.Home, Icons.Default.Home, R.string.nav_home),
                    BottomNavItem(Screen.MyAutomations, Icons.Default.SmartToy, R.string.nav_my_automations),
                    BottomNavItem(Screen.CreateWorkflow, Icons.Default.AddCircle, R.string.nav_create),
                    BottomNavItem(Screen.ExecutionHistory, Icons.Default.History, R.string.nav_history),
                    BottomNavItem(Screen.Settings, Icons.Default.Settings, R.string.nav_settings)
                )

                val showBottomBar = currentRoute in listOf(
                    Screen.Home.route,
                    Screen.MyAutomations.route,
                    Screen.ExecutionHistory.route,
                    Screen.Settings.route
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomBar) {
                            Surface(
                                shadowElevation = 12.dp,
                                tonalElevation = 4.dp,
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                            ) {
                                NavigationBar(
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp
                                ) {
                                    bottomNavItems.forEach { item ->
                                        val selected = currentRoute == item.screen.route
                                        NavigationBarItem(
                                            icon = {
                                                Icon(
                                                    imageVector = item.icon,
                                                    contentDescription = stringResource(item.labelRes)
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = stringResource(item.labelRes),
                                                    fontSize = 10.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            ),
                                            alwaysShowLabel = true,
                                            selected = selected,
                                            onClick = {
                                                if (item.screen == Screen.CreateWorkflow) {
                                                    navController.navigate("workflow_editor/new")
                                                } else {
                                                    navController.navigate(item.screen.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
            }
                ) { innerPadding ->
                    val activeWebView by WebMacroEngine.activeWebViewFlow.collectAsState()
                    val latestReport by AutomationExecutionEngine.latestReportFlow.collectAsState()
                    val isWebViewPinned by AutomationExecutionEngine.isWebViewPinnedFlow.collectAsState()
                    var hideLiveWebView by remember(activeWebView) { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Home.route,
                            modifier = Modifier.fillMaxSize()
                        ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                autoListenTrigger = autoListenTrigger,
                                onNavigateToCreate = { navController.navigate("workflow_editor/new")},
                                onNavigateToMyAutomations = { navController.navigate(Screen.MyAutomations.route)},
                                onNavigateToHistory = { navController.navigate(Screen.ExecutionHistory.route)},
                                onNavigateToSettings = { navController.navigate(Screen.Settings.route)},
                                onNavigateToBankingProtection = { navController.navigate(Screen.BankingProtection.route)},
                                onNavigateToPermissions = { navController.navigate(Screen.PermissionsDashboard.route)},
                                onNavigateToFailureDetails = { id -> navController.navigate(Screen.FailureDetails.createRoute(id))},
                                onNavigateToBackup = { navController.navigate(Screen.WorkflowBackup.route)},
                                onNavigateToBrowser = { navController.navigate(Screen.Browser.route)}
                            )
                }

                        composable(Screen.Browser.route) {
                            InAppBrowserScreen(
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.MyAutomations.route) {
                            MyAutomationsScreen(
                                onNavigateToEdit = { id -> navController.navigate(Screen.WorkflowEditor.createRoute(id))},
                                onNavigateToCreate = { navController.navigate("workflow_editor/new")},
                                onNavigateToRecord = { navController.navigate(Screen.Recorder.route)}
                            )
                }

                        composable(
                            route = Screen.WorkflowEditor.route,
                            arguments = listOf(navArgument("automationId") { type = NavType.StringType})
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("automationId")
                            WorkflowEditorScreen(
                                automationId = id,
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.Recorder.route) {
                            RecorderScreen(
                                onNavigateBack = { navController.popBackStack()},
                                onNavigateToEdit = { id ->
                                    navController.popBackStack()
                                    navController.navigate(Screen.WorkflowEditor.createRoute(id))
                                },
                                onNavigateToBrowser = { navController.navigate(Screen.Browser.route) }
                            )
                }

                        composable(Screen.ExecutionHistory.route) {
                            ExecutionHistoryScreen(
                                onNavigateBack = { navController.popBackStack()},
                                onNavigateToDetails = { id -> navController.navigate(Screen.FailureDetails.createRoute(id))}
                            )
                }

                        composable(
                            route = Screen.FailureDetails.route,
                            arguments = listOf(navArgument("logId") { type = NavType.StringType})
                        ) { backStackEntry ->
                            val logId = backStackEntry.arguments?.getString("logId") ?: "latest"
                            FailureDetailsScreen(
                                logId = logId,
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.BankingProtection.route) {
                            BankingProtectionScreen(
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.PermissionsDashboard.route) {
                            PermissionsDashboardScreen(
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.WorkflowBackup.route) {
                            WorkflowBackupScreen(
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.CredentialManager.route) {
                            CredentialManagerScreen(
                                onNavigateBack = { navController.popBackStack()}
                            )
                }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onNavigateToCredentials = { navController.navigate(Screen.CredentialManager.route)},
                                onNavigateToBankingProtection = { navController.navigate(Screen.BankingProtection.route)},
                                onNavigateToPermissions = { navController.navigate(Screen.PermissionsDashboard.route)},
                                onNavigateToBackup = { navController.navigate(Screen.WorkflowBackup.route)},
                                onNavigateBack = { navController.popBackStack()}
                            )
                }
                    }

                        if (activeWebView != null && !hideLiveWebView) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 12.dp,
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isWebViewPinned) Color(0xFFB45309) else Color(0xFF065F46))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isWebViewPinned) "الشاشة مثبتة للقراءة" else "عرض الموقع المباشر",
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!isWebViewPinned) {
                                                Button(
                                                    onClick = { AutomationExecutionEngine.setWebViewPinned(true) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("تثبيت الشاشة", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                Button(
                                                    onClick = { AutomationExecutionEngine.setWebViewPinned(false) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("متابعة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (isWebViewPinned) AutomationExecutionEngine.setWebViewPinned(false)
                                                    hideLiveWebView = true
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                            ) {
                                                Text("إخفاء", color = Color.White, fontSize = 11.5.sp)
                                            }
                                        }
                                    }
                                    AndroidView(
                                        factory = {
                                            activeWebView!!.also { wv ->
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        latestReport?.let { report ->
                            val badgeColor = when (report.isPaid) {
                                true -> Color(0xFF059669)
                                false -> Color(0xFFD97706)
                                null -> Color(0xFF2563EB)
                            }
                            AlertDialog(
                                onDismissRequest = {},
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (report.isPaid == true) Icons.Default.CheckCircle else Icons.Default.ReceiptLong,
                                            contentDescription = null,
                                            tint = badgeColor
                                        )
                                        Text(
                                            text = report.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        )
                                    }
                                },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 360.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            color = badgeColor.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                                Text(
                                                    text = "الحالة: ${report.statusLabel}",
                                                    color = badgeColor,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                if (!report.amountLabel.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "القيمة المطلوبة: ${report.amountLabel}",
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 18.sp
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "التقرير:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = report.details,
                                                fontSize = 13.sp,
                                                lineHeight = 20.sp,
                                                modifier = Modifier.fillMaxWidth().padding(10.dp)
                                            )
                                        }
                                        if (activeWebView != null) {
                                            OutlinedButton(
                                                onClick = {
                                                    AutomationExecutionEngine.setWebViewPinned(true)
                                                    hideLiveWebView = false
                                                    AutomationExecutionEngine.dismissReport()
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Web, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("تصفح وتمرير صفحة الموقع (تثبيت الشاشة)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { AutomationExecutionEngine.dismissReport() }
                                    ) {
                                        Text("إغلاق")
                                    }
                                },
                                dismissButton = {
                                    OutlinedButton(
                                        onClick = { TextToSpeechManager.speak(report.spokenText, isArabic = true) }
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("نطق النتيجة")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldAutoListen(intent)) {
            autoListenTrigger++
        }
    }

    private fun shouldAutoListen(intent: Intent?): Boolean {
        val isListenDeepLink = intent?.data?.scheme == "voiceagent" && intent.data?.host == "listen"
        return isListenDeepLink || SafeModeManager.isAutoListenOnLaunchEnabled(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && SafeModeManager.isAutoDisableAccessibilityOnExit(this)) {
            AccessibilityControlHelper.disableNow(this)
        }
    }
}
