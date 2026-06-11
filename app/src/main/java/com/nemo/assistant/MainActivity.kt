package com.nemo.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nemo.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val OVERLAY_PERMISSION_REQUEST = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved API key if any
        val prefs = getSharedPreferences("nemo_prefs", MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "")
        if (!savedKey.isNullOrEmpty()) {
            binding.etApiKey.setText(savedKey)
        }

        // Save API key button
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter your Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
        }

        // Launch Nemo button
        binding.btnLaunchNemo.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter your Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("gemini_api_key", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                // Ask for overlay permission
                Toast.makeText(this, "Please allow 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            } else {
                startOverlayService()
            }
        }

        // Stop Nemo button
        binding.btnStopNemo.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Nemo stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                startOverlayService()
            } else {
                Toast.makeText(this, "Overlay permission is required for Nemo to work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Nemo is now active! Look for the floating bubble.", Toast.LENGTH_LONG).show()
        // Minimize app so user can see the overlay
        moveTaskToBack(true)
    }
}
