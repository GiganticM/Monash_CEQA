package me.rxue.mhceqa

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import me.rxue.mhceqa.ui.screens.AddAssetScreen
import me.rxue.mhceqa.ui.theme.MonashCeqaTheme

/**
 * Shown when an asset ID is NOT found in the source CSV.
 * The user enters department, make, model and serial number,
 * then the data is passed back to MainActivity for saving.
 *
 * Supports hardware barcode scanner broadcast mode so technicians
 * can scan the device Serial Number (or other fields) directly.
 */
class AddAssetActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ASSET_NUMBER = "extra_asset_number"
        const val EXTRA_DEPARTMENT   = "extra_department"
        const val EXTRA_MAKE         = "extra_make"
        const val EXTRA_MODEL        = "extra_model"
        const val EXTRA_SERIAL       = "extra_serial"
        const val EXTRA_CONDITION_SCORE = "extra_condition_score"
        const val EXTRA_IS_IO        = "extra_is_io"
    }

    private val latestBarcodeState = mutableStateOf<String?>(null)

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val barcode = BarcodeHelper.extractBarcodeFromIntent(intent)
            if (!barcode.isNullOrBlank()) {
                latestBarcodeState.value = barcode
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assetNumber = intent.getStringExtra(EXTRA_ASSET_NUMBER) ?: ""
        val isIo = intent.getBooleanExtra(EXTRA_IS_IO, false)

        setContent {
            MonashCeqaTheme {
                AddAssetScreen(
                    assetNumber = assetNumber,
                    isIo = isIo,
                    scannedBarcode = latestBarcodeState.value,
                    onClearBarcode = { latestBarcodeState.value = null },
                    onSave = { dept, make, model, serial, conditionScore ->
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_ASSET_NUMBER, assetNumber)
                            putExtra(EXTRA_DEPARTMENT, dept)
                            putExtra(EXTRA_MAKE, make)
                            putExtra(EXTRA_MODEL, model)
                            putExtra(EXTRA_SERIAL, serial)
                            putExtra(EXTRA_CONDITION_SCORE, conditionScore)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = BarcodeHelper.createBarcodeIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(barcodeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(barcodeReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(barcodeReceiver)
        } catch (_: Exception) {}
    }
}
