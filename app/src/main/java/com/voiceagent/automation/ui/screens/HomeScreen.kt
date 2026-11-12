package com.voiceagent.automation.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.engine.ExecutionState
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.security.SafeModeManager
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.ui.theme.*
import com.voiceagent.automation.voice.CommandMatchingEngine
import com.voiceagent.automation.voice.MatchResult
import com.voiceagent.automation.voice.VoiceRecognitionManager
import com.voiceagent.automation.voice.VoiceState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    autoListenTrigger: Int = 0,
    onNavigateToCreate: () -> Unit,
    onNavigateToMyAutomations: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToBankingProtection: () -> Unit = {},
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToFailureDetails: (String) -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }

    val automations by db.automationDao().getAllAutomationsFlow().collectAsState(initial = emptyList())
    val latestLog by db.executionLogDao().getLatestLogFlow().collectAsState(initial = null)
    val executionState by AutomationExecutionEngine.executionState.collectAsState()
    val isServiceConnected by VoiceAccessibilityService.isServiceConnected.collectAsState()

    // Voice recognition manager
    val voiceManager = remember { VoiceRecognitionManager(context) }
    val voiceState by voiceManager.voiceState.collectAsState()

    DisposableEffect(voiceManager) {
        onDispose {
            voiceManager.stopListening()
        }
    }

    var matchedAutomation by remember { mutableStateOf<Automation?>(null) }
    var matchConfidenceText by remember { mutableStateOf<String?>(null) }
    var ambiguousOptions by remember { mutableStateOf<List<Automation>>(emptyList()) }

    // Audio permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.startListening(SafeModeManager.getVoiceLanguage(context))
        }
    }

    // Automatically start listening when opened via Google Assistant / deep link / app launch
    LaunchedEffect(autoListenTrigger) {
        if (autoListenTrigger > 0) {
            kotlinx.coroutines.delay(350)
            val hasAudioPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasAudioPerm) {
                voiceManager.startListening(SafeModeManager.getVoiceLanguage(context))
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // React to voice recognition success
    LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Success) {
            val spokenText = (voiceState as VoiceState.Success).recognizedText
            val lower = spokenText.trim().lowercase()

            // Check if spoken command is a direct expense logging command
            val isFinanceCommand = lower.contains("سجل مصروف") || lower.contains("سجل مصاريف") ||
                    lower.contains("أضف مصروف") || lower.contains("اضف مصروف") ||
                    (lower.startsWith("سجل ") && (lower.contains("جنيه") || lower.any { it.isDigit() }))

            if (isFinanceCommand) {
                val regex = Regex("(\\d+(?:\\.\\d+)?)")
                val match = regex.find(lower)
                val amount = match?.value?.toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    val note = lower.replace(regex, "")
                        .replace("سجل", "")
                        .replace("مصروف", "")
                        .replace("مصاريف", "")
                        .replace("جنيه", "")
                        .replace("أضف", "")
                        .replace("اضف", "")
                        .trim()
                    db.expenseTransactionDao().insertTransaction(
                        com.voiceagent.automation.model.ExpenseTransaction(
                            amount = amount,
                            type = "expense",
                            category = "عام",
                            note = note.ifBlank { "مصروف صوتي" }
                        )
                    )
                    com.voiceagent.automation.voice.TextToSpeechManager.speak("تم تسجيل مصروف $amount جنيه بنجاح")
                    matchedAutomation = null
                    ambiguousOptions = emptyList()
                    matchConfidenceText = "تم تسجيل المصروف في مصروفي: $amount ج.م (${note.ifBlank { "عام" }})"
                    return@LaunchedEffect
                }
            }

            val result = CommandMatchingEngine.matchCommand(spokenText, automations.filter { it.isEnabled })
            when (result) {
                is MatchResult.ExactMatch -> {
                    matchedAutomation = result.automation
                    matchConfidenceText = "Exact Match (تطابق تام)"
                    ambiguousOptions = emptyList()
                    // Auto-execute if enabled
                    AutomationExecutionEngine.executeAutomation(context, result.automation)
                }
                is MatchResult.HighConfidenceMatch -> {
                    matchedAutomation = result.automation
                    matchConfidenceText = "Matched ${(result.score * 100).toInt()}%: ${result.automation.name}"
                    ambiguousOptions = emptyList()
                }
                is MatchResult.AmbiguousMatch -> {
                    ambiguousOptions = result.candidateAutomations
                    matchedAutomation = null
                    matchConfidenceText = "Multiple matching automations found. Which one did you mean?"
                }
                is MatchResult.NoMatch -> {
                    matchedAutomation = null
                    matchConfidenceText = "No configured automation matches: \"$spokenText\""
                    ambiguousOptions = emptyList()
                }
            }
        }
    }

    // Pulse animation for listening state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val micScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (voiceState is VoiceState.Listening) 1.22f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 16.dp, bottom = 90.dp)
    ) {
        // Banking Protection & Safe Mode Header Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clickable { onNavigateToBankingProtection() },
                colors = CardDefaults.cardColors(containerColor = Emerald50),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Emerald600),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "منع أتمتة إنستاباي والبنوك نشط",
                                fontWeight = FontWeight.Bold,
                                color = Emerald900,
                                fontSize = 13.sp
                            )
                            Surface(color = Emerald600, shape = RoundedCornerShape(4.dp)) {
                                Text("Safe Mode", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        Text(
                            text = "لا يتدخل في تطبيقات البنوك. لتشغيل إنستاباي قد تحتاج لإيقاف الـ Accessibility مؤقتاً.",
                            color = Slate600,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            color = Emerald100,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.AccessibilityNew, contentDescription = null, tint = Emerald700, modifier = Modifier.size(14.dp))
                                Text(
                                    text = if (isServiceConnected) "إيقاف/إعدادات الـ Accessibility مؤقتاً" else "تفعيل الـ Accessibility",
                                    color = Emerald900,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Emerald700)
                }
            }
        }

        // Active Execution Banner (Mobile-Optimized & Never Cut Off)
        if (executionState is ExecutionState.Running) {
            val running = executionState as ExecutionState.Running
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Amber100),
                    shape = RoundedCornerShape(16.dp),
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
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Amber500
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "جاري تنفيذ: ${running.automation.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { AutomationExecutionEngine.requestStop() },
                                colors = ButtonDefaults.buttonColors(containerColor = Red600),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إيقاف (Stop)", fontSize = 11.5.sp, fontWeight = FontWeight.Black)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "الخطوة ${running.currentStepIndex + 1} من ${running.totalSteps}: ${running.currentStep.title}",
                            fontSize = 11.5.sp,
                            color = LightTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = (running.currentStepIndex + 1).toFloat() / running.totalSteps.coerceAtLeast(1),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            color = Emerald600
                        )
                    }
                }
            }
        }

        // Hero Greeting Card
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Voice Agent",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Hello! What would you like me to do?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = "أهلاً بك! ماذا تريد أن أنفذ لك من مهام مؤتمتة الآن؟",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
        }

        // Giant Central Glow Microphone Button
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .scale(micScale)
            ) {
                // Outer glow ripple
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceState is VoiceState.Listening) Emerald500.copy(alpha = 0.25f) else Color.Transparent
                        )
                )
                // Mid glow ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    if (voiceState is VoiceState.Listening) Red500 else Emerald500,
                                    if (voiceState is VoiceState.Listening) Red600 else Emerald600
                                )
                            )
                        )
                        .clickable {
                            if (voiceState is VoiceState.Listening) {
                                voiceManager.stopListening()
                            } else {
                                val hasAudioPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasAudioPermission) {
                                    voiceManager.startListening(SafeModeManager.getVoiceLanguage(context))
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (voiceState is VoiceState.Listening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = Color.White,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = when (voiceState) {
                    is VoiceState.Listening -> "أنصت لصوتك الآن... تحدث بأمرك"
                    is VoiceState.Processing -> "جاري تحليل الأمر الصوتي..."
                    is VoiceState.Success -> "تم التعرف على: \"${(voiceState as VoiceState.Success).recognizedText}\""
                    is VoiceState.Error -> (voiceState as VoiceState.Error).message
                    else -> "اضغط على المايكروفون وتحدث بأي أمر مسجل"
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (voiceState is VoiceState.Listening) Red500 else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Voice Command Match Card
        item {
            AnimatedVisibility(visible = matchConfidenceText != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = matchConfidenceText ?: "",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (matchedAutomation != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        AutomationExecutionEngine.executeAutomation(context, matchedAutomation!!)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تنفيذ المهمة الآن (Run)", fontSize = 12.sp)
                                }
                            }
                        } else if (ambiguousOptions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ambiguousOptions.forEach { candidate ->
                                    OutlinedButton(
                                        onClick = {
                                            ambiguousOptions = emptyList()
                                            matchedAutomation = candidate
                                            AutomationExecutionEngine.executeAutomation(context, candidate)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Emerald600)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = candidate.nameAr.ifBlank { candidate.name },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Favorite Quick Automations
        val favoriteList = automations.filter { it.isFavorite }
        if (favoriteList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "المهام المفضلة (Favorites)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToMyAutomations) {
                        Text("عرض الكل (${automations.size})", fontSize = 12.sp, color = Emerald600)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(favoriteList) { auto ->
                        Card(
                            modifier = Modifier
                                .width(220.dp)
                                .clickable {
                                    AutomationExecutionEngine.executeAutomation(context, auto)
                                },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Emerald100),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Emerald600)
                                    }
                                    Badge(containerColor = Emerald100) {
                                        Text("${auto.steps.size} خطوات", color = Emerald600, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = auto.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = auto.nameAr.ifBlank { auto.description },
                                    fontSize = 11.5.sp,
                                    color = LightTextSecondary,
                                    maxLines = 2
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { AutomationExecutionEngine.executeAutomation(context, auto) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تشغيل (Run)", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Last Execution Result Card
        if (latestLog != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "آخر تنفيذ (Last Execution)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onNavigateToHistory) {
                        Text("سجل المهام", fontSize = 12.sp, color = Blue600)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (latestLog!!.isSuccess) Emerald100 else Red100
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (latestLog!!.isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (latestLog!!.isSuccess) Emerald600 else Red600,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = latestLog!!.automationName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (latestLog!!.isSuccess) Emerald700 else Red600
                            )
                            Text(
                                text = if (latestLog!!.isSuccess) {
                                    "اكتملت جميع الخطوات بنجاح (${latestLog!!.completedStepsCount}/${latestLog!!.totalStepsCount})"
                                } else {
                                    "فشل عند: ${latestLog!!.failedStepTitle ?: "التحقق"} (${latestLog!!.failureReason ?: "غير معروف"})"
                                },
                                fontSize = 11.5.sp,
                                color = LightTextSecondary
                            )
                        }
                    }
                    if (!latestLog!!.isSuccess) {
                        Button(
                            onClick = { onNavigateToFailureDetails(latestLog!!.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red600)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("عرض تفاصيل الفشل والتشخيص (Failure Details)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Quick Action Buttons (Compact & Responsive on all screen widths)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNavigateToBrowser,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("المتصفح المدمج", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = onNavigateToCreate,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue600),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مهمة جديدة", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onNavigateToMyAutomations,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("المهام", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
