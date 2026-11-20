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
        const val EXTRA_INITIAL_SERIAL = "extra_initial_serial"
        const val EXTRA_INITIAL_DEPARTMENT = "extra_initial_department"
        const val EXTRA_INITIAL_MAKE = "extra_initial_make"
        const val EXTRA_INITIAL_MODEL = "extra_initial_model"
        const val EXTRA_INITIAL_CONDITION_SCORE = "extra_initial_condition_score"
        const val EXTRA_INITIAL_NOTE = "extra_initial_note"
        const val EXTRA_IS_EDIT_MODE = "extra_is_edit_mode"
        const val EXTRA_ORIGINAL_ASSET_NUMBER = "extra_original_asset_number"
        const val EXTRA_ORIGINAL_TIMESTAMP = "extra_original_timestamp"
        const val EXTRA_ACTION_DELETE = "extra_action_delete"
        const val EXTRA_DEPARTMENT   = "extra_department"
        const val EXTRA_MAKE         = "extra_make"
        const val EXTRA_MODEL        = "extra_model"
        const val EXTRA_SERIAL       = "extra_serial"
        const val EXTRA_CONDITION_SCORE = "extra_condition_score"
        const val EXTRA_NOTE         = "extra_note"
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
        val isEditMode = intent.getBooleanExtra(EXTRA_IS_EDIT_MODE, false)
        val assetNumber = intent.getStringExtra(EXTRA_ASSET_NUMBER) ?: ""
        val initialSerial = intent.getStringExtra(EXTRA_INITIAL_SERIAL) ?: ""
        val initialDepartment = intent.getStringExtra(EXTRA_INITIAL_DEPARTMENT) ?: ""
        val initialMake = intent.getStringExtra(EXTRA_INITIAL_MAKE) ?: ""
        val initialModel = intent.getStringExtra(EXTRA_INITIAL_MODEL) ?: ""
        val initialConditionScore = intent.getStringExtra(EXTRA_INITIAL_CONDITION_SCORE)?.ifEmpty { "+1" } ?: "+1"
        val initialNote = intent.getStringExtra(EXTRA_INITIAL_NOTE) ?: ""
        val originalAssetNumber = intent.getStringExtra(EXTRA_ORIGINAL_ASSET_NUMBER) ?: assetNumber
        val originalTimestamp = intent.getStringExtra(EXTRA_ORIGINAL_TIMESTAMP) ?: ""
        val isIo = intent.getBooleanExtra(EXTRA_IS_IO, false)

        setContent {
            MonashCeqaTheme {
                AddAssetScreen(
                    assetNumber = assetNumber,
                    initialSerial = initialSerial,
                    initialDepartment = initialDepartment,
                    initialMake = initialMake,
                    initialModel = initialModel,
                    initialConditionScore = initialConditionScore,
                    initialNote = initialNote,
                    isEditMode = isEditMode,
                    isIo = isIo,
                    scannedBarcode = latestBarcodeState.value,
                    onClearBarcode = { latestBarcodeState.value = null },
                    onSave = { finalAssetNum, dept, make, model, serial, conditionScore, note ->
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_ASSET_NUMBER, finalAssetNum)
                            putExtra(EXTRA_DEPARTMENT, dept)
                            putExtra(EXTRA_MAKE, make)
                            putExtra(EXTRA_MODEL, model)
                            putExtra(EXTRA_SERIAL, serial)
                            putExtra(EXTRA_CONDITION_SCORE, conditionScore)
                            putExtra(EXTRA_NOTE, note)
                            if (isEditMode) {
                                putExtra(EXTRA_ORIGINAL_ASSET_NUMBER, originalAssetNumber)
                                putExtra(EXTRA_ORIGINAL_TIMESTAMP, originalTimestamp)
                            }
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onDelete = if (isEditMode) {
                        {
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_ACTION_DELETE, true)
                                putExtra(EXTRA_ORIGINAL_ASSET_NUMBER, originalAssetNumber)
                                putExtra(EXTRA_ORIGINAL_TIMESTAMP, originalTimestamp)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    } else null,
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
