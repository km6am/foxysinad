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
import java.io.IOException
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
private const val CALLSIGN = "KM6AM"     // the Morse ID; the firmware won't transmit without one (or cabled mode)
private const val OPER_ID_BIT = 1 shl 8  // STATus:OPERation bit 8: a Morse ID
private val ERROR_REPLY = Regex("""[+-]?\d+,".*""")  // a SYST:ERR? reply: <code>,"<text>"

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

    // Logcat alone lost a run's lines once, so every line also goes to
    // files/spike.txt (pull it with run-as, like spike.wav).
    private fun report(key: String, value: String) {
        Log.i(TAG, "$key $value")
        File(filesDir, "spike.txt").appendText("$key $value\n")
        runOnUiThread { out.append("$key $value\n") }
    }

    private fun runAll() {
        runOnUiThread { out.text = "" }
        File(filesDir, "spike.txt").writeText("")
        for (d in usb.deviceList.values) {
            val name = runCatching { "${d.manufacturerName} / ${d.productName}" }.getOrDefault("?")
            report("USB", "%04x:%04x %s".format(d.vendorId, d.productId, name))
        }
        // The tone stays on through the capture, so with a radio receiving the
        // PicoFox and its audio into the UFO202 this records the whole chain.
        val fox = runCatching { openFox() }.getOrElse { report("SERIAL", "error: $it"); null }
        try {
            if (fox != null) report("TONE", runCatching { startTone(fox) }.getOrElse { "error: $it" })
            val idBefore = fox?.let { runCatching { it.query("STAT:OPER?") }.getOrNull() }  // reading clears it
            report("AUDIO", runCatching { audioCheck() }.getOrElse { "error: $it" })
            if (fox != null && idBefore != null) {
                val ev = runCatching { fox.query("STAT:OPER?").toInt() }.getOrDefault(0)
                report("ID_OVERLAP", if (ev and OPER_ID_BIT != 0) "yes: a Morse ID started during the capture" else "no")
            }
        } finally {
            if (fox != null) {
                // OUTP OFF sends the ID owed for this transmission first (4-10 s).
                report("OFF", runCatching { fox.write("OUTP OFF"); "output ${fox.query("OUTP?")}" }
                    .getOrElse { "error: $it" })
                fox.close()
            }
        }
        report("DONE", "")
    }

    // --- PicoFox over USB serial -------------------------------------------

    private fun openFox(): Fox? {
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == PICO_VID }
            ?: return null.also { report("SERIAL", "no PicoFox (VID 2e8a) found") }
        if (!usb.hasPermission(dev) && !askPermission(dev))
            return null.also { report("SERIAL", "USB permission denied") }
        // A one-entry probe table, so the generic CDC driver is used whatever the PID.
        val table = ProbeTable().apply { addProduct(dev.vendorId, dev.productId, CdcAcmSerialDriver::class.java) }
        val driver = UsbSerialProber(table).probeDevice(dev)
            ?: return null.also { report("SERIAL", "no CDC driver matched") }
        val conn = usb.openDevice(dev) ?: return null.also { report("SERIAL", "openDevice failed") }
        val port = driver.ports[0]
        port.open(conn)
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.dtr = true
        port.rts = true
        Thread.sleep(100)
        val fox = Fox(port)
        val idn = runCatching { fox.query("*IDN?", timeoutMs = 3000) }.getOrElse { fox.close(); throw it }
        report("SERIAL", idn)
        if (!idn.startsWith("FOXSINAD,")) {
            fox.close()
            return null.also { report("SERIAL", "not foxsinad_fw 0.4.0 or later; flash it with tools/flash.py") }
        }
        return fox
    }

    /** The full setup after connecting (docs/API.md, Startup state), then RF on. */
    private fun startTone(fox: Fox): String {
        fox.write("*CLS")
        fox.write("IDEN:CALL \"$CALLSIGN\"")
        fox.write("FREQ 145.550000MHZ")
        fox.write("POW:ATT 31.75")  // maximum attenuation: attenuator step 127
        fox.write("FM:DEV 3000;:FM:INT:FREQ 1000;:FM:STAT ON")
        fox.write("OUTP ON")
        return "FREQ ${fox.query("FREQ?")} POW:ATT ${fox.query("POW:ATT?")} " +
            "FM ${fox.query("FM:INT:FREQ?")} Hz at ${fox.query("FM:DEV?")} Hz, OUTP ${fox.query("OUTP?")}, " +
            "call ${fox.query("IDEN:CALL?")}"
    }

    /** foxctl's idiom in Kotlin: each message is followed by SYST:ERR? and
     *  *OPC? on lines of their own (a command error skips the rest of its
     *  line), and the "1" from *OPC? after an error reply ends the exchange. */
    private class Fox(private val port: UsbSerialPort) {
        private val pending = StringBuilder()
        private val buf = ByteArray(256)

        fun write(cmd: String) = check(cmd, call(cmd).second)

        fun query(q: String, timeoutMs: Long = 40_000): String {
            val (reply, error) = call(q, timeoutMs)
            check(q, error)
            return reply ?: ""
        }

        fun close() { runCatching { port.close() } }

        private fun check(cmd: String, error: String) {
            if (!error.startsWith("0,")) throw IOException("'$cmd': $error")
        }

        // 40 s by default: a command that waits for, or sends, a Morse ID can take that long.
        private fun call(cmd: String, timeoutMs: Long = 40_000): Pair<String?, String> {
            pending.clear()
            port.write("$cmd\nSYST:ERR?\n*OPC?\n".toByteArray(Charsets.US_ASCII), 1000)
            val deadline = System.currentTimeMillis() + timeoutMs
            val got = mutableListOf<String>()
            while (!(got.size >= 2 && got.last() == "1" && ERROR_REPLY.matches(got[got.size - 2]))) {
                got += readLine(deadline) ?: throw IOException("'$cmd': no reply in ${timeoutMs / 1000} s, got $got")
            }
            if (got.size > 3) throw IOException("'$cmd': unexpected replies $got")
            return (if (got.size == 3) got[0] else null) to got[got.size - 2]
        }

        /** The next non-empty line, or null at the deadline. */
        private fun readLine(deadline: Long): String? {
            while (true) {
                val i = pending.indexOf("\n")
                if (i >= 0) {
                    val line = pending.substring(0, i).trim()
                    pending.delete(0, i + 1)
                    if (line.isNotEmpty()) return line
                    continue
                }
                if (System.currentTimeMillis() > deadline) return null
                val n = port.read(buf, 200)
                if (n > 0) pending.append(String(buf, 0, n, Charsets.US_ASCII))
            }
        }
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
