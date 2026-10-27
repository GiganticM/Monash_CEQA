package me.rxue.mhceqa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import me.rxue.mhceqa.ui.screens.SessionScreen
import me.rxue.mhceqa.ui.theme.MonashCeqaTheme
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
        const val KEY_SESSION_TYPE   = "session_type"
        const val KEY_NEEDS_SESSION_HEADER = "needs_session_header"
        const val SESSION_TYPE_QA    = "QA"
        const val SESSION_TYPE_IO    = "IO"

        private val DATE_FORMAT = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())

        fun isSessionActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SESSION_ACTIVE, false)

        fun todayDateString(): String = DATE_FORMAT.format(Date())
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MonashCEQA)
        super.onCreate(savedInstanceState)

        val savedUri = CsvManager(this).loadSavedSourceUri()
        if (savedUri != null) {
            val savedName = CsvManager(this).loadSavedSourceName()
            SourceAssetRepository.preload(this, savedUri, savedName)
        }

        if (isSessionActive(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent {
            MonashCeqaTheme {
                SessionScreen(
                    onStartSession = { initials, location, sessionType ->
                        startSession(initials, location, sessionType)
                    },
                    onOpenSettings = {
                        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    private fun startSession(initials: String, location: String, sessionType: String = SESSION_TYPE_QA) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putString(KEY_USER_INITIALS, initials)
            .putString(KEY_QA_LOCATION, location)
            .putString(KEY_SESSION_DATE, todayDateString())
            .putString(KEY_SESSION_TYPE, sessionType)
            .putBoolean(KEY_NEEDS_SESSION_HEADER, true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

