package com.gotcha.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.gotcha.BuildConfig
import com.gotcha.util.GotchaLog

/** Opens a web page for Samosa sign-in or the account pages. */
fun interface SamosaBrowser {
    /** False when the phone has nothing that can show a web page. */
    fun open(context: Context, url: Uri): Boolean
}

/**
 * A Custom Tab when the phone has a browser that supports one, otherwise any
 * browser. A Custom Tab shares the browser's cookies, so a user who signed in
 * on the account website stays signed in there when Gotcha opens it again.
 *
 * Chrome is preferred over the phone's default browser when it is installed:
 * Google sign-in on the account website opens Google's account chooser, and
 * some default browsers (seen with Aloha, 2026-11-15) leave that on a blank
 * page so the sign-in never returns to Gotcha. Without Chrome, the default
 * Custom Tabs browser is used as before.
 */
object CustomTabBrowser : SamosaBrowser {
    private const val TAG = "SamosaBrowser"

    /** Custom Tabs browsers that run Google sign-in correctly, most preferred first. */
    internal val PREFERRED_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    )

    /** The first installed [PREFERRED_BROWSERS] entry with Custom Tabs, or null. */
    internal fun preferredBrowser(context: Context): String? = try {
        CustomTabsClient.getPackageName(context, PREFERRED_BROWSERS, true)
    } catch (e: SecurityException) {
        GotchaLog.d(TAG, e) { "Cannot query Custom Tabs browsers, using the default" }
        null
    }

    override fun open(context: Context, url: Uri): Boolean = try {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        preferredBrowser(context)?.let { tab.intent.setPackage(it) }
        tab.launchUrl(context, url)
        true
    } catch (e: ActivityNotFoundException) {
        GotchaLog.d(TAG, e) { "No Custom Tabs browser, trying a plain browser" }
        try {
            val view = Intent(Intent.ACTION_VIEW, url)
            if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
            true
        } catch (e2: ActivityNotFoundException) {
            GotchaLog.d(TAG, e2) { "No browser at all" }
            false
        }
    }
}

/** The Samosa account website: sign-in, usage, devices, billing. */
object SamosaDashboard {

    /** A part of the account website Gotcha links to. */
    enum class Section(val tab: String?) {
        OVERVIEW(null),
        USAGE("usage"),
        SESSIONS("sessions"),
        BILLING("billing")
    }

    /** The website's origin, without a trailing slash. */
    val baseUrl: String = BuildConfig.SAMOSA_DASHBOARD_URL.trimEnd('/')

    /**
     * The page for [section]. The website does not read `tab` yet and opens
     * on its overview, which is still the right place to manage the account.
     */
    fun url(section: Section = Section.OVERVIEW): Uri {
        val builder = rootBuilder()
        section.tab?.let { builder.appendQueryParameter("tab", it) }
        return builder.build()
    }

    /** The website root, with a `/` path so query parameters attach cleanly. */
    internal fun rootBuilder(): Uri.Builder {
        val builder = Uri.parse(baseUrl).buildUpon()
        if (Uri.parse(baseUrl).path.isNullOrEmpty()) builder.path("/")
        return builder
    }

    /** Opens [section] in a Custom Tab. False when no browser could open it. */
    fun open(
        context: Context,
        section: Section = Section.OVERVIEW,
        browser: SamosaBrowser = CustomTabBrowser
    ): Boolean = browser.open(context, url(section))
}
