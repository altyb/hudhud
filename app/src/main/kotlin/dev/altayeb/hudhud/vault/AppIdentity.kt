package dev.altayeb.hudhud.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Who is really asking: the signing certificates Android reports for a package, and whether it is a known browser. */
object AppIdentity {
    fun certs(context: Context, pkg: String): Set<String> = runCatching {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }.getOrDefault(emptySet())

    fun allowlistJson(context: Context): String =
        context.assets.open("privileged_browsers.json").bufferedReader().use { it.readText() }

    /**
     * Any browser, listed or not. A browser shows every website under one certificate, so a login must never be linked to
     * the browser itself: that would offer it on every site.
     */
    fun isBrowser(context: Context, pkg: String): Boolean {
        val intent = android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_BROWSER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).any { it.activityInfo.packageName == pkg } ||
            isTrustedBrowser(context, pkg)
    }

    private var browsers: Map<String, Set<String>>? = null

    /** A web domain is only believed when it comes from a browser on this list, with the right signature. Any app can claim a domain. */
    fun isTrustedBrowser(context: Context, pkg: String): Boolean {
        val list = browsers ?: run {
            val apps = org.json.JSONObject(allowlistJson(context)).getJSONArray("apps")
            val out = HashMap<String, Set<String>>()
            for (i in 0 until apps.length()) {
                val info = apps.getJSONObject(i).getJSONObject("info")
                val sigs = info.getJSONArray("signatures")
                out[info.getString("package_name")] = (0 until sigs.length()).map {
                    sigs.getJSONObject(it).getString("cert_fingerprint_sha256").replace(":", "").lowercase()
                }.toSet()
            }
            out.also { browsers = it }
        }
        val want = list[pkg] ?: return false
        return certs(context, pkg).any { it in want }
    }
}
