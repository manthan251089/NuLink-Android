package com.example.nulinkicp

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.widget.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private var selectedHex: Uri? = null
    private var connection: UsbDeviceConnection? = null
    private var bulkInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    private val permissionAction = "com.example.nulinkicp.USB_PERMISSION"
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    inspectAndOpen(device)
                } else log("USB permission denied.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        buildUi()
        registerReceiver(permissionReceiver, IntentFilter(permissionAction), RECEIVER_NOT_EXPORTED)
        findNuLink()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val title = TextView(this).apply { text = "Nu-Link ICP Programmer"; textSize = 24f }
        statusView = TextView(this).apply { text = "USB: searching"; textSize = 16f }
        val connect = Button(this).apply { text = "Detect / Connect Nu-Link"; setOnClickListener { findNuLink() } }
        val browse = Button(this).apply { text = "Select Intel HEX"; setOnClickListener { pickHex() } }
        val online = Button(this).apply {
            text = "Online programming (not enabled yet)"
            isEnabled = false
        }
        val offline = Button(this).apply {
            text = "Offline transfer (not enabled yet)"
            isEnabled = false
        }
        logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(title); root.addView(statusView); root.addView(connect); root.addView(browse)
        root.addView(online); root.addView(offline)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun findNuLink() {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == NUVOTON_VID && it.productId == NUVOTON_PID
        }
        if (device == null) {
            statusView.text = "USB: Nu-Link not found"
            log("Connect the Nu-Link clone using a USB OTG adapter.")
            return
        }
        log("Found ${device.deviceName}, VID=${device.vendorId.toString(16)}, PID=${device.productId.toString(16)}")
        if (usbManager.hasPermission(device)) inspectAndOpen(device) else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction), PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    private fun inspectAndOpen(device: UsbDevice) {
        // Descriptors from user's USBTreeView capture: interface 0 HID, interface 1 CDC-Data.
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == BULK_INTERFACE_NUMBER }
        if (intf == null) {
            log("Expected interface 1 was not exposed by Android. Interfaces=${device.interfaceCount}")
            return
        }
        val input = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.address == BULK_IN_ADDRESS && it.direction == UsbConstants.USB_DIR_IN }
        val output = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.address == BULK_OUT_ADDRESS && it.direction == UsbConstants.USB_DIR_OUT }
        if (input == null || output == null) {
            log("Bulk endpoints 0x83/0x04 not found on interface 1.")
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null || !conn.claimInterface(intf, true)) {
            log("Could not open/claim interface 1. Close Windows ICP before connecting.")
            return
        }
        connection?.close()
        connection = conn
        bulkInterface = intf
        bulkIn = input
        bulkOut = output
        statusView.text = "USB: connected (protocol not initialized)"
        log("Claimed interface 1; IN=0x83 BULK, OUT=0x04 BULK, max packet=${input.maxPacketSize}.")
        log("No Nu-Link command has been sent. Protocol implementation awaits validated command framing.")
    }

    private fun pickHex() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "text/plain", "application/hex"))
        }
        startActivityForResult(i, REQUEST_HEX)
    }

    @Deprecated("Use Activity Result APIs in production")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_HEX && resultCode == RESULT_OK) {
            selectedHex = data?.data
            selectedHex?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val parsed = IntelHex.parse(bytes.toString(Charsets.US_ASCII))
                    log("HEX loaded: ${parsed.size} data bytes across ${parsed.records} data records.")
                    log("Address range: 0x${parsed.minAddress.toString(16)}..0x${parsed.maxAddress.toString(16)}")
                } catch (e: Exception) {
                    log("HEX error: ${e.message}")
                }
            }
        }
    }

    private fun log(s: String) { logView.append(s + "\n") }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        bulkInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        super.onDestroy()
    }

    companion object {
        const val NUVOTON_VID = 0x0416
        const val NUVOTON_PID = 0x511C
        const val BULK_INTERFACE_NUMBER = 1
        const val BULK_IN_ADDRESS = 0x83
        const val BULK_OUT_ADDRESS = 0x04
        const val REQUEST_HEX = 1001
    }
}

data class HexSummary(val size: Int, val records: Int, val minAddress: Long, val maxAddress: Long)

object IntelHex {
    fun parse(text: String): HexSummary {
        var upper = 0L
        var count = 0
        var min = Long.MAX_VALUE
        var max = 0L
        var eof = false
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            require(line.startsWith(":")) { "Invalid Intel HEX record" }
            val b = line.substring(1).chunked(2).map { it.toInt(16) }
            require(b.size >= 5) { "Record too short" }
            val len = b[0]
            require(b.size == len + 5) { "Record length mismatch" }
            require((b.sum() and 0xFF) == 0) { "Checksum error" }
            val addr = (b[1] shl 8) or b[2]
            when (b[3]) {
                0x00 -> {
                    if (len > 0) {
                        val absolute = upper + addr
                        min = minOf(min, absolute.toLong())
                        max = maxOf(max, absolute.toLong() + len - 1)
                        count += len
                    }
                }
                0x01 -> eof = true
                0x02 -> upper = ((b[4] shl 8) or b[5]).toLong() shl 4
                0x04 -> upper = (((b[4] shl 8) or b[5]).toLong() shl 16)
                0x03, 0x05 -> Unit
                else -> error("Unsupported Intel HEX record type ${b[3]}")
            }
        }
        require(eof) { "Missing EOF record" }
        require(count > 0) { "HEX contains no data records" }
        return HexSummary(count, text.lineSequence().count { it.trim().startsWith(":") && it.trim().getOrNull(7) == '0' },
            min, max)
    }
}
