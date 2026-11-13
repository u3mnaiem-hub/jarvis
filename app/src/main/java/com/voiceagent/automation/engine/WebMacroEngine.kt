package com.voiceagent.automation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

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
                try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
                var docWin = (el.ownerDocument && el.ownerDocument.defaultView) ? el.ownerDocument.defaultView : window;
                var tag = (el.tagName || '').toUpperCase();
                if (tag === 'BUTTON' || tag === 'INPUT' || tag === 'A') {
                    try { el.click(); return true; } catch (e) {}
                }
                var events = ['mousedown', 'mouseup', 'click'];
                for (var i = 0; i < events.length; i++) {
                    el.dispatchEvent(new docWin.MouseEvent(events[i], {
                        view: docWin,
                        bubbles: true,
                        cancelable: true
                    }));
                }
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
                    var pwd = doc.querySelector('input[type="password"], #Frm_Password');
                    if (pwd) {
                        pwd.focus();
                        ['keydown', 'keypress', 'keyup'].forEach(function(type) {
                            pwd.dispatchEvent(new KeyboardEvent(type, {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
                            }));
                        });
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
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        pageLoadedDeferred = null
    }

    companion object {
        private const val TAG = "WebMacroEngine"

        private const val FINDER_HELPER_JS = """
            function findElementInFrames(doc, cssList, textList, preferInput) {
                if (!doc) return null;
                for (var i = 0; i < cssList.length; i++) {
                    var sel = cssList[i];
                    if (!sel) continue;
                    try {
                        var el = doc.querySelector(sel);
                        if (el) return el;
                    } catch (e) {}
                }
                if (textList && textList.length > 0) {
                    var selector = preferInput
                        ? 'input, textarea, select'
                        : 'button, input[type="button"], input[type="submit"], a, [role="button"], div, span, li, td, label';
                    var candidates = doc.querySelectorAll(selector);
                    for (var t = 0; t < textList.length; t++) {
                        var needle = (textList[t] || '').trim().toLowerCase();
                        if (!needle) continue;
                        for (var j = 0; j < candidates.length; j++) {
                            var c = candidates[j];
                            var label = (c.innerText || c.textContent || c.value || c.placeholder || c.getAttribute('aria-label') || '').trim().toLowerCase();
                            if (label === needle) return c;
                        }
                        for (var k = 0; k < candidates.length; k++) {
                            var c2 = candidates[k];
                            var label2 = (c2.innerText || c2.textContent || c2.value || c2.placeholder || c2.getAttribute('aria-label') || '').trim().toLowerCase();
                            if (label2 && label2.indexOf(needle) !== -1 && label2.length <= needle.length + 25) {
                                return c2;
                            }
                        }
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
