package com.voiceagent.automation.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.template.DemoWorkflows
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onNavigateToCredentials: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    val isServiceConnected by VoiceAccessibilityService.isServiceConnected.collectAsState()

    var spokenFeedbackEnabled by remember { mutableStateOf(true) }
    var selectedLanguage by remember { mutableStateOf("ar-EG") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات والصلاحيات (Settings)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Accessibility Service Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceConnected) Emerald100 else Red100
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isServiceConnected) Emerald600 else Red600),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isServiceConnected) Icons.Default.Check else Icons.Default.AccessibilityNew,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isServiceConnected) "خدمة إمكانية الوصول: مفعلة بنجاح ✓" else "خدمة الوصول: غير مفعلة (اضغط للتفعيل)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = if (isServiceConnected) Emerald700 else Red600
                        )
                        Text(
                            text = "ضرورية لتنفيذ الضغطات والتنقل الآلي بين التطبيقات",
                            fontSize = 11.5.sp,
                            color = LightTextSecondary
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LightTextSecondary)
                }
            }

            // Credential Manager Shortcut
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToCredentials),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Purple100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = Purple500)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("خزينة كلمات المرور المشفرة (Keystore)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("إدارة كلمات مرور الراوتر والخدمات المشفرة بأمان", fontSize = 11.5.sp, color = LightTextSecondary)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = LightTextSecondary)
                }
            }

            // Voice & Speech Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("إعدادات الصوت والنطق", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("التعليق الصوتي أثناء التنفيذ (TTS)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("نطق اسم كل خطوة وتأكيد إتمام المهمة", fontSize = 11.sp, color = LightTextSecondary)
                        }
                        Switch(
                            checked = spokenFeedbackEnabled,
                            onCheckedChange = { spokenFeedbackEnabled = it }
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Text("لهجة التعرف الصوتي الافتراضية:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedLanguage == "ar-EG",
                            onClick = { selectedLanguage = "ar-EG" },
                            label = { Text("عربي (مصر) 🇪🇬") }
                        )
                        FilterChip(
                            selected = selectedLanguage == "ar-SA",
                            onClick = { selectedLanguage = "ar-SA" },
                            label = { Text("عربي فصحى 🇸🇦") }
                        )
                        FilterChip(
                            selected = selectedLanguage == "en-US",
                            onClick = { selectedLanguage = "en-US" },
                            label = { Text("English 🇺🇸") }
                        )
                    }
                }
            }

            // Restore Demo Workflows & Backup
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("النسخ الاحتياطي واستعادة النماذج", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val enableTv = DemoWorkflows.createEnableTvInternetWorkflow()
                                val disableTv = DemoWorkflows.createDisableTvInternetWorkflow()
                                db.automationDao().insertAll(listOf(enableTv, disableTv))
                                Toast.makeText(context, "تمت استعادة مهام الراوتر والتلفزيون النموذجية بنجاح!", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استعادة مهمة راوتر التلفزيون (Enable TV Internet)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
