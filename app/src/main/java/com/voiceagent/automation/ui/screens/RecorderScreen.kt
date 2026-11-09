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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun RecorderScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }

    val isRecording by AutomationRecorderEngine.isRecordingFlow.collectAsState()
    val recordedSteps by AutomationRecorderEngine.recordedStepsFlow.collectAsState()
    val isServiceConnected by VoiceAccessibilityService.isServiceConnected.collectAsState()

    var workflowName by remember { mutableStateOf("") }
    var workflowNameAr by remember { mutableStateOf("") }
    var voicePhrase by remember { mutableStateOf("") }

    var isManualStepDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تسجيل مهمة جديدة (Automation Recorder)") },
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
        ) {
            // Service Warning
            if (!isServiceConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Red100),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "⚠️ خدمة إمكانية الوصول غير مفعلة. لن يتم التقاط الضغطات حتى يتم تفعيلها من إعدادات الهاتف.",
                        modifier = Modifier.padding(12.dp),
                        color = Red600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Recorder Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) Red500 else LightTextSecondary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRecording) "وضع التسجيل نشط (RECORDING...)" else "مسجل الخطوات جاهز (READY)",
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) Red600 else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (isRecording) {
                                    AutomationRecorderEngine.stopRecording()
                                } else {
                                    AutomationRecorderEngine.startRecording(workflowName.ifBlank { "Recorded Workflow" })
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRecording) Red600 else Emerald600
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isRecording) "إيقاف التسجيل" else "بدء التسجيل الآن")
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
                        label = { Text("العبارة الصوتية المنشطة (مثال: شغل النت للتلفزيون)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action toolbar: Add manual step & Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الخطوات المسجلة (${recordedSteps.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { isManualStepDialogVisible = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ خطوة يدوية", fontSize = 11.5.sp)
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
                                Toast.makeText(context, "تم حفظ المهمة المسجلة بنجاح!", Toast.LENGTH_LONG).show()
                                onNavigateToEdit(newId)
                            }
                        },
                        enabled = recordedSteps.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("حفظ المهمة (Save)", fontSize = 11.5.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Recorded Steps List
            if (recordedSteps.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "💡 اضغط على 'بدء التسجيل الآن'، ثم افتح أي تطبيق (مثل Firefox أو الإعدادات) ونفّذ خطواتك بصورة طبيعية؛ سيقوم Voice Agent بالتقاط الضغطات والنصوص وإضافتها هنا تلقائياً.",
                            fontSize = 12.sp,
                            color = LightTextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(recordedSteps) { index, step ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Emerald100),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Emerald600)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(step.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "${step.actionType.labelEn} • Target: ${step.selectorText ?: step.selectorResourceId ?: step.targetPackage ?: "Coordinate"}",
                                        fontSize = 11.sp,
                                        color = LightTextSecondary,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = { AutomationRecorderEngine.removeStepAt(index) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Red600, modifier = Modifier.size(18.dp))
                                }
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
