package com.nemo.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

class WakeWordService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private val CHANNEL_ID = "nemo_wake_channel"
    private val NOTIF_ID = 2

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        startPorcupine()
    }

    private fun startPorcupine() {
        val prefs = getSharedPreferences("nemo_prefs", Context.MODE_PRIVATE)
        val accessKey = prefs.getString("picovoice_key", "") ?: ""
        if (accessKey.isEmpty()) {
            stopSelf()
            return
        }
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(applicationContext, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        sendBroadcast(Intent("com.nemo.assistant.WAKE"))
                    }
                })
            porcupineManager?.start()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nemo Wake Word", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo is listening for \"Jarvis\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.stop()
        porcupineManager?.delete()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

