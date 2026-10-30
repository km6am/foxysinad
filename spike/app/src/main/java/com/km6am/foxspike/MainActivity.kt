/* Feasibility spike for the foxsinad Android kit. Through a USB hub, can
the phone see the PicoFox and the UFO202, talk to the PicoFox over USB
serial, and record the UFO202 without processing? Run logs one line per
finding under the tag FoxSpike and saves the recording as files/spike.wav
for the Mac's analyzer (compare.py), so both captures are judged by the
same code. */

package com.km6am.foxspike

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "FoxSpike"
private const val PICO_VID = 0x2E8A  // Raspberry Pi, as foxctl autodetects
private const val FS = 48000
private const val SECONDS = 10

class MainActivity : Activity() {
    private lateinit var out: TextView
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        out = TextView(this).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        val run = Button(this).apply { text = "Run" }
        run.setOnClickListener {
            run.isEnabled = false
            thread { runAll(); runOnUiThread { run.isEnabled = true } }
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(run)
                addView(out)
            })
        })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun report(key: String, value: String) {
        Log.i(TAG, "$key $value")
        runOnUiThread { out.append("$key $value\n") }
    }

    private fun runAll() {
        runOnUiThread { out.text = "" }
        for (d in usb.deviceList.values) {
            val name = runCatching { "${d.manufacturerName} / ${d.productName}" }.getOrDefault("?")
            report("USB", "%04x:%04x %s".format(d.vendorId, d.productId, name))
        }
        report("SERIAL", runCatching { serialCheck() }.getOrElse { "error: $it" })
        report("AUDIO", runCatching { audioCheck() }.getOrElse { "error: $it" })
        report("DONE", "")
    }

    // --- PicoFox over USB serial -------------------------------------------

    private fun serialCheck(): String {
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == PICO_VID }
            ?: return "no PicoFox (VID 2e8a) found"
        if (!usb.hasPermission(dev) && !askPermission(dev)) return "USB permission denied"
        // A one-entry probe table, so the generic CDC driver is used whatever the PID.
        val table = ProbeTable().apply { addProduct(dev.vendorId, dev.productId, CdcAcmSerialDriver::class.java) }
        val driver = UsbSerialProber(table).probeDevice(dev) ?: return "no CDC driver matched"
        val conn = usb.openDevice(dev) ?: return "openDevice failed"
        val port = driver.ports[0]
        return try {
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            Thread.sleep(100)
            // HELLO is the pre-0.4 firmware's greeting; *IDN? is 0.4.0's.
            listOf("HELLO", "*IDN?").joinToString("; ") { "$it -> ${exchange(port, it)}" }
        } finally {
            runCatching { port.close() }
        }
    }

    private fun exchange(port: UsbSerialPort, cmd: String): String {
        port.write("$cmd\n".toByteArray(), 500)
        val reply = StringBuilder()
        val buf = ByteArray(256)
        val end = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < end && '\n' !in reply) {
            val n = port.read(buf, 200)
            if (n > 0) reply.append(String(buf, 0, n, Charsets.US_ASCII))
        }
        return reply.toString().trim().ifEmpty { "(no reply)" }
    }

    private fun askPermission(dev: UsbDevice): Boolean {
        val action = "$packageName.USB_PERMISSION"
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(dev, pi)
        latch.await(60, TimeUnit.SECONDS)
        unregisterReceiver(receiver)
        return granted
    }

    // --- UFO202 capture ----------------------------------------------------

    private fun audioCheck(): String {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "RECORD_AUDIO not granted"
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (d in inputs)
            report("AUDIO_IN", "type=${d.type} '${d.productName}' rates=${d.sampleRates.toList()} " +
                "ch=${d.channelCounts.toList()}")
        val usbIn = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: return "no USB audio input"

        // UNPROCESSED promises no gain control or noise suppression, but only
        // where the phone declares support; otherwise the least-processed
        // source left is VOICE_RECOGNITION.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val format = AudioFormat.Builder()
            .setSampleRate(FS)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf * 4, FS * 4))
            .build()
        rec.setPreferredDevice(usbIn)

        val pcm = ByteArray(FS * 4 * SECONDS)
        rec.startRecording()
        var got = 0
        while (got < pcm.size) {
            val n = rec.read(pcm, got, pcm.size - got)
            if (n <= 0) break
            got += n
        }
        val routed = rec.routedDevice
        val rate = rec.sampleRate
        val ch = rec.channelCount
        rec.stop()
        rec.release()

        writeWav(File(filesDir, "spike.wav"), pcm, got, rate, ch)
        val samples = ShortArray(got / 2)
        ByteBuffer.wrap(pcm, 0, got).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val levels = (0 until ch).joinToString(" ") { c -> "ch$c=${level(samples, c, ch)}" }
        return "source=${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION(no UNPROCESSED)"} " +
            "routed='${routed?.productName}' type=${routed?.type} rate=$rate ch=$ch " +
            "seconds=${"%.2f".format(got / (rate * ch * 2.0))} $levels"
    }

    // "peak/rms dBFS" of one channel of interleaved samples.
    private fun level(s: ShortArray, c: Int, ch: Int): String {
        var peak = 0
        var sum = 0.0
        var n = 0
        var i = c
        while (i < s.size) {
            val v = s[i].toInt()
            peak = max(peak, abs(v))
            sum += v.toDouble() * v
            n++
            i += ch
        }
        fun db(x: Double) = 20 * log10(max(x, 1e-9) / 32768)
        return "%.1f/%.1f".format(db(peak.toDouble()), db(sqrt(sum / max(n, 1))))
    }

    private fun writeWav(f: File, pcm: ByteArray, n: Int, rate: Int, ch: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + n).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
        h.putInt(rate).putInt(rate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(n)
        f.outputStream().use { it.write(h.array()); it.write(pcm, 0, n) }
    }
}
