package com.nemo.assistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class WakeWordService : Service() {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private val CHANNEL_ID = "nemo_wake_channel"
    private val NOTIF_ID = 2

    private val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Setting up Nemo's ears..."))
        setupModel()
    }

    private fun setupModel() {
        Thread {
            try {
                val modelDir = File(filesDir, MODEL_DIR_NAME)
                if (!modelDir.exists() || modelDir.listFiles()?.isEmpty() != false) {
                    downloadAndExtractModel(modelDir)
                }
                model = Model(modelDir.absolutePath)
                startListening()
            } catch (e: Exception) {
                updateNotification("Setup failed: ${e.message}")
                stopSelf()
            }
        }.start()
    }

    private fun downloadAndExtractModel(targetDir: File) {
        updateNotification("Downloading voice model (~40MB)...")
        targetDir.mkdirs()
        val zipFile = File(cacheDir, "vosk-model.zip")

        URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        updateNotification("Extracting voice model...")
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("/", entry.name)
                if (name.isNotEmpty()) {
                    val outFile = File(targetDir, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
    }

    private fun startListening() {
        val rec = Recognizer(model, 16000.0f)
        speechService = SpeechService(rec, 16000.0f)
        updateNotification("Listening for \"Jarvis\" or \"Nemo\"")

        speechService?.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                checkForWakeWord(hypothesis)
            }

            override fun onResult(hypothesis: String?) {
                checkForWakeWord(hypothesis)
            }

            override fun onFinalResult(hypothesis: String?) {
                checkForWakeWord(hypothesis)
            }

            override fun onError(exception: Exception?) {
                speechService?.stop()
                startListening()
            }

            override fun onTimeout() {
                speechService?.stop()
                startListening()
            }
        })
    }

    private fun checkForWakeWord(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val json = JSONObject(hypothesis)
            val text = (json.optString("text", "") + " " + json.optString("partial", "")).lowercase().trim()
            if (text.isNotEmpty()) {
                updateNotification("Heard: \"$text\"")
            }
            if (text.contains("jarvis") || text.contains("nemo") || text.contains("nemu") || text.contains("demo")) {
                sendBroadcast(Intent("com.nemo.assistant.WAKE"))
                speechService?.stop()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startListening()
                }, 5000)
            }
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nemo Wake Word", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

            override fun onResult(hypothesis: String?) {
                checkForWakeWord(hypothesis)
            }

            override fun onFinalResult(hypothesis: String?) {
                checkForWakeWord(hypothesis)
            }

            override fun onError(exception: Exception?) {
                speechService?.stop()
                startListening()
            }

            override fun onTimeout() {
                speechService?.stop()
                startListening()
            }
        })
    }

    private fun checkForWakeWord(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val json = JSONObject(hypothesis)
            val text = (json.optString("text", "") + " " + json.optString("partial", "")).lowercase().trim()
            if (text.isNotEmpty()) {
                updateNotification("Heard: \"$text\"")
            }
            if (text.contains("jarvis") || text.contains("nemo") || text.contains("nemu") || text.contains("demo")) {
                sendBroadcast(Intent("com.nemo.assistant.WAKE"))
                speechService?.stop()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startListening()
                }, 5000)
            }
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nemo Wake Word", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
