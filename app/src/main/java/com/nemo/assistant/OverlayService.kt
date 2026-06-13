package com.nemo.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class OverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var tvResponse: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnReadScreen: Button

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val conversationHistory = mutableListOf<JSONObject>()

    private val CHANNEL_ID = "nemo_channel"
    private val NOTIF_ID = 1

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var panelVisible = false
    private var savedBubbleParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        showBubble()
    }

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

        savedBubbleParams = bubbleParams

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

    private fun togglePanel(bubbleParams: WindowManager.LayoutParams) {
        if (panelVisible) {
            stopListening()
            windowManager.removeView(panelView)
            panelVisible = false
        } else {
            showPanel(bubbleParams)
        }
    }

    private fun showPanel(bubbleParams: WindowManager.LayoutParams) {
        val context = this

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            setBackgroundColor(Color.parseColor("#E60A0A1A"))
            elevation = 16f
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(context).apply {
            text = "N E M O"
            textSize = 15f
            setTextColor(Color.parseColor("#00F5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnClose = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                stopListening()
                windowManager.removeView(panelView)
                panelVisible = false
            }
        }
        header.addView(tvTitle)
        header.addView(btnClose)
        root.addView(header)

        // Status
        tvStatus = TextView(context).apply {
            text = "Ready — tap mic or type"
            textSize = 11f
            setTextColor(Color.parseColor("#00AA88"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
        }
        root.addView(tvStatus)

        // Response area
        tvResponse = TextView(context).apply {
            text = "Ask me anything!"
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCDD"))
            setPadding(0, 0, 0, 24)
            minHeight = 120
            setLineSpacing(0f, 1.5f)
        }
        root.addView(tvResponse)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#222244"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 0, 0, 24) }
        }
        root.addView(divider)

        // READ SCREEN BUTTON
        btnReadScreen = Button(context).apply {
            text = "👁  Read My Screen"
            setTextColor(Color.parseColor("#0A0A1A"))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#00CCAA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 20) }
            setOnClickListener {
                // Hide panel so we read the real screen underneath
                windowManager.removeView(panelView)
                panelVisible = false
                scope.launch {
                    delay(1000) // wait for panel to disappear
                    val screenText = NemoAccessibilityService.instance?.readScreen()
                    val bp = savedBubbleParams ?: bubbleParams
                    showPanel(bp)
                    if (screenText.isNullOrBlank()) {
                        updateStatus("Nothing read — enable Accessibility for Nemo in Settings")
                        tvResponse.text = "Go to Settings → Accessibility → Nemo Screen Reader → Turn ON"
                    } else {
                        updateStatus("Reading screen…")
                        tvResponse.text = "Reading screen…"
                        askGemini("Here is what is currently on my Android screen:\n\n$screenText\n\nPlease summarize what you see in 2-3 sentences.")
                    }
                }
            }
        }
        root.addView(btnReadScreen)

        // Input row
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnMic = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.parseColor("#0D1530"))
            setColorFilter(Color.parseColor("#00F5FF"))
            layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                setMargins(0, 0, 16, 0)
            }
            setPadding(20, 20, 20, 20)
            setOnClickListener {
                if (isListening) stopListening() else startListening()
            }
        }

        etInput = EditText(context).apply {
            hint = "Type or tap mic…"
            setHintTextColor(Color.parseColor("#555577"))
            setTextColor(Color.parseColor("#CCCCDD"))
            textSize = 13f
            setBackgroundColor(Color.parseColor("#0D0D1F"))
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 16, 0) }
            maxLines = 3
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendMessage(); true
                } else false
            }
        }

        btnSend = Button(context).apply {
            text = "SEND"
            setTextColor(Color.parseColor("#0A0A1A"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#00F5FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { sendMessage() }
        }

        inputRow.addView(btnMic)
        inputRow.addView(etInput)
        inputRow.addView(btnSend)
        root.addView(inputRow)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val panelWidth = (screenWidth * 0.88).toInt()

        var panelX = bubbleParams.x - panelWidth / 2 + 50
        panelX = panelX.coerceIn(20, screenWidth - panelWidth - 20)

        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX
            y = bubbleParams.y + 130
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelView = root
        windowManager.addView(panelView, panelParams)
        panelVisible = true
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Speech recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                updateMicIcon(true)
                updateStatus("Listening… speak now")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateStatus("Processing…") }
            override fun onError(error: Int) {
                isListening = false
                updateMicIcon(false)
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                    else -> "Mic error ($error). Try again."
                }
                updateStatus(msg)
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                updateMicIcon(false)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    etInput.setText(text)
                    sendMessage()
                } else {
                    updateStatus("Didn't catch that. Try again.")
                }
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrEmpty()) updateStatus("Hearing: $text")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        updateMicIcon(false)
    }

    private fun updateMicIcon(listening: Boolean) {
        if (panelVisible && ::btnMic.isInitialized) {
            btnMic.alpha = if (listening) 1.0f else 0.7f
            btnMic.setColorFilter(
                if (listening) Color.parseColor("#FF4466") else Color.parseColor("#00F5FF")
            )
        }
    }

    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText("")
        updateStatus("Thinking…")
        tvResponse.text = "…"
        askGemini(text)
    }

    private fun askGemini(userText: String) {
        val prefs = getSharedPreferences("nemo_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            updateStatus("No API key. Open Nemo app to set it.")
            return
        }

        val userMsg = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userText)))
        }
        conversationHistory.add(userMsg)

        scope.launch(Dispatchers.IO) {
            try {
                val systemPrompt = "You are Nemo, a helpful AI assistant on the user's Android phone. " +
                        "You are concise, friendly, and practical. Keep answers short — 2-3 sentences max unless asked for detail."

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

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
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
                            tvResponse.text = "Couldn't connect to Gemini."
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

                    val assistantMsg = JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", reply)))
                    }
                    conversationHistory.add(assistantMsg)
                    while (conversationHistory.size > 20) conversationHistory.removeAt(0)

                    withContext(Dispatchers.Main) {
                        tvResponse.text = reply
                        updateStatus("Tap mic or type to continue")
                        speakOut(reply)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Network error: ${e.message}")
                    tvResponse.text = "Check your internet connection."
                }
                conversationHistory.removeLastOrNull()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.ENGLISH
            tts.setSpeechRate(0.95f)
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nemo_utterance")
    }

    private fun updateStatus(msg: String) {
        if (panelVisible && ::tvStatus.isInitialized) tvStatus.text = msg
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Nemo Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo is active")
            .setContentText("Tap the floating bubble to chat")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopListening()
        tts.shutdown()
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        if (panelVisible) try { windowManager.removeView(panelView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
