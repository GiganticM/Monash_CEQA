package org.monash.ceqa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.monash.ceqa.databinding.ActivitySessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionActivity : AppCompatActivity() {

    companion object {
        const val PREFS              = "asset_scanner_prefs"
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_USER_INITIALS  = "session_user_initials"
        const val KEY_QA_LOCATION    = "session_qa_location"
        const val KEY_SESSION_DATE   = "session_date"

        private val DATE_FORMAT = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

        fun isSessionActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SESSION_ACTIVE, false)

        fun todayDateString(): String = DATE_FORMAT.format(Date())
    }

    private lateinit var binding: ActivitySessionBinding

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isSessionActive(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Settings icon - floated ImageButton, no toolbar needed
        binding.btnSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        binding.etInitials.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(15))

        binding.btnStartSession.setOnClickListener {
            val initials = binding.etInitials.text.toString().trim().uppercase()
            val location = binding.etLocation.text.toString().trim()

            if (initials.isEmpty()) {
                binding.tilInitials.error = "Please enter your initials"
                return@setOnClickListener
            }
            binding.tilInitials.error = null
            binding.tilLocation.error = null

            if (location.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("No Location Entered")
                    .setMessage("You haven't entered a QA location. Do you want to continue without one?")
                    .setPositiveButton("Continue") { _, _ -> startSession(initials, location) }
                    .setNegativeButton("Go Back", null)
                    .show()
            } else {
                startSession(initials, location)
            }
        }
    }

    private fun startSession(initials: String, location: String) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putString(KEY_USER_INITIALS, initials)
            .putString(KEY_QA_LOCATION, location)
            .putString(KEY_SESSION_DATE, todayDateString())
            .apply()

        val csvManager = CsvManager(this)
        Thread {
            csvManager.appendSessionHeader(initials, location)
        }.start()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
