package com.voiceagent.automation.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.AutomationStep

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserScreen(
    initialUrl: String = "http://192.168.1.1",
    onNavigateBack: () -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val isRecording by AutomationRecorderEngine.isRecordingFlow.collectAsState()
    val recordedSteps by AutomationRecorderEngine.recordedStepsFlow.collectAsState()

    fun recordUrlIfActive(targetUrl: String) {
        if (!AutomationRecorderEngine.isRecordingActive) return
        val lastUrl = AutomationRecorderEngine.recordedStepsFlow.value.lastOrNull {
            it.actionType == ActionType.OPEN_URL
        }?.url
        if (lastUrl == targetUrl) return
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Open URL: $targetUrl",
                description = "Navigate to $targetUrl",
                actionType = ActionType.OPEN_URL,
                url = targetUrl,
                delayAfterMs = 2500L
            )
        )
    }

    // Intercept device back button: go back in web history if possible, else pop screen
    BackHandler(enabled = canGoBack) {
        webViewInstance?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        trailingIcon = {
                            IconButton(onClick = {
                                var target = urlText.trim()
                                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                    target = "http://$target"
                                }
                                currentUrl = target
                                recordUrlIfActive(target)
                                webViewInstance?.loadUrl(target)
                            }) {
                                Icon(Icons.Default.ArrowForward, contentDescription = "Go")
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val routerUrl = "http://192.168.1.1"
                        urlText = routerUrl
                        currentUrl = routerUrl
                        webViewInstance?.loadUrl(routerUrl)
                    }) {
                        Icon(Icons.Default.Router, contentDescription = "Router", tint = Color(0xFF10B981))
                    }
                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF065F46))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "جاري التسجيل (${recordedSteps.size} خطوات)",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = onNavigateBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("عودة لحفظ المهمة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF10B981)
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.databaseEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { urlText = it }
                                canGoBack = view?.canGoBack() ?: false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                canGoBack = view?.canGoBack() ?: false
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                isLoading = false
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                // Router config pages (e.g. 192.168.1.1) commonly use self-signed SSL certs
                                handler?.proceed()
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(currentUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                }
            )
        }
    }
}
