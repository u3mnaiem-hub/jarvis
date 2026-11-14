package com.voiceagent.automation.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Direct HTTP client for the ZTE H188 (WE Egypt) router macro.
 *
 * Uses OkHttp with an in-memory [CookieJar] that persists cookies across all requests in one run,
 * trusts the router's self-signed certificate for the target host only, and never uses a WebView,
 * AccessibilityService, or coordinate taps.
 */
class ZteRouterHttpClient(
    val baseUrl: String = "https://192.168.1.1/",
    var debugMode: Boolean = true
) {
    val stepLogs = mutableListOf<String>()

    private val routerHost: String = baseUrl.toHttpUrlOrNull()?.host ?: "192.168.1.1"
    private val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** Session token (`_sessionTmpToken`) decoded as a 24-digit string, refreshed as pages are fetched. */
    var currentSessionToken: String? = null
        private set

    /** Last authenticated HTML body returned by login or page fetches. */
    var lastAuthenticatedHtml: String = ""
        private set

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val hostCookies = cookieStore.getOrPut(url.host) { mutableListOf() }
            synchronized(hostCookies) {
                for (newCookie in cookies) {
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = cookieStore[url.host] ?: return emptyList()
            synchronized(hostCookies) {
                val now = System.currentTimeMillis()
                hostCookies.removeAll { it.expiresAt < now }
                return hostCookies.toList()
            }
        }
    }

    private val client: OkHttpClient = buildScopedTrustClient()

    private fun buildScopedTrustClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, _ ->
                // Trust self-signed certificate strictly for the configured router host only
                hostname.equals(routerHost, ignoreCase = true)
            }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    sealed class RouterLoginResult {
        data class Success(val sessionToken: String, val html: String) : RouterLoginResult()
        data class Failure(
            val category: FailureType,
            val message: String,
            val httpStatusCode: Int = -1
        ) : RouterLoginResult()
    }

    enum class FailureType {
        TOKEN_NOT_FOUND,
        LOGIN_REJECTED,
        LOGIN_PAGE_RETURNED_AGAIN,
        NETWORK_OR_CERTIFICATE_ERROR,
        DEVICE_CONTROL_ERROR
    }

    data class ParentalControlDevice(
        val index: Int,
        val instId: String,
        val name: String,
        val currentEnable: String,
        val allParams: Map<String, String>
    )

    data class ParentalControlToggleResult(
        val success: Boolean,
        val message: String,
        val httpStatusCode: Int = -1,
        val failureType: FailureType? = null
    )

    private fun logStep(stepName: String, statusCode: Int, detail: String = "") {
        val entry = "[$stepName] HTTP $statusCode" + (if (detail.isNotBlank()) " - $detail" else "")
        stepLogs.add(entry)
        Log.i(TAG, entry)
    }

    private fun logDebugRequest(
        stepName: String,
        method: String,
        url: String,
        fieldNames: List<String> = emptyList(),
        extraInfo: String = ""
    ) {
        if (!debugMode) return
        val fieldsPart = if (fieldNames.isNotEmpty()) " fields=${fieldNames.joinToString(",")}" else ""
        val extraPart = if (extraInfo.isNotBlank()) " ($extraInfo)" else ""
        val msg = "[DEBUG][$stepName] $method $url$fieldsPart$extraPart"
        stepLogs.add(msg)
        Log.d(TAG, msg)
    }

    /**
     * Decodes a ZTE `\xNN` hex-escaped string (e.g. `\x35\x39\x32...`) into its ASCII characters.
     */
    fun decodeHexEscapes(raw: String): String {
        val regex = Regex("""\\x([0-9a-fA-F]{2})""")
        return regex.replace(raw) { matchResult ->
            val code = matchResult.groupValues[1].toInt(16)
            code.toChar().toString()
        }
    }

    /**
     * Extracts `_sessionTmpToken` from ZTE HTML and decodes `\xNN` escapes into the 24-digit numeric token.
     */
    fun extractSessionTokenFromHtml(html: String): String? {
        val regex = Regex("""_sessionTmpToken\s*=\s*["']([^"']+)["']""")
        val match = regex.find(html) ?: return null
        val decoded = decodeHexEscapes(match.groupValues[1]).trim()
        return decoded.takeIf { it.isNotEmpty() }
    }

    /**
     * Lowercase 64-character hex SHA-256 digest of [input].
     */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Step 1: Fetch the login page and read `_sessionTmpToken`.
     */
    suspend fun fetchLoginPageAndToken(): RouterLoginResult = withContext(Dispatchers.IO) {
        val url = normalizedBaseUrl
        logDebugRequest("FetchLoginPage", "GET", url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val html = response.body?.string().orEmpty()
                logStep("FetchLoginPage", code, "HTML length=${html.length}")

                if (!response.isSuccessful) {
                    return@withContext RouterLoginResult.Failure(
                        FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                        "Router login page request returned HTTP $code",
                        code
                    )
                }

                // If session is already authenticated from cookies
                if (isAuthenticatedHtml(html)) {
                    val existingTok = extractSessionTokenFromHtml(html) ?: currentSessionToken.orEmpty()
                    currentSessionToken = existingTok
                    lastAuthenticatedHtml = html
                    return@withContext RouterLoginResult.Success(existingTok, html)
                }

                val token = extractSessionTokenFromHtml(html)
                if (token.isNullOrBlank()) {
                    return@withContext RouterLoginResult.Failure(
                        FailureType.TOKEN_NOT_FOUND,
                        "Token not found: _sessionTmpToken was not present in $url response",
                        code
                    )
                }
                currentSessionToken = token
                logStep("ReadSessionToken", code, "Decoded ${token.length}-char _sessionTmpToken")
                RouterLoginResult.Success(token, html)
            }
        } catch (e: SSLHandshakeException) {
            logStep("FetchLoginPage", -1, "SSL certificate error: ${e.message}")
            RouterLoginResult.Failure(
                FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                "Certificate error connecting to $url: ${e.message}"
            )
        } catch (e: IOException) {
            logStep("FetchLoginPage", -1, "Network error: ${e.message}")
            RouterLoginResult.Failure(
                FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                "Network error connecting to $url: ${e.message}"
            )
        }
    }

    /**
     * Fetches the one-time login challenge (`xmlObj`) from `/function_module/login_module/login_page/logintoken_lua.lua`
     * if the router's `g_loginToken` script uses it (`var SHA256Password = sha256(Password + xmlObj)`).
     */
    private fun fetchLoginChallengeSalt(): String? {
        val challengeUrl = "${normalizedBaseUrl}function_module/login_module/login_page/logintoken_lua.lua"
        logDebugRequest("FetchLoginChallenge", "GET", challengeUrl)
        val req = Request.Builder()
            .url(challengeUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", normalizedBaseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                logStep("FetchLoginChallenge", resp.code)
                val m = Regex("""<ajax_response_xml_root>([^<]+)</ajax_response_xml_root>""").find(body)
                m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLoginChallengeSalt failed: ${e.message}")
            null
        }
    }

    /**
     * Reusable login function for ZTE H188 router macros.
     *
     * - Reads fresh `_sessionTmpToken` on each attempt.
     * - Inspects the login page script (`g_loginToken` / `logintoken_lua.lua`) and tries up to 3 candidates
     *   in order with a 5-second pause between attempts, never retrying in a tight loop.
     * - Posts `Username`, `Password`, `action`, `_sessionTOKEN` as `application/x-www-form-urlencoded`.
     */
    suspend fun login(
        plainPassword: String,
        username: String = "admin"
    ): RouterLoginResult = withContext(Dispatchers.IO) {
        // Formula candidates in priority order (max 3 total login attempts):
        // 1. Exact formula from ZTE H188 login page JS (`g_loginToken` fetches logintoken_lua.lua -> sha256(plainPassword + xmlObj), fallback to sha256(plainPassword + token))
        // 2. sha256(plainPassword + token) / sha256(token + plainPassword)
        // 3. sha256(sha256(plainPassword) + token)
        val maxAttempts = 3
        var lastFailure: RouterLoginResult.Failure? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                logStep("LoginBackoff", 0, "Waiting 5 seconds before attempt $attempt of $maxAttempts")
                delay(5000L)
            }

            val pageRes = fetchLoginPageAndToken()
            val token: String
            val loginHtml: String
            when (pageRes) {
                is RouterLoginResult.Failure -> return@withContext pageRes
                is RouterLoginResult.Success -> {
                    if (isAuthenticatedHtml(pageRes.html)) {
                        logStep("Login", 200, "Already authenticated")
                        return@withContext pageRes
                    }
                    token = pageRes.sessionToken
                    loginHtml = pageRes.html
                }
            }

            // Check if the router already reports a lock countdown on the login page before submitting
            val lockWaitSec = extractLockWaitSeconds(loginHtml)
            if (lockWaitSec > 0) {
                val promptMsg = extractJsVar(loginHtml, "prompt_msg").orEmpty()
                return@withContext RouterLoginResult.Failure(
                    FailureType.LOGIN_REJECTED,
                    "Login rejected: Router is temporarily locked (${promptMsg.ifBlank { "wait ${lockWaitSec}s" }})."
                )
            }

            val usesLoginTokenLua = loginHtml.contains("logintoken_lua.lua") &&
                loginHtml.contains("sha256(Password+xmlObj)")

            val passwordHash: String
            val formulaLabel: String
            when (attempt) {
                1 -> {
                    val xmlChallenge = if (usesLoginTokenLua) fetchLoginChallengeSalt() else null
                    if (!xmlChallenge.isNullOrBlank()) {
                        passwordHash = sha256Hex(plainPassword + xmlChallenge)
                        formulaLabel = "sha256(password + logintoken_lua)"
                    } else {
                        passwordHash = sha256Hex(plainPassword + token)
                        formulaLabel = "sha256(password + token)"
                    }
                }
                2 -> {
                    // If attempt 1 used logintoken_lua, try direct sha256(password + token); otherwise sha256(token + password)
                    if (usesLoginTokenLua) {
                        passwordHash = sha256Hex(plainPassword + token)
                        formulaLabel = "sha256(password + token)"
                    } else {
                        passwordHash = sha256Hex(token + plainPassword)
                        formulaLabel = "sha256(token + password)"
                    }
                }
                else -> {
                    passwordHash = sha256Hex(sha256Hex(plainPassword) + token)
                    formulaLabel = "sha256(sha256(password) + token)"
                }
            }

            val fieldNames = listOf("Username", "Password", "action", "_sessionTOKEN")
            logDebugRequest(
                stepName = "LoginAttempt#$attempt",
                method = "POST",
                url = normalizedBaseUrl,
                fieldNames = fieldNames,
                extraInfo = "formula=$formulaLabel"
            )

            val formBody = FormBody.Builder()
                .add("Username", username)
                .add("Password", passwordHash)
                .add("action", "login")
                .add("_sessionTOKEN", token)
                .build()

            val postReq = Request.Builder()
                .url(normalizedBaseUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedBaseUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(formBody)
                .build()

            var shouldContinue = false
            try {
                client.newCall(postReq).execute().use { response ->
                    val code = response.code
                    val respHtml = response.body?.string().orEmpty()
                    logStep("LoginAttempt#$attempt", code, "formula=$formulaLabel, responseLen=${respHtml.length}")

                    // Update session token if present in response
                    extractSessionTokenFromHtml(respHtml)?.let { currentSessionToken = it }

                    if (!response.isSuccessful) {
                        lastFailure = RouterLoginResult.Failure(
                            FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                            "Login POST returned HTTP $code",
                            code
                        )
                        shouldContinue = true
                        return@use
                    }

                    if (isAuthenticatedHtml(respHtml)) {
                        lastAuthenticatedHtml = respHtml
                        logStep("LoginSuccess", code, "Authenticated on attempt #$attempt ($formulaLabel)")
                        return@withContext RouterLoginResult.Success(
                            currentSessionToken ?: token,
                            respHtml
                        )
                    }

                    // Inspect explicit router error variables in the returned login page
                    val loginErrMsg = extractJsVar(respHtml, "login_err_msg").orEmpty()
                    val promptMsg = extractJsVar(respHtml, "prompt_msg").orEmpty()
                    val lockSec = extractLockWaitSeconds(respHtml)
                    val explicitErr = listOf(loginErrMsg, promptMsg).filter { it.isNotBlank() }.joinToString(" ")

                    if (explicitErr.isNotBlank() || lockSec > 0) {
                        val reason = buildString {
                            append("Login rejected by router")
                            if (explicitErr.isNotBlank()) append(": \"$explicitErr\"")
                            if (lockSec > 0) append(" (lockout ${lockSec}s)")
                        }
                        lastFailure = RouterLoginResult.Failure(
                            FailureType.LOGIN_REJECTED,
                            reason,
                            code
                        )
                        // If the router explicitly locked login or rejected credentials on the page's native formula,
                        // do not hammer the router if locked.
                        if (lockSec > 0 || (attempt == 1 && usesLoginTokenLua && explicitErr.isNotBlank())) {
                            return@withContext lastFailure!!
                        }
                    } else {
                        lastFailure = RouterLoginResult.Failure(
                            FailureType.LOGIN_PAGE_RETURNED_AGAIN,
                            "Login page returned again (Frm_Username / Frm_Password still present without an explicit router error message)",
                            code
                        )
                    }
                }
            } catch (e: SSLHandshakeException) {
                logStep("LoginAttempt#$attempt", -1, "SSL error: ${e.message}")
                return@withContext RouterLoginResult.Failure(
                    FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                    "Certificate error during login POST: ${e.message}"
                )
            } catch (e: IOException) {
                logStep("LoginAttempt#$attempt", -1, "Network error: ${e.message}")
                return@withContext RouterLoginResult.Failure(
                    FailureType.NETWORK_OR_CERTIFICATE_ERROR,
                    "Network error during login POST: ${e.message}"
                )
            }
            if (shouldContinue) {
                continue
            }
        }

        lastFailure ?: RouterLoginResult.Failure(
            FailureType.LOGIN_PAGE_RETURNED_AGAIN,
            "Login did not succeed after $maxAttempts attempts"
        )
    }

    /**
     * A successful login is detected when the response no longer contains `Frm_Username` and `Frm_Password`,
     * or when it contains the rendered menu element `id='mmInternet'` / `id="mmInternet"`.
     */
    fun isAuthenticatedHtml(html: String): Boolean {
        if (html.isBlank()) return false
        val hasLoginInputs = html.contains("Frm_Username") && html.contains("Frm_Password")
        val hasRenderedInternetMenu = Regex("""id\s*=\s*['"]mmInternet['"]""").containsMatchIn(html)
        return !hasLoginInputs || hasRenderedInternetMenu
    }

    private fun extractJsVar(html: String, varName: String): String? {
        val regex = Regex("""\b$varName\s*=\s*["']([^"']*)["']""")
        val raw = regex.find(html)?.groupValues?.get(1) ?: return null
        return decodeHexEscapes(raw).trim()
    }

    private fun extractLockWaitSeconds(html: String): Int {
        val raw = extractJsVar(html, "DiaplayLockTime") ?: return 0
        return raw.toIntOrNull() ?: 0
    }

    /**
     * Step 3: Navigates to / fetches the Parental Control page and toggles `Enable` for [deviceName].
     *
     * Logs every request URL, method, and form field names in debug mode so any router-specific endpoints
     * or parameters are visible in logs.
     */
    suspend fun setParentalControlDeviceState(
        deviceName: String,
        enableRule: Boolean
    ): ParentalControlToggleResult = withContext(Dispatchers.IO) {
        val targetValue = if (enableRule) "1" else "0"

        // 1. Discover the Parental Control page URL from `menu_items` / `menu_subitems` for `smParentCtrl`
        val parentCtrlPageName = findMenuPageInHtml(lastAuthenticatedHtml, "smParentCtrl")
            ?: fetchInternetMenuAndFindParentCtrl()

        if (parentCtrlPageName.isNullOrBlank()) {
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Login succeeded, but the Parental Control page URL (smParentCtrl) could not be found in menu metadata. Check debug logs or capture the toggle request from Chrome's Network tab.",
                failureType = FailureType.DEVICE_CONTROL_ERROR
            )
        }

        val pageRelativeUrl = if (parentCtrlPageName.startsWith("getpage.lua")) {
            parentCtrlPageName
        } else {
            "getpage.lua?pid=123&nextpage=$parentCtrlPageName&Menu3Location=0"
        }
        val fullPageUrl = normalizedBaseUrl + pageRelativeUrl.removePrefix("/")
        logDebugRequest("FetchParentalControlPage", "GET", fullPageUrl)

        val pageHtml = try {
            val req = Request.Builder()
                .url(fullPageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedBaseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                logStep("FetchParentalControlPage", resp.code, "len=${body.length}")
                if (!resp.isSuccessful) {
                    return@withContext ParentalControlToggleResult(
                        success = false,
                        message = "Failed to load Parental Control page ($fullPageUrl): HTTP ${resp.code}",
                        httpStatusCode = resp.code,
                        failureType = FailureType.DEVICE_CONTROL_ERROR
                    )
                }
                extractSessionTokenFromHtml(body)?.let { currentSessionToken = it }
                body
            }
        } catch (e: IOException) {
            logStep("FetchParentalControlPage", -1, "Network error: ${e.message}")
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Network error loading Parental Control page: ${e.message}",
                failureType = FailureType.NETWORK_OR_CERTIFICATE_ERROR
            )
        }

        // 2. Extract the data ServerAddr from `<address><span>...</span></address>` inside the Parental Control HTML
        val serverAddrs = Regex("""<address[^>]*>\s*<span[^>]*>([^<]+)</span>\s*</address>""", RegexOption.IGNORE_CASE)
            .findAll(pageHtml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val parentCtrlServerAddr = serverAddrs.firstOrNull { it.contains("ParentCtrl", ignoreCase = true) }
            ?: serverAddrs.firstOrNull()

        if (parentCtrlServerAddr.isNullOrBlank()) {
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Loaded Parental Control HTML, but no <address><span> data endpoint was found. Please capture the toggle POST request from Chrome's Network tab.",
                failureType = FailureType.DEVICE_CONTROL_ERROR
            )
        }

        // 3. GET the XML data from `parentCtrlServerAddr` to list devices (0..10) and their `_InstID`
        val dataUrl = normalizedBaseUrl + parentCtrlServerAddr.removePrefix("/")
        logDebugRequest("FetchParentalControlDataXml", "GET", dataUrl)

        val xmlBody = try {
            val req = Request.Builder()
                .url(dataUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedBaseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                logStep("FetchParentalControlDataXml", resp.code, "len=${body.length}")
                if (!resp.isSuccessful) {
                    return@withContext ParentalControlToggleResult(
                        success = false,
                        message = "Failed to load Parental Control XML ($dataUrl): HTTP ${resp.code}",
                        httpStatusCode = resp.code,
                        failureType = FailureType.DEVICE_CONTROL_ERROR
                    )
                }
                body
            }
        } catch (e: IOException) {
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Network error fetching Parental Control XML: ${e.message}",
                failureType = FailureType.NETWORK_OR_CERTIFICATE_ERROR
            )
        }

        val devices = parseParentalControlDevicesFromXml(xmlBody)
        logStep("ParseParentalControlDevices", 200, "Found ${devices.size} rules: ${devices.joinToString { "${it.index}:${it.name}(${it.instId})" }}")

        if (devices.isEmpty()) {
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "No Parental Control device rules were found in $parentCtrlServerAddr XML response. If the toggle request uses a different endpoint, please capture it from Chrome's Network tab.",
                failureType = FailureType.DEVICE_CONTROL_ERROR
            )
        }

        val needle = deviceName.trim().lowercase()
        val exactMatches = devices.filter { it.name.lowercase() == needle }
        val partialMatches = devices.filter {
            it.name.lowercase().contains(needle) || needle.contains(it.name.lowercase())
        }
        val matches = exactMatches.ifEmpty { partialMatches }

        if (matches.isEmpty()) {
            val available = devices.joinToString(", ") { "${it.name} (index ${it.index})" }
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Device '$deviceName' not found in Parental Control rules. Available: $available",
                failureType = FailureType.DEVICE_CONTROL_ERROR
            )
        }
        if (matches.size > 1) {
            val matchedList = matches.joinToString(", ") { "${it.name} (index ${it.index})" }
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Multiple Parental Control rules matched '$deviceName': $matchedList",
                failureType = FailureType.DEVICE_CONTROL_ERROR
            )
        }

        val targetDevice = matches.first()
        val token = currentSessionToken ?: ""

        // 4. Build the exact ZTE `InstSwitch` POST payload discovered in the router's JS:
        //    var PostData = "IF_ACTION=Apply&Enable=" + instSwitchValue + "&_InstID=" + _InstIDValue + "&_sessionTOKEN=" + _sessionTmpToken;
        //    Header: Check = sha256(PostData)
        val postFields = linkedMapOf(
            "IF_ACTION" to "Apply",
            "Enable" to targetValue,
            "_InstID" to targetDevice.instId,
            "_sessionTOKEN" to token
        )

        val rawPostData = postFields.entries.joinToString("&") { (k, v) -> "$k=$v" }
        val checkHeader = sha256Hex(rawPostData)

        logDebugRequest(
            stepName = "ToggleParentalControlRadio",
            method = "POST",
            url = dataUrl,
            fieldNames = postFields.keys.toList(),
            extraInfo = "device=${targetDevice.name}, index=${targetDevice.index}, _InstID=${targetDevice.instId}, Enable=$targetValue"
        )

        try {
            val req = Request.Builder()
                .url(dataUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedBaseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Check", checkHeader)
                .post(rawPostData.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val respXml = resp.body?.string().orEmpty()
                val errStr = Regex("""<IF_ERRORSTR>([^<]*)</IF_ERRORSTR>""").find(respXml)?.groupValues?.get(1)?.trim()
                logStep("ToggleParentalControlRadio", code, "IF_ERRORSTR=${errStr ?: "N/A"}")

                if (resp.isSuccessful && (errStr == "SUCC" || respXml.contains("<IF_ERRORSTR>SUCC</IF_ERRORSTR>"))) {
                    return@withContext ParentalControlToggleResult(
                        success = true,
                        message = "Parental Control rule for '${targetDevice.name}' (index ${targetDevice.index}, _InstID=${targetDevice.instId}) set to Enable=$targetValue and confirmed (IF_ERRORSTR=SUCC).",
                        httpStatusCode = code
                    )
                }

                return@withContext ParentalControlToggleResult(
                    success = false,
                    message = "Toggle POST to $parentCtrlServerAddr returned HTTP $code (IF_ERRORSTR=${errStr ?: "unknown"}). Please capture the Parental Control toggle request from Chrome's Network tab so we can verify the exact fields.",
                    httpStatusCode = code,
                    failureType = FailureType.DEVICE_CONTROL_ERROR
                )
            }
        } catch (e: IOException) {
            logStep("ToggleParentalControlRadio", -1, "Network error: ${e.message}")
            return@withContext ParentalControlToggleResult(
                success = false,
                message = "Network error sending Parental Control toggle POST: ${e.message}",
                failureType = FailureType.NETWORK_OR_CERTIFICATE_ERROR
            )
        }
    }

    private fun findMenuPageInHtml(html: String, menuId: String): String? {
        if (html.isBlank()) return null
        // Look for `menu_items[...]['smParentCtrl']['page'] = '...'` or `menu_subitems[...]['smParentCtrl']['page'] = '...'`
        val jsRegex = Regex("""\[['"]$menuId['"]\]\s*\[\s*['"]page['"]\s*\]\s*=\s*['"]([^'"]+)['"]""")
        jsRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }

        // Or rendered HTML attribute: id='smParentCtrl' ... MenuPage='...'
        val attrRegex1 = Regex("""id\s*=\s*['"]$menuId['"][^>]*MenuPage\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        attrRegex1.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }

        val attrRegex2 = Regex("""MenuPage\s*=\s*['"]([^'"]+)['"][^>]*id\s*=\s*['"]$menuId['"]""", RegexOption.IGNORE_CASE)
        return attrRegex2.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun fetchInternetMenuAndFindParentCtrl(): String? {
        val mmInternetPage = findMenuPageInHtml(lastAuthenticatedHtml, "mmInternet") ?: return null
        val url = normalizedBaseUrl + "getpage.lua?pid=123&nextpage=${URLEncoder.encode(mmInternetPage, "UTF-8")}&Menu3Location=0"
        logDebugRequest("FetchInternetMenu", "GET", url)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", normalizedBaseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                logStep("FetchInternetMenu", resp.code, "len=${html.length}")
                extractSessionTokenFromHtml(html)?.let { currentSessionToken = it }
                findMenuPageInHtml(html, "smParentCtrl")
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchInternetMenuAndFindParentCtrl failed: ${e.message}")
            null
        }
    }

    /**
     * Parses `<Instance>` blocks from the ZTE Parental Control XML response.
     */
    fun parseParentalControlDevicesFromXml(xml: String): List<ParentalControlDevice> {
        val result = mutableListOf<ParentalControlDevice>()
        val instanceRegex = Regex("""<Instance>([\s\S]*?)</Instance>""", RegexOption.IGNORE_CASE)
        val pairRegex = Regex("""<ParaName>([^<]+)</ParaName>\s*<ParaValue>([^<]*)</ParaValue>""", RegexOption.IGNORE_CASE)

        var idx = 0
        for (instMatch in instanceRegex.findAll(xml)) {
            val block = instMatch.groupValues[1]
            val params = linkedMapOf<String, String>()
            for (pair in pairRegex.findAll(block)) {
                params[pair.groupValues[1].trim()] = pair.groupValues[2].trim()
            }
            val instId = params["_InstID"] ?: continue
            val name = params["Name"]
                ?: params["HostName"]
                ?: params["RuleName"]
                ?: params["instName_ParentCtrl"]
                ?: continue
            val enable = params["Enable"] ?: "0"
            if (idx in 0..10) {
                result.add(
                    ParentalControlDevice(
                        index = idx,
                        instId = instId,
                        name = name,
                        currentEnable = enable,
                        allParams = params
                    )
                )
            }
            idx++
        }
        return result
    }

    /**
     * Step 4: Always log out at the end of the macro so the router's single admin session is released.
     *
     * Mirrors ZTE `LogOff()` in HTML:
     *   1. GET `/common_page/template_token_check.lua?sessToken=<_sessionTmpToken>`
     *   2. POST `IF_LogOff=1&sess_token=<_sessionTmpToken>` to `https://192.168.1.1/`
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val token = currentSessionToken
        if (token.isNullOrBlank()) {
            logStep("Logout", 0, "Skipped (no active session token)")
            return@withContext false
        }

        val csrfUrl = "${normalizedBaseUrl}common_page/template_token_check.lua?sessToken=$token"
        logDebugRequest("LogoutCsrfCheck", "GET", csrfUrl)
        try {
            val csrfReq = Request.Builder()
                .url(csrfUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedBaseUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()
            client.newCall(csrfReq).execute().use { resp ->
                logStep("LogoutCsrfCheck", resp.code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Logout CSRF check failed: ${e.message}")
        }

        val logoutFields = listOf("IF_LogOff", "sess_token")
        logDebugRequest("Logout", "POST", normalizedBaseUrl, logoutFields)
        val formBody = FormBody.Builder()
            .add("IF_LogOff", "1")
            .add("sess_token", token)
            .build()

        val postReq = Request.Builder()
            .url(normalizedBaseUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", normalizedBaseUrl)
            .post(formBody)
            .build()

        return@withContext try {
            client.newCall(postReq).execute().use { resp ->
                logStep("Logout", resp.code, "Session released")
                resp.isSuccessful
            }
        } catch (e: IOException) {
            logStep("Logout", -1, "Network error during logout: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "ZteRouterHttp"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
