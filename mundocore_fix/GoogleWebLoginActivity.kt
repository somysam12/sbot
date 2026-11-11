package com.mundo.proxy.gms

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.mundo.core.gms.GmsAuthClient
import com.mundo.core.gms.GmsCheckin
import com.mundo.core.gms.GmsConstants
import com.mundo.core.gms.FakeSignInService

/**
 * WebView activity for Google EmbeddedSetup OAuth2 sign-in.
 * Loads Google's EmbeddedSetup URL, intercepts the oauth2 token via JsBridge,
 * then exchanges it for a master token via GmsAuthClient.
 */
class GoogleWebLoginActivity : Activity() {

    private var webView: WebView? = null
    private var callingPackage = ""
    private var callbackBinder: IBinder? = null
    private var requestIdToken = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: started")

        callingPackage = intent.getStringExtra("callingPackage") ?: run { finish(); return }
        callbackBinder = intent.extras?.getBinder("callback")
        requestIdToken = intent.getBooleanExtra("requestIdToken", false)

        // Show loading UI while WebView loads
        val container = FrameLayout(this)
        val loading = GoogleAuthUi.buildSignInContainer(this)
        container.addView(loading)

        val wv = WebView(this)
        webView = wv

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: page loaded: $url")
            }
        }

        // JsBridge — name MUST be "mm" (Google's EmbeddedSetup calls window.mm.accountReady)
        wv.addJavascriptInterface(JsBridge(), "mm")
        wv.loadUrl(GmsConstants.EMBEDDED_SETUP_URL)

        // Show WebView on top of loading UI
        container.addView(wv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(container)
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun accountReady(oauthToken: String) {
            Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: JsBridge.accountReady called")
            runOnUiThread {
                GmsAuthClient.exchangeToken(applicationContext, callingPackage, oauthToken) { account, error ->
                    runOnUiThread {
                        if (account != null) {
                            Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: sign-in complete: ${account.email}")
                            FakeSignInService.deliverSignInResult(callbackBinder, account, 0)
                        } else {
                            Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: sign-in failed: error=$error")
                            FakeSignInService.deliverSignInResult(
                                callbackBinder,
                                com.mundo.core.gms.GoogleSignInAccount("", "", ""),
                                error
                            )
                        }
                        finish()
                    }
                }
            }
        }

        @JavascriptInterface
        fun signInCancelled() {
            Log.w(GmsConstants.TAG, "GoogleWebLoginActivity: user cancelled")
            runOnUiThread {
                FakeSignInService.deliverSignInResult(
                    callbackBinder,
                    com.mundo.core.gms.GoogleSignInAccount("", "", ""),
                    12 // 12 = SIGN_IN_CANCELLED
                )
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        FakeSignInService.deliverSignInResult(
            callbackBinder,
            com.mundo.core.gms.GoogleSignInAccount("", "", ""),
            12
        )
        super.onBackPressed()
    }
}
