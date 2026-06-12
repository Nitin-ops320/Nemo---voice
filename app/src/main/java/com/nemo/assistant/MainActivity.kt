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

        val prefs = getSharedPreferences("nemo_prefs", MODE_PRIVATE)

        val savedKey = prefs.getString("gemini_api_key", "")
        if (!savedKey.isNullOrEmpty()) binding.etApiKey.setText(savedKey)

        val savedPvKey = prefs.getString("picovoice_key", "")
        if (!savedPvKey.isNullOrEmpty()) binding.etPicovoiceKey.setText(savedPvKey)

        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val pvKey = binding.etPicovoiceKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter your Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("gemini_api_key", key)
                .putString("picovoice_key", pvKey)
                .apply()
            Toast.makeText(this, "Keys saved!", Toast.LENGTH_SHORT).show()
        }

        binding.btnLaunchNemo.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            val pvKey = binding.etPicovoiceKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter your Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("gemini_api_key", key)
                .putString("picovoice_key", pvKey)
                .apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            } else {
                startNemo(pvKey)
            }
        }

        binding.btnStopNemo.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            stopService(Intent(this, WakeWordService::class.java))
            Toast.makeText(this, "Nemo stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                val prefs = getSharedPreferences("nemo_prefs", MODE_PRIVATE)
                startNemo(prefs.getString("picovoice_key", "") ?: "")
            } else {
                Toast.makeText(this, "Overlay permission is required for Nemo to work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startNemo(pvKey: String) {
        val overlayIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }

        val wakeIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(wakeIntent)
        } else {
            startService(wakeIntent)
        }
        Toast.makeText(this, "Nemo active! Say 'Jarvis' or 'Nemo' to wake it.", Toast.LENGTH_LONG).show()

        moveTaskToBack(true)
    }
}
