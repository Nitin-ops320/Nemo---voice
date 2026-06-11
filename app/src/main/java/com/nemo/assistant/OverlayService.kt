package com.nemo.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.*

class OverlayService : Service(), TextToSpeech.OnInitListener {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var tvResponse: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnClose: ImageButton

    // ── TTS ────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech

    // ── Vosk STT ───────────────────────────────────────────────────────────
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var isListening = false

    // ── Gemini ────────────────────────────────────────────────────────────
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val conversationHistory = mutableListOf<JSONObject>()

    // ── Notification ──────────────────────────────────────────────────────
    private val CHANNEL_ID = "nemo_channel"
    private val NOTIF_ID = 1

    // ── Bubble drag state ─────────────────────────────────────────────────
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var panelVisible = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        showBubble()
        loadVoskModel()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FLOATING BUBBLE
    // ══════════════════════════════════════════════════════════════════════
    private fun showBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        // Drag + tap logic
        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    bubbleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    // If barely moved → treat as a tap → toggle panel
                    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
                        togglePanel(bubbleParams)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PANEL (response + controls)
    // ══════════════════════════════════════════════════════════════════════
    private fun togglePanel(bubbleParams: WindowManager.LayoutParams) {
        if (panelVisible) {
            windowManager.removeView(panelView)
            panelVisible = false
        } else {
            showPanel(bubbleParams)
        }
    }

    private fun showPanel(bubbleParams: WindowManager.LayoutParams) {
        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.overlay_panel, null)

        tvResponse = panelView.findViewById(R.id.tvResponse)
        tvStatus = panelView.findViewById(R.id.tvStatus)
        btnMic = panelView.findViewById(R.id.btnMic)
        btnClose = panelView.findViewById(R.id.btnClose)

        tvResponse.text = "Tap the mic and speak."

        btnMic.setOnClickListener {
            if (isListening) stopListening() else startListening()
        }

        btnClose.setOnClickListener {
            windowManager.removeView(panelView)
            panelVisible = false
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.85).toInt()

        // Position panel near bubble but keep on screen
        var panelX = bubbleParams.x - panelWidth / 2 + 50
        panelX = panelX.coerceIn(20, screenWidth - panelWidth - 20)

        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX
            y = bubbleParams.y + 130
        }

        windowManager.addView(panelView, panelParams)
        panelVisible = true
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOSK — offline speech recognition
    // ══════════════════════════════════════════════════════════════════════
    private fun loadVoskModel() {
        scope.launch(Dispatchers.IO) {
            try {
                // Vosk model should be in app's files dir
                val modelDir = File(filesDir, "vosk-model-small-en-us")
                if (!modelDir.exists()) {
                    withContext(Dispatchers.Main) {
                        updateStatus("Vosk model missing. Using online STT fallback.")
                    }
                    return@launch
                }
                model = Model(modelDir.absolutePath)
                withContext(Dispatchers.Main) {
                    updateStatus("Vosk ready. Tap mic to speak.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("STT setup error: ${e.message}")
                }
            }
        }
    }

    private fun startListening() {
        if (model == null) {
            updateStatus("Vosk model not loaded. Check files.")
            return
        }
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService!!.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val json = JSONObject(hypothesis ?: "{}")
                    val partial = json.optString("partial", "")
                    if (partial.isNotEmpty()) updateStatus("Hearing: $partial")
                }

                override fun onResult(hypothesis: String?) {
                    val json = JSONObject(hypothesis ?: "{}")
                    val text = json.optString("text", "").trim()
                    if (text.isNotEmpty()) {
                        stopListening()
                        updateStatus("You said: $text")
                        askGemini(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val json = JSONObject(hypothesis ?: "{}")
                    val text = json.optString("text", "").trim()
                    if (text.isNotEmpty()) {
                        stopListening()
                        askGemini(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    stopListening()
                    updateStatus("STT error: ${exception?.message}")
                }

                override fun onTimeout() {
                    stopListening()
                    updateStatus("Mic timed out. Tap to try again.")
                }
            })
            isListening = true
            updateMicButton(true)
            updateStatus("Listening… speak now")
        } catch (e: Exception) {
            updateStatus("Can't start mic: ${e.message}")
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService = null
        isListening = false
        updateMicButton(false)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GEMINI API
    // ══════════════════════════════════════════════════════════════════════
    private fun askGemini(userText: String) {
        val prefs = getSharedPreferences("nemo_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            updateStatus("No API key. Open Nemo app to set it.")
            return
        }

        // Add to history
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userText)))
        }
        conversationHistory.add(userMsg)

        updateStatus("Thinking…")
        tvResponse.text = "…"

        scope.launch(Dispatchers.IO) {
            try {
                val contentsArray = JSONArray()

                // System prompt for Nemo
                val systemPrompt = "You are Nemo, a helpful AI assistant on the user's Android phone. " +
                    "You are concise, friendly, and practical. Keep answers short — 2-3 sentences max unless asked for detail. " +
                    "You can help with tasks, questions, reminders, calculations, and general knowledge."

                // Build request
                val requestBody = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                    put("contents", JSONArray().apply {
                        conversationHistory.forEach { put(it) }
                    })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 200)
                        put("temperature", 0.7)
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string() ?: ""
                    val json = JSONObject(responseStr)

                    if (!response.isSuccessful) {
                        val errMsg = json.optJSONObject("error")?.optString("message") ?: "API error ${response.code}"
                        withContext(Dispatchers.Main) {
                            updateStatus("Error: $errMsg")
                            tvResponse.text = "Sorry, I couldn't connect."
                        }
                        conversationHistory.removeLastOrNull()
                        return@use
                    }

                    val reply = json
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()

                    // Add assistant reply to history
                    val assistantMsg = JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", reply)))
                    }
                    conversationHistory.add(assistantMsg)

                    // Keep history from growing too large (last 10 exchanges)
                    while (conversationHistory.size > 20) {
                        conversationHistory.removeAt(0)
                    }

                    withContext(Dispatchers.Main) {
                        tvResponse.text = reply
                        updateStatus("Tap mic to ask again")
                        speakOut(reply)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Network error: ${e.message}")
                    tvResponse.text = "Couldn't reach Gemini. Check internet."
                }
                conversationHistory.removeLastOrNull()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TEXT TO SPEECH
    // ══════════════════════════════════════════════════════════════════════
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setSpeechRate(0.95f)
            tts.setPitch(1.0f)
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nemo_utterance")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI HELPERS
    // ══════════════════════════════════════════════════════════════════════
    private fun updateStatus(msg: String) {
        if (panelVisible && ::tvStatus.isInitialized) {
            tvStatus.text = msg
        }
    }

    private fun updateMicButton(listening: Boolean) {
        if (panelVisible && ::btnMic.isInitialized) {
            if (listening) {
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                btnMic.alpha = 1.0f
            } else {
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                btnMic.alpha = 0.6f
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NOTIFICATION (required for foreground service)
    // ══════════════════════════════════════════════════════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nemo Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nemo is running in the background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo is active")
            .setContentText("Tap the floating bubble to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLEANUP
    // ══════════════════════════════════════════════════════════════════════
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopListening()
        tts.shutdown()
        if (::bubbleView.isInitialized) {
            try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        }
        if (panelVisible) {
            try { windowManager.removeView(panelView) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?) = null
}
