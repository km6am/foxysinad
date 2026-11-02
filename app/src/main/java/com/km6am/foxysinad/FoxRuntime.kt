package com.km6am.foxysinad

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** The bench engine (foxsinad's, in Python), one per process: Android
 *  recreates the screen when a USB device comes or goes, and a run must
 *  outlive that. A foreground service will own this later. */
object FoxRuntime {
    private var phone: PyObject? = null

    @Synchronized
    fun phone(context: Context): PyObject {
        phone?.let { return it }
        val app = context.applicationContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        // foxsinad.picofox loads the PicoFox driver from a file, so it's copied out of the APK.
        val foxctl = File(app.filesDir, "foxsinad-firmware/tools/foxctl.py")
        foxctl.parentFile!!.mkdirs()
        app.assets.open("foxsinad-firmware/tools/foxctl.py").use { i -> foxctl.outputStream().use { i.copyTo(it) } }
        val module = Python.getInstance().getModule("phone")
        module.callAttr("create", UsbOpener(app), AudioCapture(app), app.filesDir.path)
        return module.also { phone = it }
    }
}

/** How the engine reaches the PicoFox: Python calls open() at connect. */
class UsbOpener(private val context: Context) {
    fun open(): UsbLink? = UsbLink.open(context)
}
