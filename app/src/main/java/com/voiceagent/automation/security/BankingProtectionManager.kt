package com.voiceagent.automation.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages the security exclusion list for banking, financial, and digital wallet applications.
 * Ensures Voice Agent never reads, intercepts, records, or injects automation into sensitive financial apps.
 */
object BankingProtectionManager {
    private const val TAG = "BankingProtection"
    private const val PREFS_NAME = "banking_protection_prefs"
    private const val KEY_PROTECTION_ENABLED = "banking_protection_enabled"
    private const val KEY_CUSTOM_PROTECTED_PACKAGES = "custom_protected_packages"

    // Default built-in list of prominent Egyptian & international banking/fintech apps
    val DEFAULT_PROTECTED_PACKAGES = listOf(
        "eg.com.instapay",                      // InstaPay Egypt
        "com.cib.cibmobile",                    // CIB Mobile Banking
        "com.nbe.mobilebanking",                // National Bank of Egypt (NBE)
        "com.banquemisr.mobilebanking",         // Banque Misr (BM Online)
        "com.qnb.qnbmobile",                    // QNB Alahli
        "com.fawry.myfawry",                    // myFawry
        "com.vodafone.vodafonecash",            // Vodafone Cash
        "com.orange.orangemoney",               // Orange Cash Egypt
        "com.etisalat.etisalatapp",             // Etisalat Cash / My Etisalat
        "com.we.pay",                           // WE Pay
        "com.telda.app",                        // Telda
        "com.thndr.app",                        // Thndr Investment
        "com.paypal.android.p2pmobile",         // PayPal
        "com.binance.dev",                      // Binance
        "com.hsbc.hsbcnet",                     // HSBC
        "com.adib.mobilebanking",               // ADIB Egypt
        "com.albaraka.mobile",                  // Al Baraka Bank Egypt
        "com.alexbank.mobilebanking",           // AlexBank
        "com.abk.egypt"                         // Al Ahli Bank of Kuwait (ABK Egypt)
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isProtectionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROTECTION_ENABLED, true)
    }

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
        Log.i(TAG, "Banking Protection enabled set to: $enabled")
    }

    fun getProtectedPackages(context: Context): Set<String> {
        val custom = getPrefs(context).getStringSet(KEY_CUSTOM_PROTECTED_PACKAGES, emptySet()) ?: emptySet()
        return DEFAULT_PROTECTED_PACKAGES.toSet() + custom
    }

    fun addCustomPackage(context: Context, packageName: String): Boolean {
        val cleanPkg = packageName.trim().lowercase()
        if (cleanPkg.isBlank()) return false
        val current = getPrefs(context).getStringSet(KEY_CUSTOM_PROTECTED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(cleanPkg)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_PROTECTED_PACKAGES, current).apply()
        Log.i(TAG, "Added custom protected package: $cleanPkg")
        return true
    }

    fun removeCustomPackage(context: Context, packageName: String): Boolean {
        val cleanPkg = packageName.trim().lowercase()
        val current = getPrefs(context).getStringSet(KEY_CUSTOM_PROTECTED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        val removed = current.remove(cleanPkg)
        if (removed) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_PROTECTED_PACKAGES, current).apply()
            Log.i(TAG, "Removed custom protected package: $cleanPkg")
        }
        return removed
    }

    /**
     * Checks if a target package is protected against automation or inspection.
     */
    fun isPackageProtected(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (!isProtectionEnabled(context)) return false

        val lowerPkg = packageName.trim().lowercase()
        val protectedList = getProtectedPackages(context)
        
        // Exact match
        if (protectedList.contains(lowerPkg)) {
            Log.w(TAG, "Security Alert: Access to financial app blocked by Banking Protection: $packageName")
            return true
        }

        // Substring / keyword check for financial apps
        val financialKeywords = listOf("bank", "instapay", "wallet", "fintech", "payment", "fawry", "vodafonecash", "telda")
        for (kw in financialKeywords) {
            if (lowerPkg.contains(kw) && !lowerPkg.contains("com.voiceagent") && !lowerPkg.contains("masrofy")) {
                Log.w(TAG, "Security Alert: Heuristic match blocked sensitive package: $packageName")
                return true
            }
        }

        return false
    }
}
