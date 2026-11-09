package com.voiceagent.automation.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.model.*
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun WorkflowEditorScreen(
    automationId: String?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }

    var name by remember { mutableStateOf("") }
    var nameAr by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Internet & Router") }
    var voicePhrasesText by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf<List<AutomationStep>>(emptyList()) }
    var isFavorite by remember { mutableStateOf(false) }

    // Dialog state for adding or editing a step
    var isStepDialogVisible by remember { mutableStateOf(false) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }
    var currentEditingStep by remember { mutableStateOf(AutomationStep()) }

    // Load existing automation if editing
    LaunchedEffect(automationId) {
        if (!automationId.isNullOrBlank() && automationId != "new") {
            val existing = db.automationDao().getAutomationById(automationId)
            if (existing != null) {
                name = existing.name
                nameAr = existing.nameAr
                description = existing.description
                category = existing.category
                voicePhrasesText = existing.voicePhrases.joinToString("\n")
                steps = existing.steps
                isFavorite = existing.isFavorite
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (automationId == null || automationId == "new") "إنشاء مهمة جديدة" else "تعديل المهمة") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (name.isBlank()) {
                            Toast.makeText(context, "يرجى إدخال اسم المهمة", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        val phrases = voicePhrasesText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                        val auto = Automation(
                            id = if (automationId.isNullOrBlank() || automationId == "new") UUID.randomUUID().toString() else automationId,
                            name = name.trim(),
                            nameAr = nameAr.trim(),
                            description = description.trim(),
                            category = category,
                            voicePhrases = phrases,
                            steps = steps.mapIndexed { idx, s -> s.copy(order = idx + 1) },
                            isFavorite = isFavorite
                        )
                        coroutineScope.launch {
                            db.automationDao().insertOrUpdate(auto)
                            Toast.makeText(context, "تم حفظ المهمة بنجاح", Toast.LENGTH_SHORT).show()
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = Emerald600)
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingStepIndex = null
                    currentEditingStep = AutomationStep(order = steps.size + 1)
                    isStepDialogVisible = true
                },
                containerColor = Blue600,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("إضافة خطوة (Add Step)", fontWeight = FontWeight.Bold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // Basic Info Cards
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("بيانات المهمة الأساسية", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("اسم المهمة بالإنجليزي (e.g. Enable TV Internet)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = nameAr,
                            onValueChange = { nameAr = it },
                            label = { Text("اسم المهمة بالعربي (مثال: تشغيل نت التلفزيون)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = voicePhrasesText,
                            onValueChange = { voicePhrasesText = it },
                            label = { Text("العبارات الصوتية المنشطة (عبارة في كل سطر)") },
                            placeholder = { Text("شغل النت للتلفزيون\nافتح نت الشاشة\nEnable TV internet") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("وصف المهمة (اختياري)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Steps Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "خطوات التنفيذ المتتالية (${steps.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (steps.isNotEmpty()) {
                        Button(
                            onClick = {
                                val testAuto = Automation(
                                    name = name.ifBlank { "Test Automation" },
                                    steps = steps
                                )
                                AutomationExecutionEngine.executeAutomation(context, testAuto)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اختبار المهمة (Test)", fontSize = 11.5.sp)
                        }
                    }
                }
            }

            // Steps List
            if (steps.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("لا توجد خطوات بعد. اضغط على زر (+ إضافة خطوة) لإضافة أول إجراء.", color = LightTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                itemsIndexed(steps) { index, step ->
                    StepItemCard(
                        step = step,
                        index = index,
                        onEdit = {
                            editingStepIndex = index
                            currentEditingStep = step
                            isStepDialogVisible = true
                        },
                        onDelete = {
                            val mutable = steps.toMutableList()
                            mutable.removeAt(index)
                            steps = mutable.mapIndexed { idx, s -> s.copy(order = idx + 1) }
                        },
                        onMoveUp = if (index > 0) {
                            {
                                val mutable = steps.toMutableList()
                                val temp = mutable[index]
                                mutable[index] = mutable[index - 1]
                                mutable[index - 1] = temp
                                steps = mutable.mapIndexed { idx, s -> s.copy(order = idx + 1) }
                            }
                        } else null,
                        onMoveDown = if (index < steps.size - 1) {
                            {
                                val mutable = steps.toMutableList()
                                val temp = mutable[index]
                                mutable[index] = mutable[index + 1]
                                mutable[index + 1] = temp
                                steps = mutable.mapIndexed { idx, s -> s.copy(order = idx + 1) }
                            }
                        } else null
                    )
                }
            }
        }
    }

    // Add / Edit Step Dialog
    if (isStepDialogVisible) {
        StepEditorDialog(
            initialStep = currentEditingStep,
            onDismiss = { isStepDialogVisible = false },
            onSave = { updatedStep ->
                val mutable = steps.toMutableList()
                if (editingStepIndex != null && editingStepIndex in mutable.indices) {
                    mutable[editingStepIndex!!] = updatedStep
                } else {
                    mutable.add(updatedStep)
                }
                steps = mutable.mapIndexed { idx, s -> s.copy(order = idx + 1) }
                isStepDialogVisible = false
            }
        )
    }
}

@Composable
fun StepItemCard(
    step: AutomationStep,
    index: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
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
            // Step Number Badge
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Blue100),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", fontWeight = FontWeight.Bold, color = Blue600, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(containerColor = Emerald100) {
                        Text(step.actionType.labelEn, color = Emerald600, fontSize = 10.sp)
                    }
                    if (step.delayAfterMs > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("⏳ ${step.delayAfterMs}ms", fontSize = 10.sp, color = LightTextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(step.title.ifBlank { step.actionType.labelEn }, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                Text(
                    text = when (step.actionType) {
                        ActionType.LAUNCH_APP -> "App: ${step.targetPackage ?: "None"}"
                        ActionType.OPEN_URL -> "URL: ${step.url ?: "None"}"
                        ActionType.SET_TEXT -> "Text: \"${step.textPayload ?: ""}\""
                        ActionType.INJECT_CREDENTIAL -> "Keystore Password Key: ${step.credentialKey ?: "None"}"
                        ActionType.CLICK_ELEMENT -> "Target: ${step.selectorText ?: step.selectorResourceId ?: "Coords"}"
                        ActionType.WAIT_FOR_ELEMENT -> "Wait for: ${step.selectorText ?: step.selectorResourceId}"
                        ActionType.SPEAK_TEXT -> "Speak: \"${step.textPayload ?: ""}\""
                        else -> step.description
                    },
                    fontSize = 11.5.sp,
                    color = LightTextSecondary,
                    maxLines = 1
                )
            }

            // Move and Delete controls
            Row {
                if (onMoveUp != null) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                if (onMoveDown != null) {
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Blue600, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Red600, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun StepEditorDialog(
    initialStep: AutomationStep,
    onDismiss: () -> Unit,
    onSave: (AutomationStep) -> Unit
) {
    var title by remember { mutableStateOf(initialStep.title) }
    var selectedActionType by remember { mutableStateOf(initialStep.actionType) }
    var targetPackage by remember { mutableStateOf(initialStep.targetPackage ?: "") }
    var url by remember { mutableStateOf(initialStep.url ?: "") }
    var selectorText by remember { mutableStateOf(initialStep.selectorText ?: "") }
    var selectorResourceId by remember { mutableStateOf(initialStep.selectorResourceId ?: "") }
    var textPayload by remember { mutableStateOf(initialStep.textPayload ?: "") }
    var credentialKey by remember { mutableStateOf(initialStep.credentialKey ?: "") }
    var delayAfterMs by remember { mutableStateOf(initialStep.delayAfterMs.toString()) }
    var timeoutSeconds by remember { mutableStateOf(initialStep.timeoutSeconds.toString()) }

    var expandedTypeDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الخطوة (Edit Step)", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("عنوان الخطوة (Title)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Action Type Selector
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedTypeDropdown = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("نوع الإجراء: ${selectedActionType.labelEn} (${selectedActionType.labelAr})")
                        }
                        DropdownMenu(
                            expanded = expandedTypeDropdown,
                            onDismissRequest = { expandedTypeDropdown = false }
                        ) {
                            ActionType.values().forEach { action ->
                                DropdownMenuItem(
                                    text = { Text("${action.labelEn} - ${action.labelAr}") },
                                    onClick = {
                                        selectedActionType = action
                                        expandedTypeDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                when (selectedActionType) {
                    ActionType.LAUNCH_APP -> {
                        item {
                            OutlinedTextField(
                                value = targetPackage,
                                onValueChange = { targetPackage = it },
                                label = { Text("اسم حزمة التطبيق (e.g. org.mozilla.firefox)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    ActionType.OPEN_URL -> {
                        item {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("رابط الموقع (e.g. 192.168.1.1)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    ActionType.SET_TEXT, ActionType.SPEAK_TEXT -> {
                        item {
                            OutlinedTextField(
                                value = textPayload,
                                onValueChange = { textPayload = it },
                                label = { Text("النص المراد إدخاله / نطقه") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    ActionType.INJECT_CREDENTIAL -> {
                        item {
                            OutlinedTextField(
                                value = credentialKey,
                                onValueChange = { credentialKey = it },
                                label = { Text("مفتاح كلمة المرور بالخزينة (e.g. router_admin_pass)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    else -> {}
                }

                if (selectedActionType == ActionType.CLICK_ELEMENT ||
                    selectedActionType == ActionType.WAIT_FOR_ELEMENT ||
                    selectedActionType == ActionType.SET_TEXT ||
                    selectedActionType == ActionType.INJECT_CREDENTIAL
                ) {
                    item {
                        OutlinedTextField(
                            value = selectorText,
                            onValueChange = { selectorText = it },
                            label = { Text("نص العنصر (Button / Field Text)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = selectorResourceId,
                            onValueChange = { selectorResourceId = it },
                            label = { Text("معرف العنصر Resource ID (اختياري)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = delayAfterMs,
                            onValueChange = { delayAfterMs = it },
                            label = { Text("انتظار بعد (ms)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = timeoutSeconds,
                            onValueChange = { timeoutSeconds = it },
                            label = { Text("مهلة الفشل (ثواني)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val updated = initialStep.copy(
                    title = title.ifBlank { selectedActionType.labelEn },
                    actionType = selectedActionType,
                    targetPackage = targetPackage.ifBlank { null },
                    url = url.ifBlank { null },
                    selectorText = selectorText.ifBlank { null },
                    selectorResourceId = selectorResourceId.ifBlank { null },
                    textPayload = textPayload.ifBlank { null },
                    credentialKey = credentialKey.ifBlank { null },
                    delayAfterMs = delayAfterMs.toLongOrNull() ?: 300,
                    timeoutSeconds = timeoutSeconds.toIntOrNull() ?: 10
                )
                onSave(updated)
            }) {
                Text("حفظ الخطوة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
