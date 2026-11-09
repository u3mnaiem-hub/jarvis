package com.voiceagent.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.voiceagent.automation.ui.navigation.Screen
import com.voiceagent.automation.ui.screens.*
import com.voiceagent.automation.ui.theme.VoiceAgentTheme

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val labelRes: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            NavigationBar {
                                bottomNavItems.forEach { item ->
                                    val selected = currentRoute == item.screen.route
                                    NavigationBarItem(
                                        icon = { Icon(item.icon, contentDescription = null) },
                                        label = { Text(stringResource(item.labelRes)) },
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
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onNavigateToCreate = { navController.navigate("workflow_editor/new") },
                                onNavigateToMyAutomations = { navController.navigate(Screen.MyAutomations.route) },
                                onNavigateToHistory = { navController.navigate(Screen.ExecutionHistory.route) },
                                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                            )
                        }

                        composable(Screen.MyAutomations.route) {
                            MyAutomationsScreen(
                                onNavigateToEdit = { id -> navController.navigate(Screen.WorkflowEditor.createRoute(id)) },
                                onNavigateToCreate = { navController.navigate("workflow_editor/new") },
                                onNavigateToRecord = { navController.navigate(Screen.Recorder.route) }
                            )
                        }

                        composable(
                            route = Screen.WorkflowEditor.route,
                            arguments = listOf(navArgument("automationId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("automationId")
                            WorkflowEditorScreen(
                                automationId = id,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Recorder.route) {
                            RecorderScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEdit = { id ->
                                    navController.popBackStack()
                                    navController.navigate(Screen.WorkflowEditor.createRoute(id))
                                }
                            )
                        }

                        composable(Screen.ExecutionHistory.route) {
                            ExecutionHistoryScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.CredentialManager.route) {
                            CredentialManagerScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onNavigateToCredentials = { navController.navigate(Screen.CredentialManager.route) },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
