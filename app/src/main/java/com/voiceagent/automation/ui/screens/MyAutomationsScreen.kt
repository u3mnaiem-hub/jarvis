package com.voiceagent.automation.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationExecutionEngine
import com.voiceagent.automation.model.Automation
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAutomationsScreen(
    onNavigateToEdit: (String) -> Unit,
    onNavigateToCreate: () -> Unit,
    onNavigateToRecord: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    val automations by db.automationDao().getAllAutomationsFlow().collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf(
        "All",
        "Internet & Router",
        "Phone Settings",
        "Daily Tasks",
        "Applications",
        "Custom"
    )

    val filteredAutomations = automations.filter { auto ->
        val matchesCategory = selectedCategory == "All" || auto.category == selectedCategory
        val matchesSearch = searchQuery.isBlank() ||
                auto.name.contains(searchQuery, ignoreCase = true) ||
                auto.nameAr.contains(searchQuery, ignoreCase = true) ||
                auto.voicePhrases.any { it.contains(searchQuery, ignoreCase = true) }
        matchesCategory && matchesSearch
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header with Search and Create buttons (Safe & responsive)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "My Automations",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "مكتبة المهام المؤتمتة (${automations.size})",
                    fontSize = 11.5.sp,
                    color = LightTextSecondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = onNavigateToRecord,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Red100)
                ) {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Record",
                        tint = Red600,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onNavigateToCreate,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Emerald100)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Create",
                        tint = Emerald600,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("بحث بالاسم أو العبارة الصوتية...", fontSize = 12.5.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Category filter chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { cat ->
                val isSelected = cat == selectedCategory
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, fontSize = 11.5.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Emerald600,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // List of Automations
        if (filteredAutomations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(52.dp), tint = LightTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("لا توجد مهام مطابقة للبحث", fontWeight = FontWeight.Bold, color = LightTextSecondary)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
            ) {
                items(filteredAutomations, key = { it.id }) { auto ->
                    AutomationCard(
                        automation = auto,
                        onRun = { AutomationExecutionEngine.executeAutomation(context, auto) },
                        onEdit = { onNavigateToEdit(auto.id) },
                        onToggle = { isEnabled ->
                            coroutineScope.launch {
                                db.automationDao().insertOrUpdate(auto.copy(isEnabled = isEnabled))
                            }
                        },
                        onDuplicate = {
                            coroutineScope.launch {
                                val duplicate = auto.copy(
                                    id = UUID.randomUUID().toString(),
                                    name = "${auto.name} (Copy)",
                                    nameAr = "${auto.nameAr} (نسخة)",
                                    createdAt = System.currentTimeMillis()
                                )
                                db.automationDao().insertOrUpdate(duplicate)
                                Toast.makeText(context, "تم تكرار المهمة بنجاح", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = {
                            coroutineScope.launch {
                                db.automationDao().delete(auto)
                                Toast.makeText(context, "تم حذف المهمة", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onExport = {
                            val json = Gson().toJson(auto)
                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "${auto.name}.json")
                                putExtra(android.content.Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(android.content.Intent.createChooser(shareIntent, "تصدير المهمة"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AutomationCard(
    automation: Automation,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top Row: Icon, Title, Subtitle, and Switch
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
                            .background(if (automation.isEnabled) Emerald100 else LightSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (automation.category == "Internet & Router") Icons.Default.Router else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (automation.isEnabled) Emerald600 else LightTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = automation.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (automation.nameAr.isNotBlank()) {
                            Text(
                                text = automation.nameAr,
                                fontSize = 11.5.sp,
                                color = LightTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = automation.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Emerald600),
                    modifier = Modifier.scale(0.85f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Voice Phrase Badges
            if (automation.voicePhrases.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(automation.voicePhrases.take(4)) { phrase ->
                        AssistChip(
                            onClick = {},
                            label = { Text("🎙️ $phrase", fontSize = 10.sp, maxLines = 1) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Blue100)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Stats row (Steps & Success rate)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${automation.steps.size} خطوات متتالية",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LightTextSecondary
                )
                Text(
                    text = "نسبة النجاح: ${automation.successRate}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (automation.successRate >= 80) Emerald600 else Amber500
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // Bottom Actions Row: Run Button + Compact Action Buttons (Never cut off!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRun,
                    enabled = automation.isEnabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald600),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تشغيل (Run)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Blue600, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Red600, modifier = Modifier.size(18.dp))
                    }
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = LightTextSecondary, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("تكرار المهمة (Duplicate)", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Purple500) },
                                onClick = {
                                    showMenu = false
                                    onDuplicate()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تصدير ومشاركة (Export)", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Blue600) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
