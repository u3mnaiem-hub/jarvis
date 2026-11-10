package com.voiceagent.automation.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.voiceagent.automation.model.ExecutionLog
import com.voiceagent.automation.model.StepStatus
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ExecutionHistoryScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    val logs by db.executionLogDao().getAllLogsFlow().collectAsState(initial = emptyList())

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "سجل المهام والتشخيص",
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
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = {
                            coroutineScope.launch {
                                db.executionLogDao().clearAllLogs()
                            }
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All", tint = Red600)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(56.dp), tint = LightTextSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("لا توجد عمليات تنفيذ سابقة حتى الآن", fontWeight = FontWeight.Bold, color = LightTextSecondary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    ExecutionLogCard(log = log, dateFormat = dateFormat)
                }
            }
        }
    }
}

@Composable
fun ExecutionLogCard(log: ExecutionLog, dateFormat: SimpleDateFormat) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (log.isSuccess) Emerald100 else Red100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (log.isSuccess) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (log.isSuccess) Emerald600 else Red600,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = log.automationName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(dateFormat.format(Date(log.startedAt)), fontSize = 11.sp, color = LightTextSecondary)
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Badge(containerColor = if (log.isSuccess) Emerald100 else Red100) {
                    Text(
                        text = if (log.isSuccess) "نجحت ✓" else "فشلت ✗",
                        color = if (log.isSuccess) Emerald600 else Red600,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "الخطوات المكتملة: ${log.completedStepsCount} من ${log.totalStepsCount}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = LightTextSecondary
            )

            if (!log.isSuccess && !log.failureReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "سبب الإخفاق: ${log.failureReason} (عند: ${log.failedStepTitle ?: "غير محدد"})",
                    fontSize = 11.5.sp,
                    color = Red600,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Expand / Collapse Chevron indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpanded) "إخفاء التفاصيل" else "عرض تدقيق الخطوات التفصيلي (Step Audit)",
                    fontSize = 11.5.sp,
                    color = Blue600,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Blue600,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Step by step breakdown list
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    log.stepDetails.forEach { detail ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (detail.status) {
                                    StepStatus.SUCCESS -> "✓"
                                    StepStatus.FAILED -> "✗"
                                    else -> "•"
                                },
                                color = if (detail.status == StepStatus.SUCCESS) Emerald600 else Red600,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${detail.stepOrder}. ${detail.stepTitle}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
