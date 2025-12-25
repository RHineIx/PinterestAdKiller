package com.umbra.hooks

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.umbra.hooks.databinding.ActivityGboardBinding
import com.umbra.hooks.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class GboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGboardBinding
    private lateinit var prefs: SharedPreferences

    @SuppressLint("WorldReadableFiles")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init Preferences with fallback
        try {
            @Suppress("DEPRECATION")
            prefs = getSharedPreferences(Constants.PREFS_FILE, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            prefs = getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE)
        }

        loadSettings()

        // Test Button
        binding.btnTestClipboard.setOnClickListener {
            val limitStr = binding.inputLimit.text.toString()
            val limit = limitStr.toIntOrNull() ?: 10
            runClipboardTest(limit)
        }

        // Save & Kill Button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val limit = prefs.getInt(Constants.PREF_GBOARD_CLIPBOARD_LIMIT, Constants.DEFAULT_CLIPBOARD_LIMIT)
        val days = prefs.getLong(Constants.PREF_GBOARD_HISTORY_RETENTION, Constants.DEFAULT_RETENTION_DAYS)

        binding.inputLimit.setText(limit.toString())
        binding.inputDays.setText(days.toString())
    }

    private fun saveSettings() {
        val limitStr = binding.inputLimit.text.toString()
        val daysStr = binding.inputDays.text.toString()

        val limit = limitStr.toIntOrNull() ?: Constants.DEFAULT_CLIPBOARD_LIMIT
        val days = daysStr.toLongOrNull() ?: Constants.DEFAULT_RETENTION_DAYS

        val editor = prefs.edit()
        editor.putInt(Constants.PREF_GBOARD_CLIPBOARD_LIMIT, limit)
        editor.putLong(Constants.PREF_GBOARD_HISTORY_RETENTION, days)
        
        if (editor.commit()) {
            fixPermissions()
            killGboard()
        } else {
            Toast.makeText(this, "Failed to save settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runClipboardTest(count: Int) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Toast.makeText(this, "Starting test: Copying $count items...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            for (i in 1..count) {
                val text = "Test Item #$i - (${System.currentTimeMillis()})"
                val clip = ClipData.newPlainText("UmbraTest", text)
                clipboard.setPrimaryClip(clip)
                // Small delay to ensure Gboard registers the copy
                delay(150) 
            }
            Toast.makeText(this@GboardActivity, "Done! Check Gboard.", Toast.LENGTH_LONG).show()
        }
    }

    private fun killGboard() {
        val gboardPackage = "com.google.android.inputmethod.latin"
        
        // Try Root Kill
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $gboardPackage"))
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Toast.makeText(this, "Settings Saved & Gboard Restarted (Root)", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } catch (e: Exception) {
            // Root failed
        }

        // Fallback
        Toast.makeText(this, "Root unavailable. Please Force Stop manually.", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", gboardPackage, null)
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fixPermissions() {
        try {
            val dataDir = applicationInfo.dataDir
            val sharedPrefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(sharedPrefsDir, "${Constants.PREFS_FILE}.xml")

            File(dataDir).setExecutable(true, false)
            File(dataDir).setReadable(true, false)
            
            if (sharedPrefsDir.exists()) {
                sharedPrefsDir.setExecutable(true, false)
                sharedPrefsDir.setReadable(true, false)
            }

            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsFile.setWritable(true, true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
