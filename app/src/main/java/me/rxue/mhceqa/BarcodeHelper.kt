package me.rxue.mhceqa

import android.content.Intent
import android.content.IntentFilter

object BarcodeHelper {

    val SCAN_ACTIONS = listOf(
        "scan.rcv.message",
        "android.bld.scan.action.RESULT_BROADCAST",
        "com.action.SCAN_RESULT",
        "com.scansku.barcode",
        "nlscan.action.SCANER_RESULT"
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
                val obj = bundle.get(key)
                if (obj is String && obj.isNotBlank()) {
                    return obj.trim()
                }
            }
        }
        return null
    }
}
