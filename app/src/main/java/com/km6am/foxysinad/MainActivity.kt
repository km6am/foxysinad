package com.km6am.foxysinad

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.chaquo.python.PyObject
import org.json.JSONObject
import kotlin.concurrent.thread

private const val TAG = "FoxySinad"

/** foxsinad's bench page, talking to the bench engine through
 *  window.FoxsinadBridge (docs/ui-protocol.md in foxsinad). */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var phone: PyObject? = null
    private var pump: PyObject? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestWindowFeature(Window.FEATURE_NO_TITLE)  // the page has its own top bar
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true           // the page keeps its preferences
            addJavascriptInterface(Bridge(), "FoxsinadBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = attach()
            }
        }
        // Android 15+ draws apps edge to edge: keep the page (its sticky top
        // bar above all) out from under the status bar, cutout and keyboard.
        setContentView(FrameLayout(this).apply {
            addView(web)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        })
        thread {
            phone = FoxRuntime.phone(this)
            runOnUiThread { web.loadUrl("file:///android_asset/static/index.html") }
        }
    }

    /** Events to this page from now on, the snapshot first. */
    private fun attach() {
        pump?.callAttr("close")
        pump = phone?.callAttr("attach", Sink())
    }

    override fun onDestroy() {
        pump?.callAttr("close")
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun send(json: String): String = phone!!.callAttr("handle", json).toString()
    }

    inner class Sink {
        fun event(json: String) {
            Log.d(TAG, json)
            web.post { web.evaluateJavascript("window.foxsinadEvent(${JSONObject.quote(json)})", null) }
        }
    }
}
