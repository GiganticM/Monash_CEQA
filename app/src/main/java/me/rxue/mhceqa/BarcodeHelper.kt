package me.rxue.mhceqa

import android.content.Intent
import android.content.IntentFilter

object BarcodeHelper {

    val SCAN_ACTIONS = listOf(
        "scan.rcv.message",
        "android.bld.scan.action.RESULT_BROADCAST",
        "com.action.SCAN_RESULT",
        "com.scansku.barcode",
        "nlscan.action.SCANER_RESULT",
        "android.intent.ACTION_DECODE_DATA",
        "com.android.server.scannerservice.broadcast"
    )

    private val PARENTHESIZED_AI21_REGEX = Regex("""\(21\)([^\(\u001D\r\n]+)""")
    private val SYMBOLOGY_PREFIX_REGEX = Regex("""^\](?:d2|C1|e0|Q3|d1)""")
    private val GENIUS3_FALLBACK_REGEX = Regex("""[NX]\d{8}$""")

    data class DecodedBarcode(
        val code: String,
        val extractedSerial: String? = null
    )

    fun createBarcodeIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            SCAN_ACTIONS.forEach { addAction(it) }
        }
    }

    fun extractBarcodeFromIntent(intent: Intent): String? {
        val candidates = listOf(
            "barcode_string",
            "barcodeData",
            "barcode",
            "barocode",
            "data",
            "scanContext",
            "value",
            "scannerdata",
            "EXTRA_BARCODE_DATA",
            "scan_result"
        )
        for (key in candidates) {
            val str = intent.getStringExtra(key)
            if (!str.isNullOrBlank()) return str.trim()
        }
        val byteData = intent.getByteArrayExtra("barcodeByteData")
            ?: intent.getByteArrayExtra("barcodebyte")
            ?: intent.getByteArrayExtra("data")
        if (byteData != null && byteData.isNotEmpty()) {
            val str = String(byteData).trim()
            if (str.isNotBlank()) return str
        }
        intent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                val obj = bundle.get(key)
                if (obj is String && obj.isNotBlank()) {
                    return obj.trim()
                }
            }
        }
        return null
    }

    /**
     * Decodes a raw barcode / DataMatrix string.
     * If it is a GS1 barcode containing AI (21) Serial Number (such as a Genius 3 thermometer
     * or medical device UDI DataMatrix), extracts the (21) Serial Number as the primary code.
     */
    fun decodeBarcode(raw: String): DecodedBarcode {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DecodedBarcode("")

        // Strip optional AIM symbology identifier (e.g. ]d2, ]C1) and leading FNC1 (ASCII 29)
        val clean = trimmed.replaceFirst(SYMBOLOGY_PREFIX_REGEX, "").trimStart('\u001D').trim()

        // 1. Check parenthesized GS1 format: e.g. (01)...(21)SN12345
        val parenMatch = PARENTHESIZED_AI21_REGEX.find(clean)
        if (parenMatch != null) {
            val sn = parenMatch.groupValues[1].trim()
            if (sn.isNotEmpty()) {
                return DecodedBarcode(code = sn.uppercase(), extractedSerial = sn.uppercase())
            }
        }

        // 2. Walk standard unbracketed GS1 Application Identifiers (01 GTIN, 11/13/15/17 Dates, 10 Lot, 21 Serial)
        val gs1Serial = extractGs1Ai21(clean)
        if (!gs1Serial.isNullOrBlank()) {
            return DecodedBarcode(code = gs1Serial.uppercase(), extractedSerial = gs1Serial.uppercase())
        }

        // 3. Fallback for 35-char Genius 3 format if non-standard prefix
        if (clean.length == 35) {
            val match = GENIUS3_FALLBACK_REGEX.find(clean)
            if (match != null) {
                val sn = match.value.uppercase()
                return DecodedBarcode(code = sn, extractedSerial = sn)
            }
        }

        // 4. Plain barcode / asset number (strip any stray control chars)
        val plain = clean.replace("\u001D", "").trim()
        return DecodedBarcode(code = plain)
    }

    /**
     * Walks a raw GS1 element string to extract Application Identifier 21 (Serial Number).
     * Supports fixed-length AIs (01/02 = 14 digits, 11/12/13/15/16/17 = 6 digits, 20 = 2 chars)
     * and <GS> (\u001D) delimited variable-length AIs (such as 10 Batch/Lot).
     */
    private fun extractGs1Ai21(input: String): String? {
        val startsWith01 = input.length > 18 && input.startsWith("01") &&
            input.substring(2, 16).all { it.isDigit() }
        val hasGroupSeparator = input.contains('\u001D')
        if (!startsWith01 && !hasGroupSeparator) return null

        var pos = 0
        val len = input.length
        while (pos < len) {
            while (pos < len && input[pos] == '\u001D') pos++
            if (pos + 2 > len) break

            val ai2 = input.substring(pos, pos + 2)
            when (ai2) {
                "01", "02" -> {
                    if (pos + 16 <= len && input.substring(pos + 2, pos + 16).all { it.isDigit() }) {
                        pos += 16
                    } else return null
                }
                "11", "12", "13", "15", "16", "17" -> {
                    if (pos + 8 <= len && input.substring(pos + 2, pos + 8).all { it.isDigit() }) {
                        pos += 8
                    } else return null
                }
                "20" -> {
                    if (pos + 4 <= len) pos += 4 else return null
                }
                "21" -> {
                    val start = pos + 2
                    val nextGs = input.indexOf('\u001D', start)
                    val serial = if (nextGs != -1) input.substring(start, nextGs) else input.substring(start)
                    val trimmedSerial = serial.trim()
                    return if (trimmedSerial.isNotEmpty() && trimmedSerial.length <= 20) trimmedSerial else null
                }
                "10", "30", "37" -> {
                    val nextGs = input.indexOf('\u001D', pos + 2)
                    if (nextGs != -1) {
                        pos = nextGs + 1
                    } else {
                        return null
                    }
                }
                else -> {
                    val nextGs = input.indexOf('\u001D', pos + 2)
                    if (nextGs != -1) {
                        pos = nextGs + 1
                    } else {
                        return null
                    }
                }
            }
        }
        return null
    }
}
