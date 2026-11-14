package com.voiceagent.automation.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.util.Log
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

private class JarvisWebSnifferBridge {
    @JavascriptInterface
    fun onRouterLoginSubmitted(username: String) {
        if (!AutomationRecorderEngine.isRecordingActive) return
        Log.i("JarvisWebSniffer", "Captured router login for user=$username")
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Type Admin Username ($username)",
                description = "Enter $username in Frm_Username",
                actionType = ActionType.SET_TEXT,
                selectorResourceId = "Frm_Username",
                textPayload = username.ifBlank { "admin" },
                delayAfterMs = 500L
            )
        )
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Type Router Password",
                description = "Enter stored router_password in Frm_Password",
                actionType = ActionType.SET_TEXT,
                selectorResourceId = "Frm_Password",
                textPayload = "{{CREDENTIAL:router_password}}",
                delayAfterMs = 500L
            )
        )
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Click Login (LoginId)",
                description = "Submit router login",
                actionType = ActionType.CLICK_BY_ID,
                selectorResourceId = "LoginId",
                delayAfterMs = 3000L
            )
        )
    }

    @JavascriptInterface
    fun onMenuClicked(elementId: String, label: String) {
        if (!AutomationRecorderEngine.isRecordingActive) return
        Log.i("JarvisWebSniffer", "Captured menu click id=$elementId ($label)")
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Click Menu: ${label.ifBlank { elementId }}",
                description = "Click router menu item #$elementId",
                actionType = ActionType.CLICK_BY_ID,
                selectorResourceId = elementId,
                selectorText = label,
                delayAfterMs = 2000L
            )
        )
    }

    @JavascriptInterface
    fun onParentalControlRadioToggled(rowIndex: Int, deviceName: String, radioValue: String, instId: String) {
        if (!AutomationRecorderEngine.isRecordingActive) return
        val stateLabel = if (radioValue == "1") "Enable Restriction (قفل النت)" else "Disable Restriction (تشغيل النت)"
        val targetDevice = deviceName.ifBlank { "TV" }
        Log.i("JarvisWebSniffer", "Captured ParentalCtrl toggle row=$rowIndex device=$targetDevice val=$radioValue instId=$instId")
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "$stateLabel: $targetDevice",
                description = "Find instName_ParentCtrl:N == $targetDevice -> click Enable:ParentCtrl:N ($radioValue)",
                actionType = ActionType.SET_INDEXED_RADIO,
                selectorResourceId = "instName_ParentCtrl:{i}",
                selectorText = targetDevice,
                textPayload = "Enable:ParentCtrl:{i}|$radioValue",
                delayAfterMs = 1500L
            )
        )
    }
}

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

    // Whenever a new recording session starts or this screen opens fresh, clear WebView cookies/cache/history
    // so it never stays stuck on the last visited page or stale session.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webViewInstance?.apply {
                stopLoading()
                clearCache(true)
                clearHistory()
                clearFormData()
                urlText = initialUrl
                currentUrl = initialUrl
                loadUrl(initialUrl)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply {
                stopLoading()
                clearHistory()
                destroy()
            }
            webViewInstance = null
        }
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

                        addJavascriptInterface(JarvisWebSnifferBridge(), "JarvisSniffer")

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { urlText = it }
                                canGoBack = view?.canGoBack() ?: false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                canGoBack = view?.canGoBack() ?: false
                                url?.let { recordUrlIfActive(it) }
                                // Inject ZTE Router & DOM Sniffer hook so recording in the built-in browser
                                // captures deterministic HTTP/JS actions without relying on screen coordinates
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        if (window.__jarvisSnifferInstalled) return;
                                        window.__jarvisSnifferInstalled = true;

                                        document.addEventListener('click', function(e) {
                                            var el = e.target;
                                            while (el && el !== document.body) {
                                                var id = el.id || '';
                                                if (id === 'LoginId') {
                                                    var uEl = document.getElementById('Frm_Username');
                                                    var uVal = uEl ? (uEl.value || 'admin') : 'admin';
                                                    if (window.JarvisSniffer) window.JarvisSniffer.onRouterLoginSubmitted(uVal);
                                                    break;
                                                }
                                                if (id === 'mmInternet' || id === 'smParentCtrl' || id.indexOf('mm') === 0 || id.indexOf('sm') === 0) {
                                                    var txt = (el.innerText || el.textContent || id).trim();
                                                    if (window.JarvisSniffer) window.JarvisSniffer.onMenuClicked(id, txt);
                                                    break;
                                                }
                                                el = el.parentElement;
                                            }
                                        }, true);

                                        document.addEventListener('change', function(e) {
                                            var t = e.target;
                                            if (!t || t.type !== 'radio') return;
                                            var name = t.name || '';
                                            if (name.indexOf('Enable:ParentCtrl:') === 0) {
                                                var idxStr = name.split(':')[2] || '0';
                                                var idx = parseInt(idxStr, 10) || 0;
                                                var devEl = document.getElementById('instName_ParentCtrl:' + idx);
                                                var devName = devEl ? (devEl.value || devEl.innerText || devEl.textContent || '').trim() : 'TV';
                                                var instEl = document.getElementById('_InstID_ParentCtrl:' + idx) || document.getElementById('_InstID:' + idx);
                                                var instId = instEl ? (instEl.value || '') : '';
                                                if (window.JarvisSniffer) {
                                                    window.JarvisSniffer.onParentalControlRadioToggled(idx, devName, String(t.value), instId);
                                                }
                                            }
                                        }, true);
                                    })();
                                    """.trimIndent(),
                                    null
                                )
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
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        clearCache(true)
                        clearHistory()
                        clearFormData()
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
