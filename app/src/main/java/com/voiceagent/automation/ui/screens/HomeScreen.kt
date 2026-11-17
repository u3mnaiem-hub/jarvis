package com.voiceagent.automation.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.shadow
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
import com.voiceagent.automation.security.RouterCredentialsHelper
import com.voiceagent.automation.security.SafeModeManager
import com.voiceagent.automation.service.AccessibilityControlHelper
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.ui.theme.*
import com.voiceagent.automation.voice.CommandMatchingEngine
import com.voiceagent.automation.voice.MatchResult
import com.voiceagent.automation.voice.TextToSpeechManager
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
                    if (result.score > 0.80) {
                        AutomationExecutionEngine.executeAutomation(context, result.automation)
                    }
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
            .background(
                Brush.verticalGradient(
                    colors = listOf(GlassBgTop, GlassBgMid, GlassBgBottom)
                )
            )
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp)
    ) {
        // 1. Compact Banking Protection & Accessibility Control Bar
        item {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clickable { onNavigateToBankingProtection() },
                cornerRadius = 16.dp,
                borderColor = Emerald500.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Emerald500, Emerald700))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "حماية البنوك وإنستاباي",
                            fontWeight = FontWeight.Bold,
                            color = LightTextPrimary,
                            fontSize = 12.5.sp
                        )
                        Text(
                            text = if (isServiceConnected) "Accessibility (مفعّل)" else "Accessibility (متوقف - آمن)",
                            color = if (isServiceConnected) Red600 else Emerald700,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        onClick = {
                            if (isServiceConnected) {
                                AccessibilityControlHelper.disableNow(context)
                            } else {
                                AccessibilityControlHelper.enableOrOpenSettings(context)
                            }
                        },
                        color = if (isServiceConnected) Red500.copy(alpha = 0.14f) else Emerald500.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isServiceConnected) Red500.copy(alpha = 0.4f) else Emerald500.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isServiceConnected) Icons.Default.PowerSettingsNew else Icons.Default.AccessibilityNew,
                                contentDescription = null,
                                tint = if (isServiceConnected) Red600 else Emerald700,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (isServiceConnected) "إيقاف" else "تفعيل",
                                color = if (isServiceConnected) Red600 else Emerald700,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 2. ZTE H188 Router Full Control Card (Top of Screen after Banking Protection)
        item {
            var routerStatusText by remember { mutableStateOf<String?>(null) }
            var routerDevices by remember {
                mutableStateOf<List<com.voiceagent.automation.engine.ZteRouterHttpClient.ParentalControlDevice>>(emptyList())
            }
            var isRouterBusy by remember { mutableStateOf(false) }
            var showRouterCredDialog by remember { mutableStateOf(false) }
            val prefs = remember { context.getSharedPreferences("voice_agent_prefs", android.content.Context.MODE_PRIVATE) }
            var routerUserDraft by remember { mutableStateOf(prefs.getString("router_admin_user", "") ?: "") }
            var routerPassDraft by remember { mutableStateOf("") }

            fun triggerQuickRouterMacro(deviceName: String, radioVal: String) {
                val label = if (radioVal == "0") "تشغيل النت: $deviceName" else "قفل النت: $deviceName"
                val quickAuto = Automation(
                    name = label,
                    nameAr = label,
                    description = "ZTE H188 Hybrid HTTP + Native JS Macro for $deviceName",
                    voicePhrases = listOf(label),
                    steps = listOf(
                        com.voiceagent.automation.model.AutomationStep(
                            order = 1,
                            title = "Open Router 192.168.1.1",
                            actionType = com.voiceagent.automation.model.ActionType.OPEN_URL,
                            isWebStep = true,
                            url = "https://192.168.1.1/"
                        ),
                        com.voiceagent.automation.model.AutomationStep(
                            order = 2,
                            title = "Toggle $deviceName -> $radioVal",
                            actionType = com.voiceagent.automation.model.ActionType.SET_INDEXED_RADIO,
                            isWebStep = true,
                            rowContextText = deviceName,
                            selectorResourceId = "instName_ParentCtrl:{i}",
                            selectorText = deviceName,
                            indexedNamePattern = "[id='instName_ParentCtrl:{index}']",
                            indexedTargetPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
                            targetRadioValue = radioVal,
                            textPayload = "Enable:ParentCtrl:{i}|$radioVal"
                        )
                    )
                )
                AutomationExecutionEngine.executeAutomation(context, quickAuto)
            }

            if (showRouterCredDialog) {
                AlertDialog(
                    onDismissRequest = { showRouterCredDialog = false },
                    title = {
                        Text("إعداد بيانات دخول الراوتر (ZTE H188A)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "تُحفظ البيانات مشفرة محلياً على هاتفك فقط لاستخدامها في الدخول التلقائي للراوتر.",
                                fontSize = 11.5.sp,
                                color = LightTextSecondary
                            )
                            OutlinedTextField(
                                value = routerUserDraft,
                                onValueChange = { routerUserDraft = it },
                                label = { Text("اسم المستخدم (Username)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = routerPassDraft,
                                onValueChange = { routerPassDraft = it },
                                label = { Text("كلمة المرور (Password)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (routerUserDraft.isNotBlank() && routerPassDraft.isNotBlank()) {
                                    val userToSave = routerUserDraft
                                    val passToSave = routerPassDraft
                                    coroutineScope.launch {
                                        val savedUser = RouterCredentialsHelper.saveRouterCredentials(
                                            context, db, userToSave, passToSave
                                        )
                                        routerStatusText = "تم حفظ بيانات دخول الراوتر ($savedUser) مشفرة بنجاح!"
                                    }
                                    routerPassDraft = ""
                                    showRouterCredDialog = false
                                } else {
                                    routerStatusText = "خطأ: يرجى إدخال اسم المستخدم وكلمة المرور معاً."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600)
                        ) {
                            Text("حفظ مشفر")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRouterCredDialog = false }) {
                            Text("إلغاء")
                        }
                    }
                )
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                cornerRadius = 16.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Emerald500, Emerald700))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Router, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تحكم كامل في الراوتر",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = LightTextPrimary
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { showRouterCredDialog = true },
                                color = Amber500.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Amber500.copy(alpha = 0.4f))
                            ) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = "بيانات الراوتر",
                                    tint = Amber700,
                                    modifier = Modifier.padding(6.dp).size(15.dp)
                                )
                            }
                            Surface(
                                onClick = {
                                    if (!isRouterBusy) {
                                        isRouterBusy = true
                                        routerStatusText = "جاري الاتصال بالراوتر 192.168.1.1..."
                                        coroutineScope.launch {
                                            try {
                                                val creds = RouterCredentialsHelper.getRouterCredentials(context, db)
                                                if (creds == null) {
                                                    routerStatusText = RouterCredentialsHelper.MISSING_CREDENTIALS_ERROR
                                                } else {
                                                    val (client, loginRes) = AutomationExecutionEngine.loginToZteRouter(
                                                        db = db,
                                                        username = creds.first
                                                    )
                                                    when (loginRes) {
                                                        is com.voiceagent.automation.engine.ZteRouterHttpClient.RouterLoginResult.Success -> {
                                                            val (_, devices) = client.fetchParentalControlDevices()
                                                            client.logout()
                                                            routerDevices = devices
                                                            routerStatusText = if (devices.isNotEmpty()) {
                                                                "تم الدخول بنجاح! (${devices.size} جهاز)"
                                                            } else {
                                                                "تم الدخول للراوتر بنجاح!"
                                                            }
                                                        }
                                                        is com.voiceagent.automation.engine.ZteRouterHttpClient.RouterLoginResult.Failure -> {
                                                            routerStatusText = "خطأ: ${loginRes.message}"
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                routerStatusText = "خطأ أثناء الفحص: ${e.message}"
                                            } finally {
                                                isRouterBusy = false
                                            }
                                        }
                                    }
                                },
                                color = Emerald500.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isRouterBusy) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Emerald700)
                                    }
                                    Text("فحص", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Emerald700)
                                }
                            }
                        }
                    }

                    if (routerStatusText != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = routerStatusText!!,
                            fontSize = 11.sp,
                            color = if (routerStatusText!!.contains("خطأ") || routerStatusText!!.contains("لا توجد")) Red600 else LightTextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (routerDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        routerDevices.forEach { dev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${dev.index}: ${dev.name} (${if (dev.currentEnable == "1") "مقفل" else "مفتوح"})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { triggerQuickRouterMacro(dev.name, "0") }) {
                                        Text("تشغيل", color = Emerald600, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { triggerQuickRouterMacro(dev.name, "1") }) {
                                        Text("قفل", color = Red600, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
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
                        .padding(bottom = 16.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(16.dp),
                            ambientColor = GlassShadowColor.copy(alpha = 0.45f),
                            spotColor = GlassShadowColor.copy(alpha = 0.55f)
                        ),
                    colors = CardDefaults.cardColors(containerColor = Amber100),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "JARVIS AI ASSISTANT",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "أهلاً بك، أنا جاهز لأوامرك",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "اضغط على المايكروفون وتحدث لتنفيذ مهامك تلقائياً",
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(22.dp))
        }

        // Giant Central Glow Microphone Orb
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(168.dp)
                    .scale(micScale)
            ) {
                // Outer ambient halo
                Box(
                    modifier = Modifier
                        .size(168.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceState is VoiceState.Listening) Red500.copy(alpha = 0.18f)
                            else Emerald500.copy(alpha = 0.10f)
                        )
                )
                // Inner soft ring
                Box(
                    modifier = Modifier
                        .size(142.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceState is VoiceState.Listening) Red500.copy(alpha = 0.24f)
                            else Emerald500.copy(alpha = 0.16f)
                        )
                )
                // Main Elevated Orb Button
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = CircleShape,
                            ambientColor = if (voiceState is VoiceState.Listening) Red500 else Emerald500,
                            spotColor = if (voiceState is VoiceState.Listening) Red500 else Emerald500
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    if (voiceState is VoiceState.Listening) Red500 else Emerald500,
                                    if (voiceState is VoiceState.Listening) Red700 else Indigo600
                                )
                            )
                        )
                        .clickable {
                            if (voiceState is VoiceState.Listening) {
                                voiceManager.stopListening()
                            } else {
                                val a11yDisabled = !isServiceConnected
                                if (a11yDisabled) {
                                    TextToSpeechManager.speak("خدمة إمكانية الوصول غير مفعلة", isArabic = true)
                                }
                                val hasAudioPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasAudioPermission) {
                                    if (a11yDisabled) {
                                        coroutineScope.launch {
                                            kotlinx.coroutines.delay(1600)
                                            voiceManager.startListening(SafeModeManager.getVoiceLanguage(context))
                                        }
                                    } else {
                                        voiceManager.startListening(SafeModeManager.getVoiceLanguage(context))
                                    }
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
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = if (voiceState is VoiceState.Listening) Red500.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(50),
                shadowElevation = 2.dp,
                border = BorderStroke(
                    1.dp,
                    if (voiceState is VoiceState.Listening) Red500.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = when (voiceState) {
                        is VoiceState.Listening -> "🎙️ أنصت لصوتك الآن... تحدث بأمرك"
                        is VoiceState.Processing -> "⏳ جاري تحليل الأمر الصوتي..."
                        is VoiceState.Success -> "✅ \"${(voiceState as VoiceState.Success).recognizedText}\""
                        is VoiceState.Error -> (voiceState as VoiceState.Error).message
                        else -> "اضغط للتحدث مع جارفس"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (voiceState is VoiceState.Listening) Red500 else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Voice Command Match Card
        item {
            AnimatedVisibility(visible = matchConfidenceText != null) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    cornerRadius = 16.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = matchConfidenceText ?: "",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LightTextPrimary
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
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Built-in / Favorite Quick Automations (Vertical, Equal Width, Full Text Visible)
        val favoriteList = automations.filter { it.isFavorite }
        if (favoriteList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "الأوامر والمهام الجاهزة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LightTextPrimary
                    )
                    TextButton(onClick = onNavigateToMyAutomations) {
                        Text("عرض الكل (${automations.size})", fontSize = 12.sp, color = Emerald600, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            items(favoriteList) { auto ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clickable {
                            AutomationExecutionEngine.executeAutomation(context, auto)
                        },
                    cornerRadius = 14.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Emerald100),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = Emerald600, modifier = Modifier.size(17.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = auto.nameAr.ifBlank { auto.name },
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = LightTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    db.automationDao().insertOrUpdate(auto.copy(isFavorite = false))
                                    Toast.makeText(context, "تم إخفاء المهمة من الرئيسية (متاحة في 'عرض الكل')", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "إخفاء المهمة",
                                tint = LightTextSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = { AutomationExecutionEngine.executeAutomation(context, auto) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("تشغيل", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
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

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    containerColor = if (latestLog!!.isSuccess) Emerald50.copy(alpha = 0.82f) else Rose50.copy(alpha = 0.85f),
                    borderColor = if (latestLog!!.isSuccess) Emerald500.copy(alpha = 0.4f) else Red500.copy(alpha = 0.4f)
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

        // Quick Action Buttons (Modern Elevated Glass Pills)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onNavigateToBrowser,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .shadow(
                            10.dp,
                            RoundedCornerShape(14.dp),
                            ambientColor = GlassShadowColor.copy(alpha = 0.45f),
                            spotColor = GlassShadowColor.copy(alpha = 0.55f)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("المتصفح المدمج", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = onNavigateToCreate,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .shadow(
                            10.dp,
                            RoundedCornerShape(14.dp),
                            ambientColor = GlassShadowColor.copy(alpha = 0.45f),
                            spotColor = GlassShadowColor.copy(alpha = 0.55f)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مهمة جديدة", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onNavigateToMyAutomations,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .shadow(
                            10.dp,
                            RoundedCornerShape(14.dp),
                            ambientColor = GlassShadowColor.copy(alpha = 0.45f),
                            spotColor = GlassShadowColor.copy(alpha = 0.55f)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = GlassCardSurface),
                    border = BorderStroke(1.2.dp, GlassCardBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("المهام", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    containerColor: Color = GlassCardSurface,
    borderColor: Color = GlassCardBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Card(
        modifier = modifier.shadow(
            elevation = 14.dp,
            shape = shape,
            ambientColor = GlassShadowColor.copy(alpha = 0.45f),
            spotColor = GlassShadowColor.copy(alpha = 0.55f)
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.2.dp, borderColor),
        content = content
    )
}
