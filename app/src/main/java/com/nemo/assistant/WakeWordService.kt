package com.nemo.assistant

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

class WakeWordService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = true

    private val CHANNEL_ID = "nemo_wake_channel"
    private val NOTIF_ID = 2

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        startListeningLoop()
    }

    private fun startListeningLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
                if (text.contains("jarvis") || text.contains("nemo")) {
                    sendBroadcast(Intent("com.nemo.assistant.WAKE"))
                    // Pause briefly so it doesn't immediately re-trigger on its own response
                    handler.postDelayed({ restartListening(intent) }, 4000)
                } else {
                    restartListening(intent)
                }
            }

            override fun onError(error: Int) {
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT just means silence - restart
                restartListening(intent)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
    }

    private fun restartListening(intent: Intent) {
        if (!isRunning) return
        handler.postDelayed({
            if (isRunning) {
                try {
                    recognizer?.startListening(intent)
                } catch (e: Exception) {
                    recognizer?.destroy()
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    startListeningLoop()
                }
            }
        }, 300)
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
            .setContentTitle("Nemo is listening for \"Jarvis\" or \"Nemo\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        recognizer?.destroy()
        recognizer = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
