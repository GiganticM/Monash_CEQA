package org.monash.ceqa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import androidx.appcompat.app.AppCompatActivity
import org.monash.ceqa.databinding.ActivitySessionBinding

/**
 * Login screen shown before each QA session.
 * Captures user initials and QA location, then hands off to MainActivity.
 */
class SessionActivity : AppCompatActivity() {

    companion object {
        const val PREFS             = "asset_scanner_prefs"
        const val KEY_SESSION_ACTIVE   = "session_active"
        const val KEY_USER_INITIALS    = "session_user_initials"
        const val KEY_QA_LOCATION      = "session_qa_location"

        /** True if a session is currently active. */
        fun isSessionActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SESSION_ACTIVE, false)
    }

    private lateinit var binding: ActivitySessionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        // Force uppercase on initials
        binding.etInitials.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(5))

        binding.btnStartSession.setOnClickListener {
            val initials = binding.etInitials.text.toString().trim().uppercase()
            val location = binding.etLocation.text.toString().trim()

            if (initials.isEmpty()) {
                binding.tilInitials.error = "Please enter your initials"
                return@setOnClickListener
            }
            if (location.isEmpty()) {
                binding.tilLocation.error = "Please enter the QA location"
                return@setOnClickListener
            }

            binding.tilInitials.error = null
            binding.tilLocation.error = null

            // Persist session
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SESSION_ACTIVE, true)
                .putString(KEY_USER_INITIALS, initials)
                .putString(KEY_QA_LOCATION, location)
                .apply()

            // Go to main screen
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
