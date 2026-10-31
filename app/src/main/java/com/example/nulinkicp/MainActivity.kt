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
    private lateinit var targetSpinner: Spinner
    private lateinit var hexView: TextView
    private var selectedHexImage: HexImage? = null
    private var selectedHexName: String = ""
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var selectedHex: Uri? = null
    private var bulkInEndpoint: UsbEndpoint? = null
    private var bulkOutEndpoint: UsbEndpoint? = null
    @Volatile private var detectRunning = false
    private lateinit var detectButton: Button
    private var lastDiagnosticReport: String = ""
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
        root.addView(TextView(this).apply {
            text = "TARGET MCU"
            textSize = 13f
            setPadding(0, 8, 0, 2)
        })
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("MS51FB9AE — 16 KB APROM", "MS51PC0AE — 32 KB APROM"))
            setSelection(1)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    selectedHexImage?.let { showHexValidation(it, selectedHexName) }
                    if (::detectButton.isInitialized) {
                        detectButton.text = if (position == 1) "RUN MS51PC0AE DETECTION DIAGNOSTIC" else "DETECTION NOT AVAILABLE FOR MS51FB9AE"
                    }
                }
            }
        }
        root.addView(targetSpinner)
        hexView = TextView(this).apply {
            text = "HEX: no file selected"
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }
        root.addView(hexView)
        root.addView(Button(this).apply {
            text = "DETECT PROGRAMMER"
            setOnClickListener { findNuLink() }
        })
        root.addView(Button(this).apply {
            text = "SELECT INTEL HEX"
            setOnClickListener { pickHex() }
        })
        detectButton = Button(this).apply {
            text = "RUN MS51PC0AE DETECTION DIAGNOSTIC"
            isEnabled = false
            setOnClickListener { runCapturedAutoDetect() }
        }
        root.addView(detectButton)
        root.addView(Button(this).apply {
            text = "BUILD APROM TRANSFER MANIFEST"
            setOnClickListener {
                val image = selectedHexImage
                if (image == null) {
                    Toast.makeText(this@MainActivity, "Select a valid HEX file first.", Toast.LENGTH_SHORT).show()
                } else if (image.maxAddress >= selectedTargetCapacity()) {
                    Toast.makeText(this@MainActivity, "HEX exceeds selected APROM capacity.", Toast.LENGTH_LONG).show()
                } else {
                    val manifest = buildTransferManifest(image)
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Nu-Link APROM transfer manifest", manifest))
                    log("APROM manifest generated locally; no USB programming packets were sent.")
                    Toast.makeText(this@MainActivity, "Manifest copied to clipboard.", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "COPY FULL DIAGNOSTIC REPORT"
            setOnClickListener {
                if (lastDiagnosticReport.isBlank()) {
                    Toast.makeText(this@MainActivity, "Run AUTO DETECT first.", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Nu-Link diagnostic report", lastDiagnosticReport))
                    Toast.makeText(this@MainActivity, "Diagnostic report copied.", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "ONLINE PROGRAM (protocol not validated)"
            isEnabled = false
        })
        root.addView(Button(this).apply {
            text = "OFFLINE TRANSFER (protocol not validated)"
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
        log("USB transport ready. No detection command has been sent yet.")
        log("Tap AUTO DETECT MCU to run the captured MS51PC0AE identification sequence.")
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
        if (targetSpinner.selectedItemPosition != 1) {
            log("AUTO DETECT diagnostic is currently captured only for MS51PC0AE. No command sent.")
            Toast.makeText(this, "Detection reference is available for MS51PC0AE only.", Toast.LENGTH_LONG).show()
            return
        }
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
                val mismatchTransactions = ArrayList<Int>()
                val mismatchDetails = ArrayList<String>()
                var totalDifferingBytes = 0
                val startedAt = System.currentTimeMillis()
                for (i in 0 until txCount) {
                    val tx = txBytes.copyOfRange(i * 64, (i + 1) * 64)
                    val expected = refBytes.copyOfRange(i * 64, (i + 1) * 64)
                    val sent = conn.bulkTransfer(epOut, tx, 64, 2000)
                    if (sent != 64) error("USB OUT failed at transaction ${i + 1}: sent=$sent/64 bytes")
                    val rx = ByteArray(64)
                    val received = conn.bulkTransfer(epIn, rx, 64, 2500)
                    if (received != 64) error("USB IN timeout/short response at transaction ${i + 1}: received=$received/64 bytes")
                    if (rx[0] == expected[0]) sequenceMatches++
                    if (rx.contentEquals(expected)) {
                        exactMatches++
                    } else {
                        val diffs = (0 until 64).filter { rx[it] != expected[it] }
                        mismatchTransactions.add(i + 1)
                        totalDifferingBytes += diffs.size
                        val detail = buildString {
                            appendLine("--- MISMATCH TRANSACTION ${i + 1} ---")
                            appendLine("OUT request: ${formatBytes(tx)}")
                            appendLine("Expected IN: ${formatBytes(expected)}")
                            appendLine("Actual IN:   ${formatBytes(rx)}")
                            appendLine("Differing offsets: ${diffs.joinToString(", ").ifEmpty { "none" }}")
                            appendLine("Byte differences: ${diffs.joinToString("; ") { offset ->
                                "%02d: expected=%02X actual=%02X".format(Locale.US, offset,
                                    expected[offset].toInt() and 0xFF, rx[offset].toInt() and 0xFF)
                            }}")
                        }
                        mismatchDetails.add(detail)
                    }
                    if (i % 25 == 0 || i == txCount - 1) {
                        val done = i + 1
                        runOnUiThread { statusView.text = "Auto Detect: $done / $txCount transactions" }
                    }
                }
                val elapsedMs = System.currentTimeMillis() - startedAt
                val fullReport = buildString {
                    appendLine("NU-LINK MS51PC0AE CAPTURE COMPARISON — DIAGNOSTIC v0.6")
                    appendLine("Requests sent: $txCount")
                    appendLine("Exact response matches: $exactMatches / $txCount")
                    appendLine("Sequence-byte matches: $sequenceMatches / $txCount")
                    appendLine("Mismatching transactions: ${mismatchTransactions.size}")
                    appendLine("Total differing response bytes: $totalDifferingBytes")
                    appendLine("Elapsed: ${elapsedMs} ms")
                    appendLine("Mismatch transaction numbers: ${mismatchTransactions.joinToString(", ").ifEmpty { "none" }}")
                    appendLine()
                    mismatchDetails.forEach { appendLine(it) }
                    appendLine("No erase, flash-program, configuration-bit, or lock-bit command is implemented.")
                }
                runOnUiThread {
                    lastDiagnosticReport = fullReport
                    log("AUTO DETECT DIAGNOSTICS v0.6: requests=$txCount, exact=$exactMatches, sequence-byte=$sequenceMatches.")
                    log("Mismatching transactions (${mismatchTransactions.size}): ${mismatchTransactions.joinToString(", ").ifEmpty { "none" }}")
                    log("Total differing response bytes: $totalDifferingBytes; elapsed=${elapsedMs}ms.")
                    if (mismatchDetails.isEmpty()) {
                        log("All live responses exactly match the reference capture.")
                    } else {
                        log("Full request/expected/actual response and byte-difference detail follows:")
                        mismatchDetails.forEach { detail -> detail.lineSequence().forEach { line -> log(line) } }
                    }
                    if (exactMatches == txCount) {
                        mcuView.text = "MCU: MS51PC0AE — capture reference matched"
                        statusView.text = "Auto Detect exchange completed"
                        log("Result: MS51PC0AE (all captured responses matched exactly).")
                    } else {
                        mcuView.text = "MCU: not confirmed"
                        statusView.text = "Auto Detect completed; response differs"
                        log("MCU was NOT identified: live responses differ from the MS51PC0AE reference.")
                    }
                    log("No erase, flash-program, configuration-bit, or lock-bit command is implemented.")
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
            val name = queryDisplayName(uri) ?: "Selected Intel HEX"
            val image = IntelHex.parse(content.toString(Charsets.US_ASCII))
            selectedHexImage = image
            selectedHexName = name
            showHexValidation(image, name)
            log("HEX VALID: ${image.bytes.size} data bytes, ${image.dataRecords} data records.")
            log("Address range: 0x${image.minAddress.toString(16).uppercase(Locale.US)} .. 0x${image.maxAddress.toString(16).uppercase(Locale.US)}")
            log("Target capacity check: ${if (image.maxAddress < selectedTargetCapacity()) "PASS" else "FAIL"}.")
            log("HEX parsed and validated locally; no programming command sent.")
        } catch (e: Exception) {
            selectedHexImage = null
            hexView.text = "HEX INVALID: ${e.message ?: e.javaClass.simpleName}"
            log("HEX ERROR: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun selectedTargetName(): String = if (targetSpinner.selectedItemPosition == 0) "MS51FB9AE" else "MS51PC0AE"

    private fun selectedTargetCapacity(): Long = if (targetSpinner.selectedItemPosition == 0) 16L * 1024L else 32L * 1024L

    private fun showHexValidation(image: HexImage, name: String) {
        val capacity = selectedTargetCapacity()
        val fits = image.maxAddress < capacity
        hexView.text = buildString {
            append("HEX: $name\n")
            append("Data: ${image.bytes.size} bytes | Records: ${image.dataRecords}\n")
            append("Range: 0x${image.minAddress.toString(16).uppercase(Locale.US)}–0x${image.maxAddress.toString(16).uppercase(Locale.US)}\n")
            append("Target: ${selectedTargetName()} (${capacity / 1024} KB APROM)\n")
            append(if (fits) "Address capacity: PASS" else "Address capacity: FAIL — HEX exceeds APROM")
        }
        log("HEX target check for ${selectedTargetName()}: ${if (fits) "PASS" else "FAIL"}.")
    }

    private fun buildTransferManifest(image: HexImage): String {
        val entries = image.bytes.toSortedMap().entries.toList()
        val ranges = ArrayList<Pair<Long, Long>>()
        var start = entries.first().key
        var previous = start
        for (i in 1 until entries.size) {
            val address = entries[i].key
            if (address != previous + 1L) {
                ranges.add(start to previous)
                start = address
            }
            previous = address
        }
        ranges.add(start to previous)
        val blocks = (image.bytes.size + 31) / 32
        return buildString {
            appendLine("NU-LINK APROM TRANSFER MANIFEST — LOCAL ANALYSIS ONLY")
            appendLine("Target: ${selectedTargetName()}")
            appendLine("HEX file: $selectedHexName")
            appendLine("Data bytes: ${image.bytes.size}")
            appendLine("Data records: ${image.dataRecords}")
            appendLine("Address range: 0x${image.minAddress.toString(16).uppercase(Locale.US)}..0x${image.maxAddress.toString(16).uppercase(Locale.US)}")
            appendLine("32-byte data chunks (packed by populated bytes, not protocol packets): $blocks")
            appendLine("Contiguous address ranges: ${ranges.size}")
            ranges.forEachIndexed { index, range ->
                appendLine("${index + 1}. 0x${range.first.toString(16).uppercase(Locale.US)}..0x${range.second.toString(16).uppercase(Locale.US)} (${range.second - range.first + 1} bytes)")
            }
            appendLine()
            appendLine("SAFETY: This manifest does not encode Nu-Link USB commands and does not send data to the programmer.")
            appendLine("APROM erase/program/verify remains disabled until the command framing and acknowledgements are validated.")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) { null }
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

    private fun formatBytes(bytes: ByteArray): String = bytes.joinToString(" ") {
        "%02X".format(Locale.US, it.toInt() and 0xFF)
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

data class HexImage(
    val bytes: Map<Long, Int>,
    val dataRecords: Int,
    val minAddress: Long,
    val maxAddress: Long,
    val startAddress: Long?
)

object IntelHex {
    fun parse(text: String): HexImage {
        val memory = sortedMapOf<Long, Int>()
        var upper = 0L
        var dataRecords = 0
        var eofSeen = false
        var startAddress: Long? = null
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        lines.forEachIndexed { index, line ->
            require(!eofSeen) { "Data found after EOF record (line ${index + 1})" }
            require(line.startsWith(":")) { "Line ${index + 1}: record must start with ':'" }
            val raw = line.substring(1)
            require(raw.length >= 10 && raw.length % 2 == 0) { "Line ${index + 1}: invalid record length" }
            val record = try { raw.chunked(2).map { it.toInt(16) } }
                catch (_: NumberFormatException) { error("Line ${index + 1}: non-hexadecimal character") }
            val length = record[0]
            require(record.size == length + 5) { "Line ${index + 1}: byte count mismatch" }
            require((record.sum() and 0xFF) == 0) { "Line ${index + 1}: checksum error" }
            val address = (record[1] shl 8) or record[2]
            val type = record[3]
            when (type) {
                0x00 -> {
                    val absolute = upper + address
                    require(absolute + length <= 0x1_0000_0000L) { "Line ${index + 1}: address overflow" }
                    for (i in 0 until length) {
                        val at = absolute + i
                        val value = record[4 + i]
                        val previous = memory[at]
                        require(previous == null || previous == value) { "Line ${index + 1}: conflicting overlapping data at 0x${at.toString(16)}" }
                        memory[at] = value
                    }
                    if (length > 0) dataRecords++
                }
                0x01 -> {
                    require(length == 0 && address == 0) { "Line ${index + 1}: malformed EOF record" }
                    eofSeen = true
                }
                0x02 -> {
                    require(length == 2) { "Line ${index + 1}: invalid extended segment address" }
                    upper = (((record[4] shl 8) or record[5]).toLong() shl 4)
                }
                0x04 -> {
                    require(length == 2) { "Line ${index + 1}: invalid extended linear address" }
                    upper = (((record[4] shl 8) or record[5]).toLong() shl 16)
                }
                0x03 -> {
                    require(length == 4) { "Line ${index + 1}: invalid start segment address" }
                    startAddress = (((record[4] shl 8) or record[5]).toLong() shl 4) + ((record[6] shl 8) or record[7])
                }
                0x05 -> {
                    require(length == 4) { "Line ${index + 1}: invalid start linear address" }
                    startAddress = ((record[4].toLong() shl 24) or (record[5].toLong() shl 16) or (record[6].toLong() shl 8) or record[7].toLong())
                }
                else -> error("Line ${index + 1}: unsupported record type 0x${type.toString(16)}")
            }
        }
        require(eofSeen) { "Missing EOF record" }
        require(memory.isNotEmpty()) { "HEX contains no data records" }
        return HexImage(memory, dataRecords, memory.firstKey(), memory.lastKey(), startAddress)
    }
}
