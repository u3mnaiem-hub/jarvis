package com.voiceagent.automation.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voiceagent.automation.service.VoiceAccessibilityService
import com.voiceagent.automation.ui.theme.*

data class PermissionItem(
    val title: String,
    val subtitle: String,
    val featureDescription: String,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val icon: ImageVector,
    val onOpenSettings: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsDashboardScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isMicGranted by remember { mutableStateOf(false) }
    var isAccessibilityGranted by remember { mutableStateOf(false) }
    var isOverlayGranted by remember { mutableStateOf(false) }
    var isNotificationGranted by remember { mutableStateOf(false) }
    var isBatteryOptIgnored by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        isMicGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        isAccessibilityGranted = VoiceAccessibilityService.isAvailable
        isOverlayGranted = Settings.canDrawOverlays(context)
        isNotificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        isBatteryOptIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsList = listOf(
        PermissionItem(
            title = "الميكروفون والتسجيل الصوتي (Microphone)",
            subtitle = "ضروري للأوامر الصوتية",
            featureDescription = "يُستخدم فقط عند الضغط على زر الميكروفون للاستماع إلى أوامرك باللغتين العربية والإنجليزية. لا يتم تسجيل أي محادثات في الخلفية.",
            isGranted = isMicGranted,
            isRequired = true,
            icon = Icons.Default.Mic,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        ),
        PermissionItem(
            title = "خدمة إمكانية الوصول (Accessibility Service)",
            subtitle = "اختياري للأتمتة المتقدمة (Advanced Mode)",
            featureDescription = "تسمح لـ Voice Agent بالنقر والتمرير وإدخال البيانات في التطبيقات الأخرى لتنفيذ مهامك آلياً. في حال تعطيلها، يعمل التطبيق في 'الوضع الآمن' عبر الأوامر والروابط المباشرة.",
            isGranted = isAccessibilityGranted,
            isRequired = false,
            icon = Icons.Default.Accessibility,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }
        ),
        PermissionItem(
            title = "الظهور فوق التطبيقات (Display Over Other Apps)",
            subtitle = "اختياري لشريط تسجيل المهام العائم",
            featureDescription = "تسمح بإظهار شريط عائم صغير لإيقاف أو متابعة تسجيل الخطوات أثناء تنقلك بين التطبيقات. يتم إخفاؤه تلقائياً عند فتح أي تطبيق بنكي محمي.",
            isGranted = isOverlayGranted,
            isRequired = false,
            icon = Icons.Default.Layers,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        ),
        PermissionItem(
            title = "إرسال الإشعارات (Notifications)",
            subtitle = "ضروري لمتابعة تقدم المهام",
            featureDescription = "تسمح بعرض إشعار في شريط الحالة يوضح اسم المهمة الجاري تنفيذها ورقم الخطوة الحالية وزر الإيقاف الطارئ.",
            isGranted = isNotificationGranted,
            isRequired = true,
            icon = Icons.Default.Notifications,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        ),
        PermissionItem(
            title = "استثناء توفير الطاقة (Battery Optimization)",
            subtitle = "موصى به لاستقرار المهام الطويلة",
            featureDescription = "يمنع نظام أندرويد من إيقاف الخدمة أثناء تنفيذ خطوات الأتمتة ذات التوقيتات الطويلة.",
            isGranted = isBatteryOptIgnored,
            isRequired = false,
            icon = Icons.Default.BatteryChargingFull,
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            }
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "لوحة تحكم الصلاحيات (Permissions)",
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
            item {
                Surface(
                    color = Slate50,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "نحن نؤمن بالشفافية والخصوصية التامة. جميع الصلاحيات التالية مشروحة بوضوح، ويمكنك استخدام التطبيق في الوضع الآمن حتى لو رفضت الصلاحيات الاختيارية.",
                        fontSize = 12.sp,
                        color = Slate600,
                        modifier = Modifier.padding(14.dp),
                        lineHeight = 18.sp
                    )
                }
            }

            items(permissionsList.size) { index ->
                val item = permissionsList[index]
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (item.isGranted) Emerald50 else Slate100),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        item.icon,
                                        contentDescription = null,
                                        tint = if (item.isGranted) Emerald600 else Slate500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Slate900)
                                    Text(item.subtitle, fontSize = 11.5.sp, color = Slate500)
                                }
                            }

                            Surface(
                                color = if (item.isGranted) Emerald600 else if (item.isRequired) Red600 else Amber500,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    if (item.isGranted) "مفعل ✓" else if (item.isRequired) "مطلوب ✗" else "اختياري",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Text(
                            item.featureDescription,
                            fontSize = 12.sp,
                            color = Slate600,
                            lineHeight = 17.sp
                        )

                        Button(
                            onClick = item.onOpenSettings,
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (item.isGranted) Slate100 else Indigo600,
                                contentColor = if (item.isGranted) Slate700 else Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (item.isGranted) "تعديل في الإعدادات" else "تفعيل الصلاحية في الإعدادات",
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
