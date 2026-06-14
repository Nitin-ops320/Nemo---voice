package com.nemo.assistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private val MEDIA_PROJECTION_REQUEST = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("nemo_prefs", MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "")
        if (!savedKey.isNullOrEmpty()) {
            binding.etApiKey.setText(savedKey)
        }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter your Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
        }

        binding.btnLaunchNemo.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter your Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("gemini_api_key", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please allow 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_REQUEST
                )
            } else {
                requestMediaProjection()
            }
        }

        binding.btnStopNemo.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Nemo stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST -> {
                if (Settings.canDrawOverlays(this)) {
                    requestMediaProjection()
                } else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_LONG).show()
                }
            }
            MEDIA_PROJECTION_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Save result code and data so OverlayService can use it
                    val prefs = getSharedPreferences("nemo_prefs", MODE_PRIVATE)
                    prefs.edit().putInt("projection_result_code", resultCode).apply()
                    startOverlayService(resultCode, data)
                } else {
                    // MediaProjection denied — still start but without screenshot ability
                    Toast.makeText(this, "Screen capture denied — vision features disabled", Toast.LENGTH_LONG).show()
                    startOverlayService(0, null)
                }
            }
        }
    }

    private fun startOverlayService(resultCode: Int, data: Intent?) {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("result_code", resultCode)
            putExtra("projection_data", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Nemo is now active! Look for the floating bubble.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }
}
