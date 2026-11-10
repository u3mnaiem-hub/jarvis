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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.model.Credential
import com.voiceagent.automation.security.KeystoreManager
import com.voiceagent.automation.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CredentialManagerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember { VoiceAgentDatabase.getInstance(context) }
    val credentials by db.credentialDao().getAllCredentialsFlow().collectAsState(initial = emptyList())

    var isAddDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "خزينة كلمات المرور المشفرة",
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { isAddDialogVisible = true },
                containerColor = Emerald600,
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("إضافة كلمة سر", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Security Info Banner
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Emerald100)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Emerald600, modifier = Modifier.size(30.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "🔒 حماية مشددة: كلمات المرور مشفرة بواسطة أجهزة التشفير المادية للهاتف (Android Keystore AES-256). لا تظهر في السجلات ولا تُرسل لأي خوادم خارجية إطلاقاً.",
                        fontSize = 11.5.sp,
                        color = Emerald700,
                        lineHeight = 16.sp
                    )
                }
            }

            if (credentials.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا توجد بيانات اعتماد محفوظة بعد", color = LightTextSecondary, fontWeight = FontWeight.Bold)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
                ) {
                    items(credentials, key = { it.key }) { cred ->
                        CredentialCard(
                            credential = cred,
                            onDelete = {
                                coroutineScope.launch {
                                    db.credentialDao().delete(cred)
                                    Toast.makeText(context, "تم حذف كلمة المرور من الخزينة", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (isAddDialogVisible) {
        AddCredentialDialog(
            onDismiss = { isAddDialogVisible = false },
            onSave = { key, label, password ->
                coroutineScope.launch {
                    val encrypted = KeystoreManager.encrypt(password)
                    val cred = Credential(
                        key = key.trim(),
                        label = label.trim(),
                        encryptedData = encrypted.encryptedBase64,
                        iv = encrypted.ivBase64
                    )
                    db.credentialDao().insertOrUpdate(cred)
                    Toast.makeText(context, "تم تشفير وحفظ كلمة المرور بنجاح في Keystore", Toast.LENGTH_SHORT).show()
                    isAddDialogVisible = false
                }
            }
        )
    }
}

@Composable
fun CredentialCard(credential: Credential, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Blue100),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = Blue600, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = credential.label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "المفتاح: ${credential.key}",
                        fontSize = 11.5.sp,
                        color = LightTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("كلمة المرور: •••••••• (مشفرة بـ AES-256)", fontSize = 11.sp, color = Emerald600)
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Red600, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun AddCredentialDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة كلمة مرور للخزينة المشفرة", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("الاسم التعريفي (مثال: Router Admin)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("المفتاح البرمجي (مثال: router_admin_pass)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور الحساسة") },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (key.isNotBlank() && password.isNotBlank()) {
                        onSave(key, label.ifBlank { key }, password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Emerald600)
            ) {
                Text("تشفير وحفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
