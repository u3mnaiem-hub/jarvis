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
import com.voiceagent.automation.data.db.VoiceAgentDatabase
import com.voiceagent.automation.engine.AutomationRecorderEngine
import com.voiceagent.automation.model.ActionType
import com.voiceagent.automation.model.AutomationStep
import com.voiceagent.automation.model.Credential
import com.voiceagent.automation.security.KeystoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

private class JarvisWebSnifferBridge(private val appContext: android.content.Context) {
    @JavascriptInterface
    fun onRouterLoginSubmitted(username: String, password: String) {
        val prefs = appContext.getSharedPreferences("voice_agent_prefs", android.content.Context.MODE_PRIVATE)
        val storedUser = prefs.getString("router_admin_user", null)
        val cleanUser = storedUser?.takeIf { it.isNotBlank() } ?: username.trim().ifBlank { "admin" }
        if (password.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = VoiceAgentDatabase.getInstance(appContext)
                    val existingCred = db.credentialDao().getCredentialByKey("router_admin_pass")
                    // Only save if no router password is stored in Credentials yet, so system-stored
                    // credentials always take priority and are never overwritten by browser typing!
                    if (existingCred == null) {
                        prefs.edit().putString("router_admin_user", cleanUser).apply()
                        val enc = KeystoreManager.encrypt(password)
                        db.credentialDao().insertOrUpdate(
                            Credential(
                                key = "router_admin_pass",
                                label = "ZTE Router Admin Password",
                                encryptedData = enc.encryptedBase64,
                                iv = enc.ivBase64
                            )
                        )
                        db.credentialDao().insertOrUpdate(
                            Credential(
                                key = "router_password",
                                label = "Router Password",
                                encryptedData = enc.encryptedBase64,
                                iv = enc.ivBase64
                            )
                        )
                        Log.i("JarvisWebSniffer", "Saved initial router credentials to Keystore vault for user=$cleanUser")
                    } else {
                        Log.i("JarvisWebSniffer", "Using existing system-stored Credentials (router_admin_pass) without overwriting")
                    }
                } catch (e: Exception) {
                    Log.w("JarvisWebSniffer", "Failed checking router credentials: ${e.message}")
                }
            }
        }
        if (!AutomationRecorderEngine.isRecordingActive) return
        Log.i("JarvisWebSniffer", "Captured router login referencing system credential router_admin_pass for user=$cleanUser")
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Type Admin Username ($cleanUser)",
                description = "Enter $cleanUser in Frm_Username",
                actionType = ActionType.SET_TEXT,
                isWebStep = true,
                cssSelector = "[id='Frm_Username']",
                selectorResourceId = "Frm_Username",
                textPayload = cleanUser,
                delayAfterMs = 400L
            )
        )
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Inject Router Password",
                description = "Inject encrypted router_admin_pass into Frm_Password",
                actionType = ActionType.INJECT_CREDENTIAL,
                isWebStep = true,
                credentialKey = "router_admin_pass",
                cssSelector = "[id='Frm_Password']",
                selectorResourceId = "Frm_Password",
                delayAfterMs = 400L
            )
        )
        AutomationRecorderEngine.addRecordedStep(
            AutomationStep(
                title = "Click Login (LoginId)",
                description = "Submit router login via native JS",
                actionType = ActionType.CLICK_ELEMENT,
                isWebStep = true,
                cssSelector = "[id='LoginId']",
                selectorResourceId = "LoginId",
                selectorText = "Login",
                waitAfterNavigationSelector = "[id='mmInternet']",
                delayAfterMs = 2000L
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
                description = "Click router element [id='$elementId']",
                actionType = ActionType.CLICK_ELEMENT,
                isWebStep = true,
                cssSelector = "[id='$elementId']",
                selectorResourceId = elementId,
                selectorText = label,
                delayAfterMs = 1500L
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
                isWebStep = true,
                rowContextText = targetDevice,
                selectorResourceId = "instName_ParentCtrl:{i}",
                selectorText = targetDevice,
                indexedNamePattern = "[id='instName_ParentCtrl:{index}']",
                indexedTargetPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
                targetRadioValue = radioValue,
                textPayload = "Enable:ParentCtrl:{i}|$radioValue",
                delayAfterMs = 1500L
            )
        )
    }

    @JavascriptInterface
    fun onGenericWebAction(actionKind: String, selector: String, label: String, value: String) {
        if (!AutomationRecorderEngine.isRecordingActive) return
        if (selector.isBlank()) return
        Log.i("JarvisWebSniffer", "Captured generic web action kind=$actionKind sel=$selector label=$label")
        when (actionKind) {
            "click" -> {
                AutomationRecorderEngine.addRecordedStep(
                    AutomationStep(
                        title = "Click: ${label.ifBlank { selector }}",
                        description = "Click web element $selector",
                        actionType = ActionType.CLICK_ELEMENT,
                        isWebStep = true,
                        cssSelector = selector,
                        selectorText = label.takeIf { it.isNotBlank() },
                        delayAfterMs = 1200L
                    )
                )
            }
            "input" -> {
                AutomationRecorderEngine.addRecordedStep(
                    AutomationStep(
                        title = "Fill: ${label.ifBlank { selector }}",
                        description = "Set value on $selector",
                        actionType = ActionType.SET_TEXT,
                        isWebStep = true,
                        cssSelector = selector,
                        textPayload = value,
                        delayAfterMs = 600L
                    )
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserScreen(
    initialUrl: String = "http://192.168.1.1",
    onNavigateBack: () -> Unit
) {
    val localContext = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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
                        coroutineScope.launch(Dispatchers.IO) {
                            val db = VoiceAgentDatabase.getInstance(localContext)
                            val cred = db.credentialDao().getCredentialByKey("router_admin_pass")
                                ?: db.credentialDao().getCredentialByKey("router_password")
                            val prefs = localContext.getSharedPreferences("voice_agent_prefs", android.content.Context.MODE_PRIVATE)
                            val savedUser = prefs.getString("router_admin_user", "admin") ?: "admin"
                            if (cred != null) {
                                val plainPass = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                                val uQuote = JSONObject.quote(savedUser)
                                val pQuote = JSONObject.quote(plainPass)
                                launch(Dispatchers.Main) {
                                    webViewInstance?.evaluateJavascript(
                                        """
                                        (function() {
                                            var u = document.getElementById('Frm_Username');
                                            var p = document.getElementById('Frm_Password');
                                            var b = document.getElementById('LoginId');
                                            if (u && p && b) {
                                                u.value = $uQuote;
                                                u.dispatchEvent(new Event('input', { bubbles: true }));
                                                u.dispatchEvent(new Event('change', { bubbles: true }));
                                                p.value = $pQuote;
                                                p.dispatchEvent(new Event('input', { bubbles: true }));
                                                p.dispatchEvent(new Event('change', { bubbles: true }));
                                                b.click();
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            } else {
                                launch(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        localContext,
                                        "احفظ باسورد الراوتر أولاً أو اكتبه مرة واحدة في صفحة الراوتر وسيُحفظ تلقائياً",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }) {
                        Icon(Icons.Default.Key, contentDescription = "Auto Login Router", tint = Color(0xFFF59E0B))
                    }
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

                        addJavascriptInterface(JarvisWebSnifferBridge(context.applicationContext), "JarvisSniffer")

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
                                // Automatically pre-fill Frm_Username and Frm_Password from system-stored Credentials
                                // when the router login page loads, so the user never needs to type them manually
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val db = VoiceAgentDatabase.getInstance(localContext)
                                        val cred = db.credentialDao().getCredentialByKey("router_admin_pass")
                                            ?: db.credentialDao().getCredentialByKey("router_password")
                                        val prefs = localContext.getSharedPreferences("voice_agent_prefs", android.content.Context.MODE_PRIVATE)
                                        val savedUser = prefs.getString("router_admin_user", "admin") ?: "admin"
                                        if (cred != null) {
                                            val plainPass = KeystoreManager.decrypt(cred.encryptedData, cred.iv)
                                            val uQuote = JSONObject.quote(savedUser)
                                            val pQuote = JSONObject.quote(plainPass)
                                            launch(Dispatchers.Main) {
                                                view?.evaluateJavascript(
                                                    """
                                                    (function() {
                                                        var u = document.getElementById('Frm_Username');
                                                        var p = document.getElementById('Frm_Password');
                                                        if (u && p) {
                                                            u.value = $uQuote;
                                                            u.dispatchEvent(new Event('input', { bubbles: true }));
                                                            u.dispatchEvent(new Event('change', { bubbles: true }));
                                                            p.value = $pQuote;
                                                            p.dispatchEvent(new Event('input', { bubbles: true }));
                                                            p.dispatchEvent(new Event('change', { bubbles: true }));
                                                        }
                                                    })();
                                                    """.trimIndent(),
                                                    null
                                                )
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
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
                                                    var pEl = document.getElementById('Frm_Password');
                                                    var uVal = uEl ? (uEl.value || 'admin') : 'admin';
                                                    var pVal = pEl ? (pEl.value || '') : '';
                                                    if (window.JarvisSniffer) window.JarvisSniffer.onRouterLoginSubmitted(uVal, pVal);
                                                    break;
                                                }
                                                if (id === 'mmInternet' || id === 'smParentCtrl' || id.indexOf('mm') === 0 || id.indexOf('sm') === 0 || id.indexOf('ssm') === 0) {
                                                    var txt = (el.innerText || el.textContent || id).trim();
                                                    if (window.JarvisSniffer) window.JarvisSniffer.onMenuClicked(id, txt);
                                                    break;
                                                }
                                                var tag = (el.tagName || '').toLowerCase();
                                                if (id && (tag === 'input' || tag === 'button' || tag === 'a') && el.type !== 'text' && el.type !== 'password' && el.type !== 'radio') {
                                                    var bTxt = (el.value || el.innerText || el.textContent || id).trim();
                                                    if (window.JarvisSniffer) window.JarvisSniffer.onMenuClicked(id, bTxt);
                                                    break;
                                                }
                                                el = el.parentElement;
                                            }
                                        }, true);

                                        document.addEventListener('change', function(e) {
                                            var t = e.target;
                                            if (!t) return;
                                            var name = t.name || '';
                                            var id = t.id || '';
                                            if (t.type === 'radio' && name.indexOf('Enable:ParentCtrl:') === 0) {
                                                var idxStr = name.split(':')[2] || '0';
                                                var idx = parseInt(idxStr, 10) || 0;
                                                var devEl = document.getElementById('instName_ParentCtrl:' + idx);
                                                var devName = devEl ? (devEl.value || devEl.innerText || devEl.textContent || '').trim() : 'TV';
                                                var instEl = document.getElementById('_InstID_ParentCtrl:' + idx) || document.getElementById('_InstID:' + idx);
                                                var instId = instEl ? (instEl.value || '') : '';
                                                if (window.JarvisSniffer) {
                                                    window.JarvisSniffer.onParentalControlRadioToggled(idx, devName, String(t.value), instId);
                                                }
                                                return;
                                            }
                                            if (id === 'Frm_Username' || id === 'Frm_Password') return;
                                            if (t.type === 'radio' || t.type === 'checkbox') {
                                                var sel = id ? ("[id='" + id + "']") : (name ? ("input[name='" + name + "'][value='" + t.value + "']") : '');
                                                if (sel && window.JarvisSniffer) {
                                                    window.JarvisSniffer.onGenericWebAction('click', sel, id || name, String(t.value || ''));
                                                }
                                            } else if (id) {
                                                var inSel = "[id='" + id + "']";
                                                if (window.JarvisSniffer) {
                                                    window.JarvisSniffer.onGenericWebAction('input', inSel, id, String(t.value || ''));
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
