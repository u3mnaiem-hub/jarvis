package com.voiceagent.automation.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.model.ExecutionLog
import com.voiceagent.automation.model.StepExecutionDetail
import com.voiceagent.automation.model.StepStatus
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailureDetailsScreen(
    logId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    
    var executionLog by remember { mutableStateOf<ExecutionLog?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(logId) {
        isLoading = true
        executionLog = if (logId == "latest") {
            db.executionLogDao().getLatestLog()
        } else {
            db.executionLogDao().getLogById(logId)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "تفاصيل التشخيص والخطأ",
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
                },
                actions = {
                    if (executionLog != null) {
                        IconButton(onClick = {
                            exportSanitizedReport(context, executionLog!!)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share Report")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (executionLog == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("لا توجد تفاصيل تشخيص مسجلة لهذه المهمة", color = Slate500)
            }
        } else {
            val log = executionLog!!
            val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Summary Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (log.isSuccess) Emerald50 else Rose50
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    log.automationName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = if (log.isSuccess) Emerald900 else Red900
                                )
                                Surface(
                                    color = if (log.isSuccess) Emerald600 else Red600,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        if (log.isSuccess) "ناجح" else "فشل",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                "تاريخ التنفيذ: ${dateFormat.format(Date(log.startedAt))}",
                                fontSize = 12.sp,
                                color = Slate600
                            )

                            Text(
                                "الخطوات المكتملة: ${log.completedStepsCount} من أصل ${log.totalStepsCount}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate700
                            )

                            if (!log.isSuccess && !log.failedStepTitle.isNullOrBlank()) {
                                Divider(modifier = Modifier.padding(vertical = 4.dp), color = Rose200)
                                Text(
                                    "الخطوة التي توقف عندها: ${log.failedStepTitle}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Red700
                                )
                                Text(
                                    "سبب الفشل: ${log.failureReason ?: "غير محدد"}",
                                    fontSize = 13.sp,
                                    color = Red900
                                )
                                if (!log.failureCategory.isNullOrBlank()) {
                                    Text(
                                        "تصنيف الخطأ: ${log.failureCategory}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Indigo600
                                    )
                                }
                                if (!log.suggestedAction.isNullOrBlank()) {
                                    Surface(
                                        color = Amber50,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Amber700)
                                            Text(
                                                "الحل المقترح: ${log.suggestedAction}",
                                                fontSize = 12.5.sp,
                                                color = Amber900,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Retry Button
                if (!log.isSuccess) {
                    item {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val auto = db.automationDao().getAutomationById(log.automationId)
                                    if (auto != null) {
                                        AutomationExecutionEngine.executeAutomation(context, auto)
                                        onNavigateBack()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo600)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("إعادة محاولة تنفيذ المهمة الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Step-by-Step Execution Timeline Header
                item {
                    Text(
                        "التسلسل الزمني التفصيلي للخطوات (Timeline)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Slate800,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Step Timeline Items
                items(log.stepDetails) { stepDetail ->
                    StepDetailCard(stepDetail)
                }
            }
        }
    }
}

@Composable
private fun StepDetailCard(detail: StepExecutionDetail) {
    val isSuccess = detail.status == StepStatus.SUCCESS
    val isSkipped = detail.status == StepStatus.SKIPPED
    val durationMs = (detail.finishedAt - detail.startedAt).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSuccess -> Color.White
                isSkipped -> Slate100
                else -> Rose50
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isSuccess) Emerald600 else if (isSkipped) Slate400 else Red600),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            detail.stepOrder.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        detail.stepTitle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Slate900
                    )
                }

                Text(
                    "${durationMs}ms",
                    fontSize = 11.sp,
                    color = Slate500
                )
            }

            if (!detail.expectedElement.isNullOrBlank()) {
                Text(
                    "العنصر المتوقع: ${detail.expectedElement}",
                    fontSize = 12.sp,
                    color = Slate600
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "العنصر وُجد: ${if (detail.elementFound) "نعم ✓" else "لا ✗"}",
                    fontSize = 11.sp,
                    color = if (detail.elementFound) Emerald700 else Red700
                )
                Text(
                    "الإجراء نُفذ: ${if (detail.actionAttempted) "نعم ✓" else "لا ✗"}",
                    fontSize = 11.sp,
                    color = if (detail.actionAttempted) Emerald700 else Red700
                )
                Text(
                    "تأكيد النتيجة: ${if (detail.actionConfirmed) "مؤكد ✓" else "غير مؤكد ✗"}",
                    fontSize = 11.sp,
                    color = if (detail.actionConfirmed) Emerald700 else Red700
                )
            }

            if (!detail.message.isNullOrBlank()) {
                Text(
                    detail.message,
                    fontSize = 12.sp,
                    color = if (isSuccess) Slate600 else Red800,
                    fontWeight = if (isSuccess) FontWeight.Normal else FontWeight.Medium
                )
            }
        }
    }
}

private fun exportSanitizedReport(context: Context, log: ExecutionLog) {
    val report = buildString {
        appendLine("=== Voice Agent Execution Diagnostic Report ===")
        appendLine("Workflow: ${log.automationName}")
        appendLine("Date: ${Date(log.startedAt)}")
        appendLine("Status: ${if (log.isSuccess) "SUCCESS" else "FAILED"}")
        appendLine("Completed: ${log.completedStepsCount}/${log.totalStepsCount} steps")
        if (!log.isSuccess) {
            appendLine("Failed Step: ${log.failedStepTitle}")
            appendLine("Failure Category: ${log.failureCategory}")
            appendLine("Failure Reason: ${log.failureReason}")
            appendLine("Suggested Action: ${log.suggestedAction}")
        }
        appendLine("\n--- Step Timeline ---")
        log.stepDetails.forEach { s ->
            appendLine("#${s.stepOrder} [${s.status}] ${s.stepTitle} - ${s.message}")
        }
        appendLine("\nNote: Sanitized report. No passwords, credentials, or personal data included.")
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Voice Agent Diagnostic: ${log.automationName}")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة تقرير التشخيص"))
}
