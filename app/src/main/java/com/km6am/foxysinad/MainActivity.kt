package com.km6am.foxysinad

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max

private const val TAG = "FoxySinad"
private const val FS = 48000
private const val CHANNELS = 2
private const val BLOCK_FRAMES = FS / 4  // 0.25 s, the bench analyzer's window
private const val POLL_BLOCKS = 16       // a fox status round trip every 4 s, well inside its 60 s host timeout

/** Sanity check: the PicoFox on (via foxctl over USB, if plugged in) and USB
 *  audio in, foxsinad's SINAD analysis (in Python) out, as text. */
class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    @Volatile private var running = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        out = TextView(this).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; textSize = 11f }
        val toggle = Button(this).apply { text = "Start" }
        toggle.setOnClickListener {
            if (running) { running = false; toggle.text = "Start" }
            else { running = true; toggle.text = "Stop"; thread { measure(); runOnUiThread { toggle.text = "Start" } } }
        }
        scroll = ScrollView(this).apply { addView(out) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(24, 24, 24, 24)
            addView(toggle)
            addView(scroll)
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        thread {
            val meter = runCatching { Python.getInstance().getModule("meter") }
                .getOrElse { return@thread report("python error: $it") }
            for (f in listOf("versions", "selftest"))
                report(runCatching { meter.callAttr(f).toString() }.getOrElse { "python error: $it" })
        }
    }

    // Every line also goes to files/meter.txt (the startup lines, then the
    // latest run), so a run can be read back over adb.
    private fun report(line: String) {
        Log.i(TAG, line)
        File(filesDir, "meter.txt").appendText(line + "\n")
        runOnUiThread {
            out.append(line + "\n")
            if (out.lineCount > 400) out.text = out.text.toString().lines().takeLast(300).joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun measure() {
        // A fresh file per run, not per screen: Android recreates the screen
        // when a USB device comes or goes, which used to wipe the last run.
        File(filesDir, "meter.txt").writeText("")
        var link: UsbLink? = null
        try {
            val py = Python.getInstance()
            val meter = py.getModule("meter")
            val bench = py.getModule("bench")
            val usbIn = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: return report("no USB audio input")
            link = UsbLink.open(this)
            try {
                if (link == null) report("no PicoFox: measuring audio only")
                else report(bench.callAttr("start", link).toString())
                record(meter, if (link != null) bench else null, usbIn)
            } finally {
                // Whatever failed, the output goes off (after the ID owed).
                if (link != null) report(runCatching { bench.callAttr("stop").toString() }.getOrElse { "fox error: $it" })
            }
        } catch (e: Exception) {
            report("error: $e")
        } finally {
            link?.close()
            running = false
            report("stopped")
        }
    }

    private fun record(meter: PyObject, bench: PyObject?, usbIn: AudioDeviceInfo) {
        // UNPROCESSED only where the phone declares it; otherwise the
        // least-processed source left is VOICE_RECOGNITION.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(FS)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minBuf * 4, BLOCK_FRAMES * CHANNELS * 2 * 4))
            .build()
        rec.setPreferredDevice(usbIn)
        val block = ByteArray(BLOCK_FRAMES * CHANNELS * 2)
        rec.startRecording()
        try {
            report("source ${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"}, " +
                "routed '${rec.routedDevice?.productName}', ${rec.sampleRate} Hz, ${rec.channelCount} ch")
            var blocks = 0
            while (running) {
                var got = 0
                while (got < block.size && running) {
                    val n = rec.read(block, got, block.size - got)
                    if (n <= 0) return report("read error $n")
                    got += n
                }
                if (got == block.size) report(meter.callAttr("block", block, CHANNELS, FS).toString())
                if (bench != null && ++blocks % POLL_BLOCKS == 0)
                    report(runCatching { bench.callAttr("poll").toString() }.getOrElse { "fox error: $it" })
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }
}
