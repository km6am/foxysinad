package com.km6am.foxysinad

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val PICO_VID = 0x2E8A  // Raspberry Pi, as foxctl's find_port looks for

/** The PicoFox's USB serial port as a byte pipe: the Kotlin half of foxctl's
 *  transport (bench.Link is the Python half). Only bytes cross here; the
 *  protocol stays in foxctl. */
class UsbLink private constructor(private val port: UsbSerialPort) {
    private val pending = ByteArrayOutputStream()
    private val buf = ByteArray(256)

    fun write(data: ByteArray) = port.write(data, 1000)

    /** A line with its '\n', or what arrived within 0.5 s (empty if nothing),
     *  as pyserial's readline() does with a 0.5 s timeout. */
    fun readLine(): ByteArray {
        val deadline = System.currentTimeMillis() + 500
        while (true) {
            val have = pending.toByteArray()
            val nl = have.indexOf('\n'.code.toByte())
            if (nl >= 0) {
                pending.reset()
                pending.write(have, nl + 1, have.size - nl - 1)
                return have.copyOf(nl + 1)
            }
            if (System.currentTimeMillis() >= deadline) {
                pending.reset()
                return have
            }
            val n = port.read(buf, 100)
            if (n > 0) pending.write(buf, 0, n)
        }
    }

    fun resetInput() {
        pending.reset()
        while (port.read(buf, 20) > 0) { /* discard */ }
    }

    fun close() { runCatching { port.close() } }

    companion object {
        /** The PicoFox's port, opened, or null if none is plugged in. Asks the
         *  user for USB permission the first time (blocks up to 60 s). */
        fun open(context: Context): UsbLink? {
            val usb = context.getSystemService(UsbManager::class.java)
            val dev = usb.deviceList.values.firstOrNull { it.vendorId == PICO_VID } ?: return null
            if (!usb.hasPermission(dev) && !askPermission(context, usb, dev))
                throw IOException("USB permission for the PicoFox was denied")
            // A one-entry probe table, so the generic CDC driver is used whatever the PID.
            val table = ProbeTable().apply { addProduct(dev.vendorId, dev.productId, CdcAcmSerialDriver::class.java) }
            val driver = UsbSerialProber(table).probeDevice(dev) ?: throw IOException("no CDC driver for the PicoFox")
            val conn = usb.openDevice(dev) ?: throw IOException("could not open the PicoFox")
            val port = driver.ports[0]
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            return UsbLink(port)
        }

        private fun askPermission(context: Context, usb: UsbManager, dev: UsbDevice): Boolean {
            val action = "${context.packageName}.USB_PERMISSION"
            val latch = CountDownLatch(1)
            var granted = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
            context.registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
            latch.await(60, TimeUnit.SECONDS)
            context.unregisterReceiver(receiver)
            return granted
        }
    }
}
