package com.example.nulinkicp

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var mcuView: TextView
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var selectedHex: Uri? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    @Volatile private var detectRunning = false
    private lateinit var detectButton: Button
    private val permissionAction = "com.example.nulinkicp.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = usbDeviceExtra(intent)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                inspectAndOpen(device)
            } else {
                statusView.text = "Programmer: USB permission denied"
                log("USB permission was denied by Android.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        findNuLink()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Nu-Link ICP Programmer"
            textSize = 24f
        })
        statusView = TextView(this).apply {
            text = "Programmer: searching"
            textSize = 17f
            setPadding(0, 12, 0, 4)
        }
        mcuView = TextView(this).apply {
            text = "MCU: not identified yet"
            textSize = 16f
            setPadding(0, 4, 0, 12)
        }
        root.addView(statusView)
        root.addView(mcuView)
        root.addView(Button(this).apply {
            text = "DETECT PROGRAMMER"
            setOnClickListener { findNuLink() }
        })
        root.addView(Button(this).apply {
            text = "SELECT INTEL HEX"
            setOnClickListener { pickHex() }
        })
        detectButton = Button(this).apply {
            text = "AUTO DETECT MCU (MS51PC0AE)"
            isEnabled = false
            setOnClickListener { runCapturedAutoDetect() }
        }
        root.addView(detectButton)
        root.addView(Button(this).apply {
            text = "ONLINE PROGRAM (not enabled)"
            isEnabled = false
        })
        root.addView(Button(this).apply {
            text = "OFFLINE TRANSFER (not enabled)"
            isEnabled = false
        })
        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 0)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun findNuLink() {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == NUVOTON_VID && it.productId == NUVOTON_PID
        }
        if (device == null) {
            statusView.text = "Programmer: not found"
            mcuView.text = "MCU: not identified"
            log("No USB device with VID=0416 PID=511C found.")
            log("Connect the Nu-Link clone through USB-OTG, then tap DETECT PROGRAMMER.")
            closeUsb()
            return
        }
        log("Found ${device.deviceName}, VID=${hex4(device.vendorId)}, PID=${hex4(device.productId)}")
        log("USB device: ${device.manufacturerName ?: "manufacturer unavailable"} / ${device.productName ?: "product unavailable"}")
        log("Device version: ${device.version}; interfaces=${device.interfaceCount}")
        dumpInterfaces(device)
        if (usbManager.hasPermission(device)) {
            inspectAndOpen(device)
        } else {
            try {
                val piIntent = Intent(permissionAction).setPackage(packageName)
                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val pi = PendingIntent.getBroadcast(this, 0, piIntent, flags)
                usbManager.requestPermission(device, pi)
                statusView.text = "Programmer: waiting for USB permission"
                log("Requested Android USB permission.")
            } catch (e: Exception) {
                statusView.text = "Programmer: permission request failed"
                log("Permission request error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun dumpInterfaces(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            log("Interface ${intf.id}: class=${hex2(intf.interfaceClass)}, subclass=${hex2(intf.interfaceSubclass)}, protocol=${hex2(intf.interfaceProtocol)}, endpoints=${intf.endpointCount}")
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
                    else -> "UNKNOWN"
                }
                log("  EP ${hex2(ep.address)} $dir $type maxPacket=${ep.maxPacketSize}")
            }
        }
    }

    private fun inspectAndOpen(device: UsbDevice) {
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == BULK_INTERFACE_NUMBER }
        if (intf == null) {
            statusView.text = "Programmer found; interface 1 missing"
            log("Expected bulk interface 1 is not available.")
            return
        }
        val input = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.address == BULK_IN_ADDRESS && it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val output = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.address == BULK_OUT_ADDRESS && it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        if (input == null || output == null) {
            statusView.text = "Programmer found; bulk endpoints missing"
            log("Required bulk endpoints 0x83 IN and 0x04 OUT were not found.")
            return
        }
        closeUsb()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            statusView.text = "Programmer found; open failed"
            log("UsbManager.openDevice returned null.")
            return
        }
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            statusView.text = "Programmer found; claim failed"
            log("Could not claim interface 1. Close Windows ICP or other apps using the programmer.")
            return
        }
        connection = conn
        claimedInterface = intf
        bulkInEndpoint = input
        bulkOutEndpoint = output
        detectButton.isEnabled = true
        statusView.text = "Programmer: CONNECTED"
        mcuView.text = "MCU: not identified — no ICP command sent"
        log("USB interface 1 successfully claimed.")
        log("Bulk IN=${hex2(input.address)}, OUT=${hex2(output.address)}, maxPacket=${input.maxPacketSize}.")
        log("Transport check only. No USB OUT command has been transmitted.")
        log("MCU identification is intentionally not enabled until command framing is validated.")
    }


    /*
     * Capture-derived replay of the user's Windows ICP Auto Detect session
     * for MS51PC0AE. This is a narrowly scoped reference test, not a general
     * Nu-Link protocol implementation.
     */
    private fun runCapturedAutoDetect() {
        val conn = connection
        val epOut = bulkOutEndpoint
        val epIn = bulkInEndpoint
        if (conn == null || epOut == null || epIn == null) {
            log("AUTO DETECT: connect the programmer first.")
            return
        }
        if (detectRunning) return
        detectRunning = true
        detectButton.isEnabled = false
        mcuView.text = "MCU: identification in progress"
        statusView.text = "Programmer: running captured Auto Detect"
        Thread {
            try {
                val txBytes = assets.open("ms51pc0ae_detect_out.bin").use { it.readBytes() }
                val refBytes = assets.open("ms51pc0ae_detect_in_reference.bin").use { it.readBytes() }
                require(txBytes.size % 64 == 0 && refBytes.size % 64 == 0) {
                    "Invalid bundled capture data"
                }
                val txCount = txBytes.size / 64
                val refCount = refBytes.size / 64
                require(txCount == refCount && txCount > 0) {
                    "Request/response reference count mismatch"
                }
                var exactMatches = 0
                var sequenceMatches = 0
                var firstMismatch = -1
                for (i in 0 until txCount) {
                    val tx = txBytes.copyOfRange(i * 64, (i + 1) * 64)
                    val expected = refBytes.copyOfRange(i * 64, (i + 1) * 64)
                    val sent = conn.bulkTransfer(epOut, tx, 64, 2000)
                    if (sent != 64) error("USB OUT failed at transaction ${i + 1}: $sent bytes")
                    val rx = ByteArray(64)
                    val received = conn.bulkTransfer(epIn, rx, 64, 2500)
                    if (received != 64) error("USB IN timeout/short response at transaction ${i + 1}: $received bytes")
                    if (rx[0] == expected[0]) sequenceMatches++
                    if (rx.contentEquals(expected)) exactMatches++
                    else if (firstMismatch < 0) firstMismatch = i
                    if (i % 25 == 0 || i == txCount - 1) {
                        val done = i + 1
                        runOnUiThread { statusView.text = "Auto Detect: $done / $txCount transactions" }
                    }
                }
                runOnUiThread {
                    if (exactMatches == txCount) {
                        mcuView.text = "MCU: MS51PC0AE — capture reference matched"
                        statusView.text = "Auto Detect exchange completed"
                        log("AUTO DETECT: all $txCount responses exactly match the Windows MS51PC0AE reference.")
                        log("Result: MS51PC0AE (capture-reference match).")
                    } else {
                        mcuView.text = "MCU: not confirmed"
                        statusView.text = "Auto Detect completed; response differs"
                        log("AUTO DETECT: $exactMatches / $txCount exact response matches; $sequenceMatches sequence-byte matches.")
                        log("First differing response: transaction ${firstMismatch + 1}.")
                        log("MCU was NOT identified because the response did not fully match the MS51PC0AE reference.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    mcuView.text = "MCU: not identified"
                    statusView.text = "Auto Detect failed"
                    log("AUTO DETECT ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    log("No erase or flash-program command is implemented in this build.")
                }
            } finally {
                detectRunning = false
                runOnUiThread { detectButton.isEnabled = connection != null }
            }
        }.start()
    }

    private fun pickHex() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "text/plain", "application/hex"))
        }
        startActivityForResult(intent, REQUEST_HEX)
    }

    @Deprecated("Use Activity Result APIs in production")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HEX || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedHex = uri
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        try {
            val content = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot open selected file")
            val summary = IntelHex.parse(content.toString(Charsets.US_ASCII))
            log("HEX VALID: ${summary.bytes} data bytes, ${summary.records} data records.")
            log("Address range: 0x${summary.minAddress.toString(16).uppercase(Locale.US)} .. 0x${summary.maxAddress.toString(16).uppercase(Locale.US)}")
            log("HEX validation only; no programming command sent.")
        } catch (e: Exception) {
            log("HEX ERROR: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun closeUsb() {
        try { claimedInterface?.let { connection?.releaseInterface(it) } } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        claimedInterface = null
        connection = null
        bulkInEndpoint = null
        bulkOutEndpoint = null
        if (::detectButton.isInitialized) detectButton.isEnabled = false
    }

    private fun log(message: String) {
        logView.append(message + "\n")
    }

    private fun hex2(v: Int) = String.format(Locale.US, "0x%02X", v and 0xFF)
    private fun hex4(v: Int) = String.format(Locale.US, "%04X", v and 0xFFFF)

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        closeUsb()
        super.onDestroy()
    }

    companion object {
        private const val NUVOTON_VID = 0x0416
        private const val NUVOTON_PID = 0x511C
        private const val BULK_INTERFACE_NUMBER = 1
        private const val BULK_IN_ADDRESS = 0x83
        private const val BULK_OUT_ADDRESS = 0x04
        private const val REQUEST_HEX = 1001
    }
}

data class HexSummary(val bytes: Int, val records: Int, val minAddress: Long, val maxAddress: Long)

object IntelHex {
    fun parse(text: String): HexSummary {
        var upper = 0L
        var count = 0
        var records = 0
        var min = Long.MAX_VALUE
        var max = 0L
        var eof = false
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            require(line.startsWith(":")) { "Invalid Intel HEX record" }
            val raw = line.substring(1)
            require(raw.length % 2 == 0) { "Odd number of HEX characters" }
            val b = raw.chunked(2).map { it.toInt(16) }
            require(b.size >= 5) { "Record too short" }
            val len = b[0]
            require(b.size == len + 5) { "Record length mismatch" }
            require((b.sum() and 0xFF) == 0) { "Checksum error" }
            val addr = (b[1] shl 8) or b[2]
            when (b[3]) {
                0x00 -> if (len > 0) {
                    val absolute = upper + addr
                    min = minOf(min, absolute)
                    max = maxOf(max, absolute + len - 1)
                    count += len
                    records++
                }
                0x01 -> eof = true
                0x02 -> {
                    require(len == 2) { "Invalid extended segment address record" }
                    upper = (((b[4] shl 8) or b[5]).toLong() shl 4)
                }
                0x04 -> {
                    require(len == 2) { "Invalid extended linear address record" }
                    upper = (((b[4] shl 8) or b[5]).toLong() shl 16)
                }
                0x03, 0x05 -> Unit
                else -> error("Unsupported Intel HEX record type ${b[3]}")
            }
        }
        require(eof) { "Missing EOF record" }
        require(count > 0) { "HEX contains no data records" }
        return HexSummary(count, records, min, max)
    }
}
