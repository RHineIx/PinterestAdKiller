package com.umbra.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.umbra.hooks.utils.Constants

class MainActivity : AppCompatActivity() {

    private lateinit var etClipLimit: TextInputEditText
    private lateinit var etRetentionDays: TextInputEditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        etClipLimit = findViewById(R.id.etClipLimit)
        etRetentionDays = findViewById(R.id.etRetentionDays)
        btnSave = findViewById(R.id.btnSaveGboard)

        loadSettings()

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(
            Constants.PREFS_FILE,
            Context.MODE_WORLD_READABLE
        )

        val limit = prefs.getInt(
            Constants.KEY_GBOARD_LIMIT,
            Constants.DEFAULT_GBOARD_LIMIT
        )

        val days = prefs.getInt(
            Constants.KEY_GBOARD_RETENTION_DAYS,
            Constants.DEFAULT_GBOARD_RETENTION
        )

        etClipLimit.setText(limit.toString())
        etRetentionDays.setText(days.toString())
    }

    @SuppressLint("WorldReadableFiles")
    private fun saveSettings() {

        val limit = etClipLimit.text
            ?.toString()
            ?.toIntOrNull()
            ?: Constants.DEFAULT_GBOARD_LIMIT

        val days = etRetentionDays.text
            ?.toString()
            ?.toIntOrNull()
            ?: Constants.DEFAULT_GBOARD_RETENTION

        val prefs = getSharedPreferences(
            Constants.PREFS_FILE,
            Context.MODE_WORLD_READABLE
        )

        prefs.edit()
            .putInt(Constants.KEY_GBOARD_LIMIT, limit)
            .putInt(Constants.KEY_GBOARD_RETENTION_DAYS, days)
            .commit()

        Toast.makeText(
            this,
            "Saved. Restart Gboard.",
            Toast.LENGTH_SHORT
        ).show()

        openGboardAppInfo()
    }

    private fun openGboardAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:com.google.android.inputmethod.latin")
                }
            )
        } catch (_: Throwable) {
        }
    }
}