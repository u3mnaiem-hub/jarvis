package com.voiceagent.automation.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.template.DemoWorkflows
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

data class WorkflowsBackupPackage(
    val formatVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "1.0.0",
    val automationsCount: Int = 0,
    val automations: List<Automation> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowBackupScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    val automations by db.automationDao().getAllAutomationsFlow().collectAsState(initial = emptyList())

    val gson = remember { Gson() }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var duplicateCount by remember { mutableStateOf(0) }

    // File import picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val jsonContent = reader.readText()
                    reader.close()

                    val type = object : TypeToken<WorkflowsBackupPackage>() {}.type
                    val backupPkg: WorkflowsBackupPackage = gson.fromJson(jsonContent, type)

                    val existingIds = automations.map { it.id }.toSet()
                    val existingNames = automations.map { it.name.trim().lowercase() }.toSet()

                    var added = 0
                    var dupes = 0

                    backupPkg.automations.forEach { auto ->
                        if (existingIds.contains(auto.id) || existingNames.contains(auto.name.trim().lowercase())) {
                            dupes++
                        } else {
                            // Sanitized safety: ensure no credentials injected via import
                            val safeSteps = auto.steps.map { s ->
                                if (s.credentialKey != null) s.copy(credentialKey = null) else s
                            }
                            db.automationDao().insertOrUpdate(auto.copy(steps = safeSteps))
                            added++
                        }
                    }

                    duplicateCount = dupes
                    importStatusMessage = "تم استيراد $added مهمة بنجاح (تم تخطي $dupes مهمة مكررة)."
                    Toast.makeText(context, importStatusMessage, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    importStatusMessage = "فشل استيراد الملف: تنسيق JSON غير صالح أو تالف."
                    Toast.makeText(context, importStatusMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "النسخ الاحتياطي والاستيراد",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Backup Overview Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate50)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Indigo600),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Backup, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("إدارة نسخ المهام المحفوظة", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Slate900)
                                Text("${automations.size} مهمة محفوظة محلياً في قاعدة البيانات", fontSize = 12.sp, color = Slate600)
                            }
                        }

                        Text(
                            "يمكنك تصدير مهامك بالكامل إلى ملف بتنسيق JSON آمن لمشاركتها أو استعادتها بعد تثبيت التطبيق. الملفات المصدرة آمنة تماماً ولا تحتوي على أي كلمات مرور.",
                            fontSize = 12.sp,
                            color = Slate600,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // Export Actions Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("تصدير المهام (Export)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Slate900)

                        Button(
                            onClick = {
                                exportAllAutomations(context, automations, gson)
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo600)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("تصدير جميع المهام (${automations.size}) بصيغة JSON", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Import Actions Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("استيراد المهام (Import)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Slate900)

                        Button(
                            onClick = {
                                filePickerLauncher.launch("application/json")
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("استيراد مهام من ملف JSON", fontWeight = FontWeight.SemiBold)
                        }

                        if (!importStatusMessage.isNullOrBlank()) {
                            Surface(
                                color = Slate100,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    importStatusMessage!!,
                                    fontSize = 12.sp,
                                    color = Slate700,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Restore Demo Workflows
            item {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val initialList = DemoWorkflows.getAllInitialAutomations()
                            db.automationDao().insertAll(initialList)
                            Toast.makeText(context, "تمت استعادة المهام الافتراضية بنجاح", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("استعادة المهام الافتراضية المدمجة (راوتر + مصروفي)")
                }
            }
        }
    }
}

private fun exportAllAutomations(context: Context, automations: List<Automation>, gson: Gson) {
    // Sanitize: strip any credentials from export
    val sanitizedList = automations.map { auto ->
        val safeSteps = auto.steps.map { s ->
            if (s.credentialKey != null) s.copy(credentialKey = null) else s
        }
        auto.copy(steps = safeSteps)
    }

    val backupPkg = WorkflowsBackupPackage(
        formatVersion = 1,
        exportedAt = System.currentTimeMillis(),
        appVersion = "1.0.0",
        automationsCount = sanitizedList.size,
        automations = sanitizedList
    )

    val jsonString = gson.toJson(backupPkg)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "VoiceAgent_Workflows_Backup.json")
        putExtra(Intent.EXTRA_TEXT, jsonString)
    }
    context.startActivity(Intent.createChooser(intent, "تصدير المهام"))
}
