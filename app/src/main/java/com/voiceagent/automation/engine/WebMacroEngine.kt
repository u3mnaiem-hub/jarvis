package com.voiceagent.automation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

data class IndexedRadioResult(
    val success: Boolean,
    val status: String, // "SUCCESS", "NOT_FOUND", "MULTIPLE_MATCHES", "RADIO_NOT_FOUND", "VERIFY_FAILED", "TIMEOUT"
    val matchedIndex: Int = -1,
    val matchedDeviceName: String = "",
    val message: String = "",
    val allDevicesDump: String = ""
)

/**
 * Dedicated WebView + JavaScript execution engine for WebMacro workflows (e.g. ZTE H188 WE router).
 * Searches both the main document and same-origin iframes, dispatches real DOM/Mouse events,
 * and never logs sensitive field values.
 */
class WebMacroEngine(private val context: Context) {

    private var webView: WebView? = null
    private var pageLoadedDeferred: CompletableDeferred<Boolean>? = null
    var debugMode: Boolean = true

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }

        WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            layout(0, 0, 1080, 1920)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoadedDeferred?.complete(true)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "Main frame error: ${error?.description}")
                        pageLoadedDeferred?.complete(false)
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    // Local router admin pages often use self-signed certificates
                    handler?.proceed()
                }
            }
            webView = this
            _activeWebViewFlow.value = this
        }
    }

    suspend fun loadUrl(url: String, timeoutMs: Long = 15000L): Boolean {
        val wv = ensureWebView()
        val deferred = CompletableDeferred<Boolean>()
        pageLoadedDeferred = deferred

        withContext(Dispatchers.Main) {
            wv.loadUrl(url)
        }

        val ok = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        // Allow JS frameworks / iframes a short settle window after onPageFinished
        delay(500)
        return ok
    }

    suspend fun checkSessionConflict(): String? {
        val js = """
            (function() {
                var keywords = [
                    'already logged in',
                    'another user',
                    'someone else is logged',
                    'session conflict',
                    'only one user',
                    'تم تسجيل الدخول',
                    'مستخدم آخر'
                ];
                function scan(doc) {
                    if (!doc || !doc.body) return null;
                    var txt = (doc.body.innerText || '').toLowerCase();
                    for (var i = 0; i < keywords.length; i++) {
                        if (txt.indexOf(keywords[i]) !== -1) return keywords[i];
                    }
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            var hit = scan(fDoc);
                            if (hit) return hit;
                        } catch (e) {}
                    }
                    return null;
                }
                return scan(document) || '';
            })();
        """.trimIndent()
        val raw = evalJs(js)?.trim('"') ?: ""
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun waitForElement(
        cssSelectors: List<String>,
        textCandidates: List<String>,
        timeoutMs: Long = 10000L
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val found = elementExists(cssSelectors, textCandidates)
            if (found) return true
            delay(300)
        }
        if (debugMode) {
            val dump = dumpInteractiveElements()
            Log.d(TAG, "waitForElement timed out. Interactive elements: $dump")
        }
        return false
    }

    suspend fun fillField(
        cssSelectors: List<String>,
        textCandidates: List<String>,
        value: String,
        timeoutMs: Long = 10000L,
        isSensitive: Boolean = false
    ): Boolean {
        val ready = waitForElement(cssSelectors, textCandidates, timeoutMs)
        if (!ready) return false

        val cssJson = JSONArray(cssSelectors).toString()
        val textJson = JSONArray(textCandidates).toString()
        val valJson = JSONObject.quote(value)

        val js = """
            (function() {
                ${FINDER_HELPER_JS}
                var el = findElementInFrames(document, $cssJson, $textJson, true);
                if (!el) return false;
                el.focus();
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (setter && setter.set) {
                    setter.set.call(el, $valJson);
                } else {
                    el.value = $valJson;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                return true;
            })();
        """.trimIndent()

        val result = evalJs(js)
        if (!isSensitive) {
            Log.d(TAG, "fillField($cssSelectors / $textCandidates) -> $result")
        } else {
            Log.d(TAG, "fillField(<sensitive>) -> $result")
        }
        return result == "true"
    }

    suspend fun clickElement(
        cssSelectors: List<String>,
        textCandidates: List<String>,
        timeoutMs: Long = 10000L,
        tryLoginFallback: Boolean = false
    ): Boolean {
        val ready = waitForElement(cssSelectors, textCandidates, timeoutMs)
        if (!ready) {
            if (tryLoginFallback) {
                return submitLoginFormFallback()
            }
            return false
        }

        val cssJson = JSONArray(cssSelectors).toString()
        val textJson = JSONArray(textCandidates).toString()

        val js = """
            (function() {
                ${FINDER_HELPER_JS}
                var el = findElementInFrames(document, $cssJson, $textJson, false);
                if (!el) return false;
                if (el.closest) {
                    var clickableAncestor = el.closest('a, button, [role="button"], [onclick]');
                    if (clickableAncestor) el = clickableAncestor;
                }
                try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
                var docWin = (el.ownerDocument && el.ownerDocument.defaultView) ? el.ownerDocument.defaultView : window;
                var events = ['mousedown', 'mouseup', 'click'];
                for (var i = 0; i < events.length; i++) {
                    try {
                        el.dispatchEvent(new docWin.MouseEvent(events[i], {
                            view: docWin,
                            bubbles: true,
                            cancelable: true
                        }));
                    } catch (e) {}
                }
                try { el.click(); } catch (e) {}
                return true;
            })();
        """.trimIndent()

        val clicked = evalJs(js) == "true"
        if (clicked) {
            delay(500)
            return true
        }
        return if (tryLoginFallback) submitLoginFormFallback() else false
    }

    /**
     * Clicks a target control (e.g. "On" / "Off" radio button) scoped specifically to the row
     * containing [rowText] (e.g. "Toshiba tv", "sharptv") across main document and iframes.
     */
    suspend fun clickInRow(
        rowText: String,
        targetTexts: List<String>,
        timeoutMs: Long = 10000L
    ): Boolean {
        val ready = waitForElement(emptyList(), listOf(rowText), timeoutMs)
        if (!ready) return false

        val rowJson = JSONObject.quote(rowText.trim())
        val targetsJson = JSONArray(targetTexts).toString()

        val js = """
            (function() {
                var rowNeedle = $rowJson.toLowerCase();
                var targetList = $targetsJson.map(function(t) { return (t || '').trim().toLowerCase(); }).filter(Boolean);

                function findRowAndClick(doc) {
                    if (!doc) return false;
                    var all = doc.querySelectorAll('tr, div, li, p, span, td');
                    var bestLabel = null;
                    for (var i = 0; i < all.length; i++) {
                        var el = all[i];
                        var txt = (el.innerText || el.textContent || '').trim().toLowerCase();
                        if (txt === rowNeedle || (txt.indexOf(rowNeedle) !== -1 && txt.length <= rowNeedle.length + 12)) {
                            if (!bestLabel || txt.length < (bestLabel.innerText || '').length) {
                                bestLabel = el;
                            }
                        }
                    }
                    if (bestLabel) {
                        var container = bestLabel;
                        for (var depth = 0; depth < 6 && container; depth++) {
                            var radios = container.querySelectorAll('input[type="radio"], input[type="checkbox"]');
                            if (radios.length > 0) {
                                // Match by label text or sibling text
                                for (var r = 0; r < radios.length; r++) {
                                    var radio = radios[r];
                                    var lbl = radio.id ? doc.querySelector('label[for="' + radio.id + '"]') : null;
                                    var parentText = (lbl ? lbl.innerText : (radio.parentElement ? radio.parentElement.innerText : '') || '').trim().toLowerCase();
                                    var nextText = (radio.nextSibling && radio.nextSibling.textContent ? radio.nextSibling.textContent : '').trim().toLowerCase();
                                    var combined = (parentText + ' ' + nextText).trim();
                                    for (var t = 0; t < targetList.length; t++) {
                                        if (combined === targetList[t] || combined.indexOf(targetList[t]) !== -1) {
                                            radio.click();
                                            radio.dispatchEvent(new Event('change', { bubbles: true }));
                                            return true;
                                        }
                                    }
                                }
                                // Positional fallback for ZTE On/Off pair: index 0 = On/Allow, index 1 = Off/Block
                                if (radios.length >= 2) {
                                    var wantsOff = targetList.some(function(x) { return x === 'off' || x === 'block' || x === 'disable' || x === 'تعطيل' || x === 'حظر'; });
                                    var targetRadio = wantsOff ? radios[1] : radios[0];
                                    targetRadio.click();
                                    targetRadio.dispatchEvent(new Event('change', { bubbles: true }));
                                    return true;
                                }
                            }
                            container = container.parentElement;
                        }
                    }
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            if (findRowAndClick(fDoc)) return true;
                        } catch (e) {}
                    }
                    return false;
                }
                return findRowAndClick(document);
            })();
        """.trimIndent()

        val ok = evalJs(js) == "true"
        if (ok) delay(400)
        return ok
    }

    suspend fun submitLoginFormFallback(): Boolean {
        val js = """
            (function() {
                function trySubmit(doc) {
                    if (!doc) return false;
                    var pwd = doc.getElementById('Frm_Password') || doc.querySelector("input[id='Frm_Password'], input[type='password']");
                    if (pwd) {
                        pwd.focus();
                        ['keydown', 'keypress', 'keyup'].forEach(function(type) {
                            pwd.dispatchEvent(new KeyboardEvent(type, {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
                            }));
                        });
                        var loginEl = doc.getElementById('LoginId') || doc.querySelector("[id='LoginId']");
                        if (loginEl) {
                            try { loginEl.click(); return true; } catch (e) {}
                        }
                        if (pwd.form) {
                            try { pwd.form.submit(); return true; } catch (e) {}
                        }
                    }
                    var form = doc.querySelector('form');
                    if (form) {
                        try { form.submit(); return true; } catch (e) {}
                    }
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            if (trySubmit(fDoc)) return true;
                        } catch (e) {}
                    }
                    return false;
                }
                return trySubmit(document);
            })();
        """.trimIndent()
        return evalJs(js) == "true"
    }

    /**
     * Data-driven indexed device lookup and immediate-save radio setter (e.g. ZTE H188 WE Parental Control).
     * 1. Waits until index 0 (e.g. [id='instName_ParentCtrl:0']) has non-empty text.
     * 2. Scans indexStart..indexEnd, matches deviceName (case-insensitive, trimmed, partial match allowed).
     * 3. Fails if 0 matches or >1 matches (never guesses a device).
     * 4. Clicks the radio matching targetValue ("1" = on, "0" = off) and dispatches click + change events.
     * 5. Waits 1.5s, re-reads radio state to verify; retries once if reverted.
     */
    suspend fun setIndexedDeviceRadio(
        namePattern: String,
        radioPattern: String,
        indexStart: Int,
        indexEnd: Int,
        deviceName: String,
        targetValue: String,
        timeoutMs: Long = 12000L
    ): IndexedRadioResult {
        val firstNameSelector = namePattern.replace("{index}", indexStart.toString())
        val firstWaitStart = System.currentTimeMillis()
        var firstNonEmptyReady = false

        while (System.currentTimeMillis() - firstWaitStart < timeoutMs) {
            val checkFirstJs = """
                (function() {
                    ${FINDER_HELPER_JS}
                    function hasNonEmptyText(doc) {
                        if (!doc) return false;
                        var list = queryAllSafe(doc, ${JSONObject.quote(firstNameSelector)});
                        for (var i = 0; i < list.length; i++) {
                            var txt = (list[i].innerText || list[i].textContent || list[i].value || '').trim();
                            if (txt.length > 0) return true;
                        }
                        var frames = doc.querySelectorAll('iframe, frame');
                        for (var f = 0; f < frames.length; f++) {
                            try {
                                var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                                if (hasNonEmptyText(fDoc)) return true;
                            } catch (e) {}
                        }
                        return false;
                    }
                    return hasNonEmptyText(document);
                })();
            """.trimIndent()
            if (evalJs(checkFirstJs) == "true") {
                firstNonEmptyReady = true
                break
            }
            delay(300)
        }

        if (!firstNonEmptyReady) {
            return IndexedRadioResult(
                success = false,
                status = "TIMEOUT",
                message = "Parental Control device list ($firstNameSelector) did not load non-empty text within ${timeoutMs / 1000}s"
            )
        }

        val namePatJson = JSONObject.quote(namePattern)
        val radioPatJson = JSONObject.quote(radioPattern)
        val deviceQueryJson = JSONObject.quote(deviceName.trim())
        val targetValJson = JSONObject.quote(targetValue.trim())

        suspend fun scanAndClickJs(): JSONObject {
            val js = """
                (function() {
                    ${FINDER_HELPER_JS}
                    var namePat = $namePatJson;
                    var radioPat = $radioPatJson;
                    var needle = $deviceQueryJson.toLowerCase();
                    var targetVal = $targetValJson;
                    var foundDevices = [];
                    var exactMatches = [];
                    var partialMatches = [];

                    function findInDocs(sel) {
                        var results = [];
                        function walk(doc) {
                            if (!doc) return;
                            var nodes = queryAllSafe(doc, sel);
                            for (var i = 0; i < nodes.length; i++) results.push(nodes[i]);
                            var frames = doc.querySelectorAll('iframe, frame');
                            for (var f = 0; f < frames.length; f++) {
                                try {
                                    var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                                    walk(fDoc);
                                } catch (e) {}
                            }
                        }
                        walk(document);
                        return results;
                    }

                    for (var idx = $indexStart; idx <= $indexEnd; idx++) {
                        var sel = namePat.replace('{index}', '' + idx);
                        var els = findInDocs(sel);
                        for (var e = 0; e < els.length; e++) {
                            var rawName = (els[e].innerText || els[e].textContent || els[e].value || '').trim();
                            if (!rawName) continue;
                            foundDevices.push(idx + ':' + rawName);
                            var lowerName = rawName.toLowerCase();
                            if (lowerName === needle) {
                                exactMatches.push({ index: idx, name: rawName, el: els[e] });
                            } else if (lowerName.indexOf(needle) !== -1 || needle.indexOf(lowerName) !== -1) {
                                partialMatches.push({ index: idx, name: rawName, el: els[e] });
                            }
                            break;
                        }
                    }

                    var matches = exactMatches.length > 0 ? exactMatches : partialMatches;
                    if (matches.length === 0) {
                        return JSON.stringify({
                            status: 'NOT_FOUND',
                            devices: foundDevices.join(', ')
                        });
                    }
                    if (matches.length > 1) {
                        var names = matches.map(function(m) { return m.name + ' (index ' + m.index + ')'; }).join(', ');
                        return JSON.stringify({
                            status: 'MULTIPLE_MATCHES',
                            matchedNames: names,
                            devices: foundDevices.join(', ')
                        });
                    }

                    var chosen = matches[0];
                    var rSel = radioPat.replace('{index}', '' + chosen.index);
                    var radios = findInDocs(rSel);

                    // Also search row container if multiple radios share name or row
                    if (radios.length < 2 && chosen.el) {
                        var row = chosen.el.closest ? (chosen.el.closest('tr, .row, div[id*="ParentCtrl"], form') || chosen.el.parentElement) : chosen.el.parentElement;
                        if (row) {
                            var rowRadios = row.querySelectorAll("input[type='radio']");
                            for (var rr = 0; rr < rowRadios.length; rr++) {
                                if (radios.indexOf(rowRadios[rr]) === -1) radios.push(rowRadios[rr]);
                            }
                        }
                    }

                    var targetRadio = null;
                    for (var r = 0; r < radios.length; r++) {
                        if (String(radios[r].value) === targetVal) {
                            targetRadio = radios[r];
                            break;
                        }
                    }
                    if (!targetRadio && radios.length >= 2) {
                        targetRadio = (targetVal === '1') ? radios[0] : radios[1];
                    } else if (!targetRadio && radios.length === 1) {
                        targetRadio = radios[0];
                    }

                    if (!targetRadio) {
                        return JSON.stringify({
                            status: 'RADIO_NOT_FOUND',
                            matchedIndex: chosen.index,
                            matchedName: chosen.name,
                            devices: foundDevices.join(', ')
                        });
                    }

                    var docWin = (targetRadio.ownerDocument && targetRadio.ownerDocument.defaultView) ? targetRadio.ownerDocument.defaultView : window;
                    try { targetRadio.scrollIntoView({ block: 'center' }); } catch (e) {}
                    targetRadio.checked = true;
                    ['mousedown', 'mouseup', 'click'].forEach(function(ev) {
                        try {
                            targetRadio.dispatchEvent(new docWin.MouseEvent(ev, { view: docWin, bubbles: true, cancelable: true }));
                        } catch (e) {}
                    });
                    try { targetRadio.click(); } catch (e) {}
                    try { targetRadio.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}

                    return JSON.stringify({
                        status: 'CLICKED',
                        matchedIndex: chosen.index,
                        matchedName: chosen.name,
                        devices: foundDevices.join(', ')
                    });
                })();
            """.trimIndent()
            val raw = evalJs(js) ?: "{}"
            val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue().toString()
            } else raw
            return try {
                JSONObject(unquoted)
            } catch (_: Exception) {
                JSONObject()
            }
        }

        suspend fun verifyRadioState(matchedIndex: Int): Boolean {
            val rSel = radioPattern.replace("{index}", matchedIndex.toString())
            val js = """
                (function() {
                    ${FINDER_HELPER_JS}
                    var rSel = ${JSONObject.quote(rSel)};
                    var targetVal = $targetValJson;
                    function checkDoc(doc) {
                        if (!doc) return null;
                        var errEl = doc.querySelector('.error, #errMsg, [id*="Error"][style*="block"]');
                        if (errEl && isElementVisible(errEl) && (errEl.innerText || '').trim().length > 0) {
                            return false;
                        }
                        var radios = queryAllSafe(doc, rSel);
                        for (var i = 0; i < radios.length; i++) {
                            if (String(radios[i].value) === targetVal) {
                                return !!radios[i].checked;
                            }
                        }
                        if (radios.length === 1) return !!radios[0].checked;
                        var frames = doc.querySelectorAll('iframe, frame');
                        for (var f = 0; f < frames.length; f++) {
                            try {
                                var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                                var res = checkDoc(fDoc);
                                if (res !== null) return res;
                            } catch (e) {}
                        }
                        return null;
                    }
                    return checkDoc(document) === true;
                })();
            """.trimIndent()
            return evalJs(js) == "true"
        }

        var attemptResult = scanAndClickJs()
        val status = attemptResult.optString("status", "ERROR")
        val devicesDump = attemptResult.optString("devices", "")

        when (status) {
            "NOT_FOUND" -> return IndexedRadioResult(
                success = false,
                status = "NOT_FOUND",
                message = "Device '$deviceName' not found in Parental Control list. Available devices: $devicesDump",
                allDevicesDump = devicesDump
            )
            "MULTIPLE_MATCHES" -> {
                val matchedNames = attemptResult.optString("matchedNames", "")
                return IndexedRadioResult(
                    success = false,
                    status = "MULTIPLE_MATCHES",
                    message = "Multiple devices matched '$deviceName': $matchedNames. Stopping without guessing.",
                    allDevicesDump = devicesDump
                )
            }
            "RADIO_NOT_FOUND" -> {
                val idx = attemptResult.optInt("matchedIndex", -1)
                val name = attemptResult.optString("matchedName", deviceName)
                return IndexedRadioResult(
                    success = false,
                    status = "RADIO_NOT_FOUND",
                    matchedIndex = idx,
                    matchedDeviceName = name,
                    message = "Matched '$name' at index $idx, but radio button for value '$targetValue' was not found.",
                    allDevicesDump = devicesDump
                )
            }
            "CLICKED" -> {
                val idx = attemptResult.optInt("matchedIndex", -1)
                val name = attemptResult.optString("matchedName", deviceName)
                delay(1500)
                if (verifyRadioState(idx)) {
                    return IndexedRadioResult(
                        success = true,
                        status = "SUCCESS",
                        matchedIndex = idx,
                        matchedDeviceName = name,
                        message = "Device '$name' (index $idx) set to value '$targetValue' and verified.",
                        allDevicesDump = devicesDump
                    )
                }
                // Retry once as required by spec
                Log.w(TAG, "Radio verification failed on first attempt for '$name' (index $idx); retrying once...")
                attemptResult = scanAndClickJs()
                delay(1500)
                if (verifyRadioState(idx)) {
                    return IndexedRadioResult(
                        success = true,
                        status = "SUCCESS",
                        matchedIndex = idx,
                        matchedDeviceName = name,
                        message = "Device '$name' (index $idx) set to value '$targetValue' and verified after 1 retry.",
                        allDevicesDump = devicesDump
                    )
                }
                return IndexedRadioResult(
                    success = false,
                    status = "VERIFY_FAILED",
                    matchedIndex = idx,
                    matchedDeviceName = name,
                    message = "Radio state for '$name' (index $idx) reverted or failed verification after retry.",
                    allDevicesDump = devicesDump
                )
            }
            else -> return IndexedRadioResult(
                success = false,
                status = status,
                message = "Unexpected error setting radio for '$deviceName'",
                allDevicesDump = devicesDump
            )
        }
    }

    /**
     * Snapshot of the ZTE login form state. IMPORTANT: the ZTE login page renders the SAME top menu
     * (Home/Topology/Internet/...), Logout, sess_token etc. as the logged-in pages, so those elements
     * prove nothing about authentication. The only reliable signals are: login form still present,
     * server-side error text, and the lock-out counter / disabled login button.
     */
    data class LoginState(
        val formPresent: Boolean = true,
        val loginDisabled: Boolean = false,
        val errorText: String = "",
        val lockText: String = "",
        val href: String = "",
        val ready: Boolean = false,
        val valid: Boolean = false
    )

    suspend fun getLoginState(): LoginState {
        val js = """
            (function() {
                function shown(el) {
                    // Layout-independent (the WebView may be detached): walk ancestors' computed display only.
                    try {
                        var n = el;
                        while (n && n.nodeType === 1) {
                            var st = window.getComputedStyle(n);
                            if (st.display === 'none' || st.visibility === 'hidden') return false;
                            n = n.parentElement;
                        }
                        return !!el;
                    } catch (e) { return !!el; }
                }
                function txt(id) {
                    var e = document.getElementById(id);
                    if (!e || !shown(e)) return '';
                    return (e.innerText || e.textContent || '').trim();
                }
                var u = document.getElementById('Frm_Username');
                var b = document.getElementById('LoginId');
                return JSON.stringify({
                    formPresent: !!u,
                    loginDisabled: !!(b && (b.disabled || b.getAttribute('disabled'))) || !!(u && (u.disabled || u.getAttribute('disabled'))),
                    error: txt('login_error_span') || txt('login_error'),
                    lock: txt('login_error_waittime'),
                    href: String(location.href || ''),
                    ready: document.readyState === 'complete'
                });
            })();
        """.trimIndent()
        val raw = evalJs(js) ?: return LoginState()
        return try {
            val unquoted = if (raw.startsWith("\"")) org.json.JSONTokener(raw).nextValue().toString() else raw
            val o = JSONObject(unquoted)
            LoginState(
                formPresent = o.optBoolean("formPresent", true),
                loginDisabled = o.optBoolean("loginDisabled", false),
                errorText = o.optString("error", ""),
                lockText = o.optString("lock", ""),
                href = o.optString("href", ""),
                ready = o.optBoolean("ready", false),
                valid = true
            )
        } catch (_: Exception) {
            LoginState()
        }
    }

    /**
     * Called right after the Login button was clicked. Returns null on verified success,
     * or a human-readable failure reason. Login is async (token GET -> form submit -> full page reload).
     */
    suspend fun waitForLoginResult(timeoutMs: Long = 20000L): String? {
        val start = System.currentTimeMillis()
        delay(1200) // let the token request + form submit start navigating
        var last = LoginState()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val st = getLoginState()
            if (st.valid) {
                last = st
                if (st.ready && st.formPresent) {
                    if (st.lockText.isNotBlank()) {
                        return "Router login is locked: ${st.lockText}. Wait for the countdown to finish before retrying."
                    }
                    if (st.errorText.isNotBlank()) {
                        return "Router rejected the login: \"${st.errorText}\". Check the router password saved in Credentials."
                    }
                }
                if (st.ready && !st.formPresent) {
                    delay(800) // let the logged-in page build its menus
                    return null
                }
            }
            delay(400)
        }
        return "Login did not complete within ${timeoutMs / 1000}s: the router is still showing the login page" +
            (if (last.errorText.isNotBlank()) " (\"${last.errorText}\")" else "") +
            ". Most likely the password is wrong or the router is temporarily locked."
    }

    /** Releases the single ZTE admin session. Safe to call at any time; never throws. */
    suspend fun logoutQuietly() {
        try {
            if (webView == null) return
            val js = """
                (function() {
                    if (document.getElementById('Frm_Username')) return 'login-page';
                    try { if (typeof LogOff === 'function') { LogOff(); return 'logoff'; } } catch (e) {}
                    var l = document.getElementById('logOff') || document.getElementById('LogOffLnk');
                    if (l) { try { l.click(); return 'click'; } catch (e) {} }
                    return 'none';
                })();
            """.trimIndent()
            val res = evalJs(js)
            Log.d(TAG, "logoutQuietly -> $res")
            if (res != null && !res.contains("login-page") && !res.contains("none")) delay(1500)
        } catch (e: Exception) {
            Log.w(TAG, "logoutQuietly failed: ${e.message}")
        }
    }

    /** Recursively lists key names of the router's JS `meta_menu` (menu/sub-menu IDs) for diagnostics. */
    suspend fun dumpMenuMeta(): String {
        val js = """
            (function() {
                try {
                    if (typeof meta_menu === 'undefined') return 'meta_menu undefined';
                    var out = [];
                    function walk(o, p, d) {
                        if (!o || typeof o !== 'object' || d > 4 || out.length > 120) return;
                        for (var k in o) {
                            if (k === 'length') continue;
                            out.push(p + k);
                            walk(o[k], p + k + '/', d + 1);
                        }
                    }
                    walk(meta_menu, '', 0);
                    return out.join(', ');
                } catch (e) { return 'meta_menu error: ' + e; }
            })();
        """.trimIndent()
        return evalJs(js)?.trim('"') ?: ""
    }

    suspend fun saveDomSnapshot(stepTitle: String): String? {
        return try {
            val dump = dumpInteractiveElements()
            val ls = getLoginState()
            val meta = dumpMenuMeta()
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "debug_snapshots")
            if (!dir.exists()) dir.mkdirs()
            val safeName = stepTitle.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val file = File(dir, "snapshot_${System.currentTimeMillis()}_$safeName.txt")
            file.writeText(
                "Step: $stepTitle\nTimestamp: ${System.currentTimeMillis()}\n" +
                    "URL: ${ls.href}\nLoginFormPresent: ${ls.formPresent} (true = NOT logged in)\n" +
                    "LoginDisabled: ${ls.loginDisabled}\nLoginError: ${ls.errorText}\nLockText: ${ls.lockText}\n" +
                    "meta_menu keys: $meta\nInteractive Elements:\n$dump\n"
            )
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save DOM snapshot: ${e.message}")
            null
        }
    }

    suspend fun dumpInteractiveElements(): String {
        val js = """
            (function() {
                var out = [];
                function collect(doc, frameLabel) {
                    if (!doc) return;
                    var nodes = doc.querySelectorAll('input, button, a, select, div[onclick], span[onclick], [role="button"]');
                    for (var i = 0; i < nodes.length && out.length < 75; i++) {
                        var n = nodes[i];
                        var txt = (n.innerText || n.value || n.placeholder || '').trim().slice(0, 30);
                        out.push(n.tagName.toLowerCase() +
                            (n.id ? '#' + n.id : '') +
                            (n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\s+/).join('.') : '') +
                            (txt ? ' "' + txt + '"' : '') +
                            (frameLabel ? ' [' + frameLabel + ']' : ''));
                    }
                    var frames = doc.querySelectorAll('iframe, frame');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            collect(fDoc, frames[f].id || frames[f].name || ('frame' + f));
                        } catch (e) {}
                    }
                }
                collect(document, '');
                return out.join(' | ');
            })();
        """.trimIndent()
        return evalJs(js)?.trim('"') ?: ""
    }

    /**
     * Out-of-the-Box Native JS Function Bridge for ZTE H188:
     * Instead of simulating DOM mouse clicks across menus, attaches the WebView visibly (`activeWebViewFlow`),
     * logs in via the router's own `$("#LoginId").click()`, jumps directly to Parental Control via the router's
     * global `AjaxPageGet(...)` function, invokes `dataPost("InstSwitch", ...)` or toggles the radio directly,
     * and calls `LogOff()`.
     */
    suspend fun executeZteNativeJsBridge(
        baseUrl: String = "https://192.168.1.1/",
        username: String = "admin",
        plainPassword: String,
        deviceName: String,
        enableRule: Boolean
    ): IndexedRadioResult {
        val targetValue = if (enableRule) "1" else "0"
        val loaded = loadUrl(baseUrl, 15000L)
        if (!loaded) {
            return IndexedRadioResult(
                success = false,
                status = "TIMEOUT",
                message = "Native JS Bridge: Timed out loading $baseUrl"
            )
        }

        // Wait up to 800ms for MainActivity to attach the visible WebView overlay
        delay(600)

        val preState = getLoginState()
        if (preState.formPresent) {
            if (preState.loginDisabled || preState.lockText.isNotBlank()) {
                return IndexedRadioResult(
                    success = false,
                    status = "LOCKED",
                    message = "Router login is currently locked: ${preState.lockText}"
                )
            }
            val userQuote = JSONObject.quote(username)
            val passQuote = JSONObject.quote(plainPassword)
            val loginJs = """
                (function() {
                    var u = document.getElementById('Frm_Username');
                    var p = document.getElementById('Frm_Password');
                    var b = document.getElementById('LoginId');
                    if (!u || !p || !b) return 'missing';
                    u.value = $userQuote;
                    p.value = $passQuote;
                    b.click();
                    return 'clicked';
                })();
            """.trimIndent()
            evalJs(loginJs)

            val loginErr = waitForLoginResult(18000L)
            if (loginErr != null) {
                return IndexedRadioResult(
                    success = false,
                    status = "LOGIN_FAILED",
                    message = loginErr
                )
            }
        }

        // Jump directly to Parental Control using the router's own JS menu table or click #mmInternet -> #smParentCtrl
        val jumpJs = """
            (function() {
                try {
                    if (typeof menu_items !== 'undefined' && menu_items['mmInternet'] && menu_items['mmInternet']['smParentCtrl']) {
                        var page = menu_items['mmInternet']['smParentCtrl']['page'];
                        if (page && typeof AjaxPageGet === 'function') {
                            AjaxPageGet('getpage.lua?pid=123&nextpage=' + page + '&Menu3Location=0', '2');
                            return 'direct:' + page;
                        }
                    }
                    var mm = document.getElementById('mmInternet');
                    if (mm) { mm.click(); return 'clicked_mmInternet'; }
                } catch (e) { return 'err:' + e; }
                return 'none';
            })();
        """.trimIndent()
        val jumpRes = evalJs(jumpJs) ?: ""
        if (jumpRes.contains("clicked_mmInternet")) {
            delay(800)
            clickElement(listOf("[id='smParentCtrl']"), listOf("Parental Control", "التحكم الأبوي"), 8000L)
        } else {
            delay(1200)
        }

        // Now run the indexed device scanner & toggle (which triggers the router's own jQuery `.change()` -> `dataPost('InstSwitch', ...)`)
        val res = setIndexedDeviceRadio(
            namePattern = "[id='instName_ParentCtrl:{index}']",
            radioPattern = "input[type='radio'][id='Enable:ParentCtrl:{index}']",
            indexStart = 0,
            indexEnd = 10,
            deviceName = deviceName,
            targetValue = targetValue,
            timeoutMs = 12000L
        )

        logoutQuietly()
        return res
    }

    private suspend fun elementExists(
        cssSelectors: List<String>,
        textCandidates: List<String>
    ): Boolean {
        val cssJson = JSONArray(cssSelectors).toString()
        val textJson = JSONArray(textCandidates).toString()
        val js = """
            (function() {
                ${FINDER_HELPER_JS}
                return !!findElementInFrames(document, $cssJson, $textJson, false);
            })();
        """.trimIndent()
        return evalJs(js) == "true"
    }

    private suspend fun evalJs(script: String): String? {
        val wv = ensureWebView()
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(4000L) {
                suspendCancellableCoroutine { cont ->
                    wv.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                }
            }
        }
    }

    suspend fun destroy() = withContext(Dispatchers.Main) {
        _activeWebViewFlow.value = null
        (webView?.parent as? ViewGroup)?.removeView(webView)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        pageLoadedDeferred = null
    }

    companion object {
        private const val TAG = "WebMacroEngine"

        private val _activeWebViewFlow = MutableStateFlow<WebView?>(null)
        val activeWebViewFlow: StateFlow<WebView?> = _activeWebViewFlow.asStateFlow()

        private const val FINDER_HELPER_JS = """
            function isElementVisible(el) {
                if (!el) return false;
                try {
                    var doc = el.ownerDocument || document;
                    var win = doc.defaultView || window;
                    var style = win.getComputedStyle(el);
                    if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                        return false;
                    }
                    var rects = el.getClientRects();
                    return !!(rects && rects.length > 0);
                } catch (e) {
                    return true;
                }
            }

            function getElementLabel(el) {
                return (
                    el.innerText ||
                    el.textContent ||
                    el.value ||
                    el.placeholder ||
                    el.getAttribute('aria-label') ||
                    el.getAttribute('title') ||
                    el.getAttribute('alt') ||
                    ''
                ).trim().toLowerCase();
            }

            function matchByText(doc, selector, textList) {
                var candidates = doc.querySelectorAll(selector);
                for (var t = 0; t < textList.length; t++) {
                    var needle = (textList[t] || '').trim().toLowerCase();
                    if (!needle) continue;
                    for (var j = 0; j < candidates.length; j++) {
                        var c = candidates[j];
                        if (!isElementVisible(c)) continue;
                        if (getElementLabel(c) === needle) return c;
                    }
                    for (var k = 0; k < candidates.length; k++) {
                        var c2 = candidates[k];
                        if (!isElementVisible(c2)) continue;
                        var label2 = getElementLabel(c2);
                        if (label2 && label2.indexOf(needle) !== -1 && label2.length <= needle.length + 25) {
                            return c2;
                        }
                    }
                }
                return null;
            }

            function queryAllSafe(doc, sel) {
                if (!doc || !sel) return [];
                var out = [];
                try {
                    // Never use raw '#id' when IDs may contain ':' (e.g. instName_ParentCtrl:0)
                    if (sel.charAt(0) === '#' && sel.indexOf(' ') === -1 && sel.indexOf('>') === -1) {
                        var rawId = sel.slice(1);
                        var byId = doc.getElementById(rawId);
                        if (byId) out.push(byId);
                        var byAttr = doc.querySelectorAll("[id='" + rawId.replace(/'/g, "\\'") + "']");
                        for (var a = 0; a < byAttr.length; a++) {
                            if (out.indexOf(byAttr[a]) === -1) out.push(byAttr[a]);
                        }
                        return out;
                    }
                    var idMatch = sel.match(/^\[id=['"]([^'"]+)['"]\]$/);
                    if (idMatch) {
                        var exactEl = doc.getElementById(idMatch[1]);
                        if (exactEl) out.push(exactEl);
                    }
                    var list = doc.querySelectorAll(sel);
                    for (var i = 0; i < list.length; i++) {
                        if (out.indexOf(list[i]) === -1) out.push(list[i]);
                    }
                } catch (e) {}
                return out;
            }

            function findElementInFrames(doc, cssList, textList, preferInput) {
                if (!doc) return null;
                for (var i = 0; i < cssList.length; i++) {
                    var sel = cssList[i];
                    if (!sel) continue;
                    var allCss = queryAllSafe(doc, sel);
                    for (var v = 0; v < allCss.length; v++) {
                        if (isElementVisible(allCss[v])) return allCss[v];
                    }
                }
                if (textList && textList.length > 0) {
                    if (preferInput) {
                        var inp = matchByText(doc, 'input, textarea, select', textList);
                        if (inp) return inp;
                    } else {
                        // Pass 1: prioritize true interactive elements
                        var interactive = matchByText(doc, 'button, input[type="button"], input[type="submit"], a, [role="button"], [onclick]', textList);
                        if (interactive) return interactive;
                        // Pass 2: fallback to visible text containers
                        var generic = matchByText(doc, 'div, span, li, td, label, p, h1, h2, h3, h4', textList);
                        if (generic) return generic;
                    }
                }
                var frames = doc.querySelectorAll('iframe, frame');
                for (var f = 0; f < frames.length; f++) {
                    try {
                        var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                        var found = findElementInFrames(fDoc, cssList, textList, preferInput);
                        if (found) return found;
                    } catch (e) {}
                }
                return null;
            }
        """
    }
}
