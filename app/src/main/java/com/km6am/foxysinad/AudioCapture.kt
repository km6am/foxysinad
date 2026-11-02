package com.km6am.foxysinad

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.chaquo.python.PyObject
import kotlin.concurrent.thread
import kotlin.math.max

private const val TAG = "FoxySinad"
private const val FS = 48000
private const val CHANNELS = 2
private const val BLOCK_FRAMES = FS / 4  // 0.25 s, the analyzer's window

/** The USB sound card's audio, fed to the engine's analyzer in 0.25 s
 *  blocks while a mode is measuring. */
class AudioCapture(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var running: BooleanArray? = null   // one flag per recording, so a stop never ends the next one

    /** Starts recording into analyzer.feed(pcm, channels); says what it opened. */
    @Synchronized
    fun start(analyzer: PyObject): String {
        stop()
        val usbIn = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: throw IllegalStateException("no USB audio input: plug the sound card into the hub")
        // UNPROCESSED only where the phone declares it; otherwise the
        // least-processed source left is VOICE_RECOGNITION.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val minBuf = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
                            else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(FS)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minBuf * 4, BLOCK_FRAMES * CHANNELS * 2 * 4))
            .build()
        rec.setPreferredDevice(usbIn)
        rec.startRecording()
        val flag = booleanArrayOf(true).also { running = it }
        thread(name = "AudioCapture") {
            val block = ByteArray(BLOCK_FRAMES * CHANNELS * 2)
            var blocks = 0
            var why: String? = null
            try {
                read@ while (flag[0]) {
                    var got = 0
                    while (got < block.size && flag[0]) {
                        val n = rec.read(block, got, block.size - got)
                        if (n <= 0) { why = "AudioRecord.read returned $n"; break@read }
                        got += n
                    }
                    if (got < block.size) continue
                    if (blocks == 0) Log.i(TAG, "first audio block read")
                    analyzer.callAttr("feed", block, CHANNELS)
                    if (blocks++ == 0) Log.i(TAG, "first audio block fed")
                }
            } catch (e: Exception) {
                why = "$e"
            } finally {
                rec.stop()
                rec.release()
            }
            // Only an unasked-for end is news; the analyzer logs it if a mode is still reading.
            if (why != null) {
                Log.w(TAG, "audio capture ended after $blocks blocks: $why")
                runCatching { analyzer.callAttr("ended", "$why (after $blocks blocks)") }
            }
        }
        return "audio: ${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"} from " +
            "'${rec.routedDevice?.productName ?: usbIn.productName}', ${rec.sampleRate} Hz, ${rec.channelCount} ch"
    }

    /** Ends the recording without waiting for it: its thread may be inside feed(). */
    @Synchronized
    fun stop() {
        running?.set(0, false)
        running = null
    }
}
