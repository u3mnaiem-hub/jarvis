package com.voiceagent.automation.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }

    val isRecording by AutomationRecorderEngine.isRecordingFlow.collectAsState()
    val isPaused by AutomationRecorderEngine.isPausedFlow.collectAsState()
    val recordedSteps by AutomationRecorderEngine.recordedStepsFlow.collectAsState()
    val isServiceConnected by VoiceAccessibilityService.isServiceConnected.collectAsState()

    var workflowName by remember { mutableStateOf("") }
    var workflowNameAr by remember { mutableStateOf("") }
    var voicePhrase by remember { mutableStateOf("") }

    var isManualStepDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "تسجيل مهمة جديدة",
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
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start / Pause / Resume / Stop Recording Controls
                    if (isRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isPaused) {
                                        AutomationRecorderEngine.resumeRecording()
                                    } else {
                                        AutomationRecorderEngine.pauseRecording()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) Amber600 else Slate700
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPaused) "استئناف" else "إيقاف مؤقت", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { AutomationRecorderEngine.stopRecording() },
                                colors = ButtonDefaults.buttonColors(containerColor = Red600),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("إنهاء وحفظ", fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                AutomationRecorderEngine.startRecording(workflowName.ifBlank { "Recorded Workflow" })
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("بدء تسجيل الشاشة الآن", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Actions Row: Manual Step + Save
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { isManualStepDialogVisible = true },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ خطوة يدوية", fontSize = 12.sp, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                if (workflowName.isBlank()) {
                                    Toast.makeText(context, "يرجى كتابة اسم للمهمة قبل الحفظ", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (recordedSteps.isEmpty()) {
                                    Toast.makeText(context, "لم يتم تسجيل أي خطوات بعد", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                coroutineScope.launch {
                                    val newId = UUID.randomUUID().toString()
                                    val auto = Automation(
                                        id = newId,
                                        name = workflowName.trim(),
                                        nameAr = workflowNameAr.ifBlank { workflowName }.trim(),
                                        voicePhrases = if (voicePhrase.isNotBlank()) listOf(voicePhrase.trim()) else emptyList(),
                                        steps = recordedSteps
                                    )
                                    db.automationDao().insertOrUpdate(auto)
                                    AutomationRecorderEngine.cancelRecording()
                                    Toast.makeText(context, "تم حفظ المهمة بنجاح!", Toast.LENGTH_LONG).show()
                                    onNavigateToEdit(newId)
                                }
                            },
                            enabled = recordedSteps.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("حفظ المهمة (Save)", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Service Warning
            if (!isServiceConnected) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Red100),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Red600, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "خدمة إمكانية الوصول غير مفعلة. يرجى تفعيلها من إعدادات الهاتف لالتقاط الضغطات.",
                                color = Red600,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Recorder Control Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Status indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(if (isRecording) Red500 else LightTextSecondary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isRecording) "وضع التسجيل نشط" else "المسجل جاهز للبدء",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRecording) Red600 else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.5.sp
                                )
                            }
                            Badge(containerColor = if (isRecording) Red100 else Emerald100) {
                                Text(
                                    text = if (isRecording) "REC ●" else "READY",
                                    color = if (isRecording) Red600 else Emerald600,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        OutlinedTextField(
                            value = workflowName,
                            onValueChange = { workflowName = it },
                            label = { Text("اسم المهمة (مثال: Router TV Internet)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = voicePhrase,
                            onValueChange = { voicePhrase = it },
                            label = { Text("العبارة الصوتية (مثال: شغل النت للتلفزيون)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Steps Title
            item {
                Text(
                    text = "الخطوات المسجلة (${recordedSteps.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            // Steps List
            if (recordedSteps.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "💡 اضغط على 'بدء تسجيل الشاشة الآن'، ثم افتح أي تطبيق (مثل Firefox أو الإعدادات) ونفّذ خطواتك بصورة طبيعية؛ سيقوم Voice Agent بالتقاط الضغطات والنصوص وإضافتها هنا تلقائياً.",
                                fontSize = 12.sp,
                                color = LightTextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(recordedSteps) { index, step ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Emerald100),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${index + 1}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Emerald600)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${step.actionType.labelEn} • ${step.selectorText ?: step.selectorResourceId ?: step.targetPackage ?: "Coordinate"}",
                                    fontSize = 11.sp,
                                    color = LightTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { AutomationRecorderEngine.removeStepAt(index) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Red600, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Manual step modal
    if (isManualStepDialogVisible) {
        StepEditorDialog(
            initialStep = AutomationStep(order = recordedSteps.size + 1),
            onDismiss = { isManualStepDialogVisible = false },
            onSave = { manualStep ->
                AutomationRecorderEngine.addManualStep(manualStep)
                isManualStepDialogVisible = false
            }
        )
    }
}
