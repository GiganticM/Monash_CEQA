package org.monash.ceqa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.monash.ceqa.databinding.ActivityAddAssetBinding

/**
 * Shown when an asset ID is NOT found in the source CSV.
 * The user enters department, make, model and serial number,
 * then the data is passed back to MainActivity for saving.
 */
class AddAssetActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ASSET_NUMBER = "extra_asset_number"
        const val EXTRA_DEPARTMENT   = "extra_department"
        const val EXTRA_MAKE         = "extra_make"
        const val EXTRA_MODEL        = "extra_model"
        const val EXTRA_SERIAL       = "extra_serial"
    }

    private lateinit var binding: ActivityAddAssetBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddAssetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show the asset ID that wasn't found so the user has context
        val assetNumber = intent.getStringExtra(EXTRA_ASSET_NUMBER) ?: ""
        binding.tvAssetId.text = "New Asset: $assetNumber"
        title = "Add New Asset"

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.etDepartment.filters = arrayOf(InputFilter.AllCaps())
        binding.etSerial.filters = arrayOf(InputFilter.AllCaps())

        binding.btnSave.setOnClickListener {
            if (validateFields()) {
                returnResult(assetNumber)
            }
        }
    }

    // ─── Validation & Confirmation ────────────────────────────────────────────

    private fun validateFields(): Boolean {
        val missingFields = mutableListOf<String>()

        fun check(value: String, label: String) {
            if (value.isBlank()) missingFields.add(label)
        }

        check(binding.etDepartment.text.toString(), "Department")
        check(binding.etMake.text.toString(),       "Make")
        check(binding.etModel.text.toString(),      "Model")
        check(binding.etSerial.text.toString(),     "Serial Number")

        return if (missingFields.isEmpty()) {
            true  // All filled - proceed to save
        } else {
            showIncompleteDialog(missingFields)
            false // Wait for user choice in the dialog
        }
    }

    private fun showIncompleteDialog(missingFields: List<String>) {
        val fieldList = missingFields.joinToString("\n") { "  • $it" }
        val message = if (missingFields.size == 1)
            "The following field has not been filled in:\n\n$fieldList\n\nDo you want to save anyway?"
        else
            "The following ${missingFields.size} fields have not been filled in:\n\n$fieldList\n\nDo you want to save anyway?"

        AlertDialog.Builder(this)
            .setTitle("Incomplete Information")
            .setMessage(message)
            .setNegativeButton("Go Back", null)
            .setPositiveButton("Save Anyway") { _, _ ->
                returnResult(intent.getStringExtra(EXTRA_ASSET_NUMBER) ?: "")
            }
            .show()
    }

    // ─── Result ───────────────────────────────────────────────────────────────

    private fun returnResult(assetNumber: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_ASSET_NUMBER, assetNumber)
            putExtra(EXTRA_DEPARTMENT,   binding.etDepartment.text.toString().trim())
            putExtra(EXTRA_MAKE,         binding.etMake.text.toString().trim())
            putExtra(EXTRA_MODEL,        binding.etModel.text.toString().trim())
            putExtra(EXTRA_SERIAL,       binding.etSerial.text.toString().trim())
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(Activity.RESULT_CANCELED)
        finish()
        return true
    }
}
