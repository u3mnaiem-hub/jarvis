package com.voiceagent.automation.ui.screens

import android.widget.Toast
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
import com.voiceagent.automation.security.BankingProtectionManager
import com.voiceagent.automation.security.SafeModeManager
import com.voiceagent.automation.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankingProtectionScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var isProtectionEnabled by remember { mutableStateOf(BankingProtectionManager.isProtectionEnabled(context)) }
    var isSafeModeActive by remember { mutableStateOf(!SafeModeManager.isAdvancedModeEnabled(context)) }
    var protectedList by remember { mutableStateOf(BankingProtectionManager.getProtectedPackages(context).toList().sorted()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newPackageInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "حماية التطبيقات البنكية (Banking Protection)",
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
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Package")
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
            // Main Protection Toggle Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isProtectionEnabled) Emerald50 else Rose50
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(if (isProtectionEnabled) Emerald600 else Red600),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isProtectionEnabled) Icons.Default.Shield else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                                Column {
                                    Text(
                                        "درع حماية إنستاباي والبنوك",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = if (isProtectionEnabled) Emerald900 else Red900
                                    )
                                    Text(
                                        if (isProtectionEnabled) "مفعل (الحماية نشطة)" else "معطل (غير مستحسن)",
                                        fontSize = 12.sp,
                                        color = Slate600
                                    )
                                }
                            }

                            Switch(
                                checked = isProtectionEnabled,
                                onCheckedChange = { checked ->
                                    isProtectionEnabled = checked
                                    BankingProtectionManager.setProtectionEnabled(context, checked)
                                    protectedList = BankingProtectionManager.getProtectedPackages(context).toList().sorted()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Emerald600,
                                    checkedTrackColor = Emerald200
                                )
                            )
                        }

                        Text(
                            "عند تفعيل هذا الخيار، يتوقف Voice Agent تلقائياً وفورياً عن فحص أو تسجيل أو إرسال أي نقرات أو قراءة لأي نافذة تخص تطبيقاً مالياً أو بنكياً (مثل InstaPay، بنك مصر، CIB، الأهلي، المحافظ الإلكترونية)، مما يمنع إغلاق هذه التطبيقات نهائياً ويحافظ على أمان حساباتك.",
                            fontSize = 12.5.sp,
                            color = Slate700,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Safe Mode Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate50)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "الوضع الآمن (Safe Mode)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Slate900
                            )
                            Surface(
                                color = if (isSafeModeActive) Emerald600 else Amber600,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    if (isSafeModeActive) "نشط كافتراضي" else "نمط الأتمتة المتقدمة",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            "في الوضع الآمن، تعمل الأوامر الصوتية، فتح التطبيقات، الروابط المباشرة (مثل مصروفي)، وقراءة الإشعارات بدون الحاجة نهائياً لصلاحية إمكانية الوصول (Accessibility)، وهو النمط الموصى به لضمان استقرار هاتفك 100%.",
                            fontSize = 12.sp,
                            color = Slate600
                        )
                    }
                }
            }

            // Protected Apps Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "التطبيقات المحمية (${protectedList.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Slate800
                    )
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("إضافة تطبيق", fontSize = 12.sp)
                    }
                }
            }

            // List of Protected Packages
            items(protectedList) { pkg ->
                val isDefault = BankingProtectionManager.DEFAULT_PROTECTED_PACKAGES.contains(pkg)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Emerald600, modifier = Modifier.size(18.dp))
                            Column {
                                Text(pkg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Slate800)
                                Text(if (isDefault) "افتراضي مدمج" else "مخصص مضاف", fontSize = 10.5.sp, color = Slate500)
                            }
                        }

                        if (!isDefault) {
                            IconButton(onClick = {
                                BankingProtectionManager.removeCustomPackage(context, pkg)
                                protectedList = BankingProtectionManager.getProtectedPackages(context).toList().sorted()
                                Toast.makeText(context, "تمت إزالة الحزمة", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red600, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Technical Disclaimer
            item {
                Text(
                    "تنبيه تقني: تستخدم هذه الحماية آليات أندرويد الرسمية لفرز حزم التطبيقات ومراقبة النوافذ، ولا تقوم بحقن أي أكواد داخل التطبيقات البنكية.",
                    fontSize = 11.sp,
                    color = Slate500,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة حزمة محمية مخصصة", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل اسم الحزمة (Package Name) للتطبيق المالي المراد حمايته:", fontSize = 12.sp, color = Slate600)
                    OutlinedTextField(
                        value = newPackageInput,
                        onValueChange = { newPackageInput = it },
                        placeholder = { Text("مثال: com.mybank.app", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPackageInput.isNotBlank()) {
                        BankingProtectionManager.addCustomPackage(context, newPackageInput)
                        protectedList = BankingProtectionManager.getProtectedPackages(context).toList().sorted()
                        newPackageInput = ""
                        showAddDialog = false
                        Toast.makeText(context, "تمت الإضافة بنجاح", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("إضافة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
