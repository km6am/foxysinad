package com.km6am.foxysinad

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import com.chaquo.python.PyObject
import org.json.JSONObject
import java.io.File
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
        // Android hands silent audio to an app that isn't on screen, so for
        // now the screen stays on (a foreground service will replace this).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        // chrome://inspect, on debug builds only
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
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

        /** The folder's finished CSVs (and any PNGs) to Android's share sheet:
         *  Drive, email, Files, a nearby computer. Only folders inside runs/.
         *  Returns "" or what went wrong, for the page's toast. */
        @JavascriptInterface
        fun share(folder: String): String {
            val runs = File(filesDir, "runs").canonicalFile
            val dir = File(folder).canonicalFile
            if (dir != runs && !dir.path.startsWith(runs.path + File.separator))
                return "only the app's runs can be shared"
            val files = dir.listFiles { f ->
                f.isFile && f.extension in setOf("csv", "png") && !f.name.endsWith(".partial.csv")
            }.orEmpty().sortedBy { it.name }
            if (files.isEmpty()) return "no finished files in ${dir.name}"
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.files", it)
            })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (files.all { it.extension == "csv" }) "text/csv" else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Foxy SINAD: ${dir.name}")
                // the grant reaches the chosen app through the clip
                clipData = ClipData.newRawUri(null, uris[0]).also { c ->
                    uris.drop(1).forEach { c.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Log.i(TAG, "share: ${files.size} files from ${dir.name}")
            runOnUiThread { startActivity(Intent.createChooser(send, "Share ${files.size} files")) }
            return ""
        }
    }

    inner class Sink {
        fun event(json: String) {
            Log.d(TAG, json)
            web.post { web.evaluateJavascript("window.foxsinadEvent(${JSONObject.quote(json)})", null) }
        }
    }
}
