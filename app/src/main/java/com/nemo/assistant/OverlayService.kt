package com.nemo.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
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

    // MediaProjection for screenshots
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionResultCode: Int = 0
    private var projectionData: Intent? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        projectionResultCode = intent?.getIntExtra("result_code", 0) ?: 0
        projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("projection_data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("projection_data")
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        showBubble()
    }

    private fun initMediaProjection() {
        if (projectionResultCode == 0 || projectionData == null) return
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(projectionResultCode, projectionData!!)
        } catch (e: Exception) {
            // projection not available
        }
    }

    private fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        try {
            if (mediaProjection == null) initMediaProjection()
            val mp = mediaProjection
            if (mp == null) { callback(null); return }

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = mp.createVirtualDisplay(
                "NemoCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            // Wait for frame
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        image.close()
                        // Scale down to save bandwidth
                        val scaled = Bitmap.createScaledBitmap(bmp, width / 2, height / 2, true)
                        bmp.recycle()
                        virtualDisplay?.release()
                        callback(scaled)
                    } else {
                        virtualDisplay?.release()
                        callback(null)
                    }
                } catch (e: Exception) {
                    virtualDisplay?.release()
                    callback(null)
                }
            }, 500)
        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun bitmapToBase64(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun showBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }

        savedBubbleParams = bubbleParams

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x; initialY = bubbleParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    bubbleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, bubbleParams); true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) togglePanel(bubbleParams)
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
            setBackgroundColor(Color.parseColor("#F00A0A1A"))
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
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = ImageButton(context).apply {
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
            text = "Tell me what to do — I'll see your screen"
            textSize = 11f
            setTextColor(Color.parseColor("#00AA88"))
            typeface = Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
        }
        root.addView(tvStatus)

        // Response area
        tvResponse = TextView(context).apply {
            text = "Say or type a command, I'll look at your screen and act!"
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCDD"))
            setPadding(0, 0, 0, 24)
            minHeight = 120
            setLineSpacing(0f, 1.5f)
        }
        root.addView(tvResponse)

        // Divider
        root.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#222244"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(0, 0, 0, 24) }
        })

        // Input row
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnMic = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.parseColor("#0D1530"))
            setColorFilter(Color.parseColor("#00F5FF"))
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(0, 0, 16, 0) }
            setPadding(20, 20, 20, 20)
            setOnClickListener { if (isListening) stopListening() else startListening() }
        }

        etInput = EditText(context).apply {
            hint = "What should I do?"
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
                    executeCommand(); true
                } else false
            }
        }

        btnSend = Button(context).apply {
            text = "GO"
            setTextColor(Color.parseColor("#0A0A1A"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#00F5FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { executeCommand() }
        }

        inputRow.addView(btnMic)
        inputRow.addView(etInput)
        inputRow.addView(btnSend)
        root.addView(inputRow)

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val panelWidth = (screenWidth * 0.88).toInt()
        var panelX = bubbleParams.x - panelWidth / 2 + 50
        panelX = panelX.coerceIn(20, screenWidth - panelWidth - 20)

        val panelParams = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = panelX; y = bubbleParams.y + 130
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelView = root
        windowManager.addView(panelView, panelParams)
        panelVisible = true
    }

    // ── MAIN COMMAND EXECUTOR ─────────────────────────────────────────────
    private fun executeCommand() {
        val command = etInput.text.toString().trim()
        if (command.isEmpty()) return
        etInput.setText("")
        updateStatus("Looking at your screen…")
        tvResponse.text = "…"

        // Hide panel, take screenshot, then act
        windowManager.removeView(panelView)
        panelVisible = false

        scope.launch {
            delay(700) // let panel disappear
            takeScreenshot { bitmap ->
                scope.launch {
                    val bp = savedBubbleParams!!
                    showPanel(bp)
                    if (bitmap == null) {
                        // No screenshot — fall back to text only
                        updateStatus("No screen capture — trying text mode…")
                        askGeminiTextOnly(command)
                    } else {
                        updateStatus("Nemo is thinking…")
                        askGeminiVision(command, bitmap)
                    }
                }
            }
        }
    }

    // ── GEMINI VISION ─────────────────────────────────────────────────────
    private fun askGeminiVision(command: String, bitmap: Bitmap) {
        val prefs = getSharedPreferences("nemo_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isEmpty()) { updateStatus("No API key set"); return }

        scope.launch(Dispatchers.IO) {
            try {
                val base64Image = bitmapToBase64(bitmap)
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels

                val prompt = """
                    You are controlling an Android phone. The user says: "$command"
                    
                    Look at this screenshot and decide what action to take.
                    The screen is ${screenWidth}x${screenHeight} pixels.
                    
                    Reply with ONLY a JSON object:
                    {
                      "action": "tap" | "open_app" | "go_back" | "go_home" | "scroll_down" | "type_text" | "chat",
                      "x": 123,
                      "y": 456,
                      "text": "text to type if action is type_text, or app package if open_app, or chat reply",
                      "explanation": "what I see and what I'm doing"
                    }
                    
                    For "tap": give exact x,y pixel coordinates of what to tap on screen.
                    For "open_app": put the package name in "text" field.
                    For "chat": put your reply in "text" field.
                    Only use "tap" if you can clearly see the element on screen.
                """.trimIndent()

                val requestBody = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                            put(JSONObject().put("text", prompt))
                        })
                    }))
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
                        withContext(Dispatchers.Main) {
                            updateStatus("API error — trying text mode")
                            askGeminiTextOnly(command)
                        }
                        return@use
                    }

                    val reply = json.getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0)
                        .getString("text").trim()
                        .removePrefix("```json").removePrefix("```")
                        .removeSuffix("```").trim()

                    withContext(Dispatchers.Main) {
                        try {
                            val cmd = JSONObject(reply)
                            val action = cmd.optString("action")
                            val x = cmd.optDouble("x", -1.0).toFloat()
                            val y = cmd.optDouble("y", -1.0).toFloat()
                            val text = cmd.optString("text")
                            val explanation = cmd.optString("explanation")

                            updateStatus(explanation)
                            tvResponse.text = explanation

                            when (action) {
                                "tap" -> {
                                    if (x > 0 && y > 0) {
                                        windowManager.removeView(panelView)
                                        panelVisible = false
                                        scope.launch {
                                            delay(300)
                                            NemoAccessibilityService.instance?.tapAt(x, y)
                                            delay(800)
                                            showPanel(savedBubbleParams!!)
                                            updateStatus("✅ Tapped at ($x, $y)")
                                        }
                                    }
                                }
                                "open_app" -> openApp(text)
                                "go_back" -> NemoAccessibilityService.instance?.goBack()
                                "go_home" -> NemoAccessibilityService.instance?.goHome()
                                "scroll_down" -> {
                                    windowManager.removeView(panelView)
                                    panelVisible = false
                                    scope.launch {
                                        delay(300)
                                        NemoAccessibilityService.instance?.scrollDown()
                                        delay(500)
                                        showPanel(savedBubbleParams!!)
                                        updateStatus("✅ Scrolled down")
                                    }
                                }
                                "type_text" -> {
                                    NemoAccessibilityService.instance?.typeText(text)
                                    updateStatus("✅ Typed text")
                                }
                                "chat" -> {
                                    tvResponse.text = text
                                    speakOut(text)
                                    updateStatus("Tap mic or type to continue")
                                }
                                else -> {
                                    tvResponse.text = reply
                                    updateStatus("Done")
                                }
                            }
                        } catch (e: Exception) {
                            tvResponse.text = reply
                            updateStatus("Done")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Error: ${e.message}")
                    tvResponse.text = "Something went wrong. Try again."
                }
            }
        }
    }

    // ── FALLBACK TEXT MODE ────────────────────────────────────────────────
    private fun askGeminiTextOnly(command: String) {
        val prefs = getSharedPreferences("nemo_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isEmpty()) return

        val userMsg = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", command)))
        }
        conversationHistory.add(userMsg)

        scope.launch(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text",
                            "You are Nemo, a helpful AI assistant on Android. Be concise and friendly.")))
                    })
                    put("contents", JSONArray().apply { conversationHistory.forEach { put(it) } })
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 300)
                        put("temperature", 0.7)
                    })
                }
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                val request = Request.Builder().url(url)
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string() ?: ""
                    val json = JSONObject(responseStr)
                    if (!response.isSuccessful) {
                        withContext(Dispatchers.Main) { updateStatus("API error ${response.code}") }
                        return@use
                    }
                    val reply = json.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim()

                    conversationHistory.add(JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().put("text", reply)))
                    })
                    while (conversationHistory.size > 20) conversationHistory.removeAt(0)

                    withContext(Dispatchers.Main) {
                        tvResponse.text = reply
                        updateStatus("Tap mic or type to continue")
                        speakOut(reply)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { updateStatus("Network error: ${e.message}") }
            }
        }
    }

    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                updateStatus("✅ Opened app")
            } else {
                updateStatus("❌ App not found: $packageName")
                tvResponse.text = "App not found. Try saying the app name differently."
            }
        } catch (e: Exception) {
            updateStatus("❌ Failed to open app")
        }
    }

    // ── VOICE INPUT ───────────────────────────────────────────────────────
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatus("Speech not available"); return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { isListening = true; updateMicIcon(true); updateStatus("Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { updateStatus("Processing…") }
            override fun onError(error: Int) {
                isListening = false; updateMicIcon(false)
                updateStatus(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Mic error ($error)"
                })
                speechRecognizer?.destroy(); speechRecognizer = null
            }
            override fun onResults(results: Bundle?) {
                isListening = false; updateMicIcon(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                if (text.isNotEmpty()) { etInput.setText(text); executeCommand() }
                else updateStatus("Didn't catch that")
                speechRecognizer?.destroy(); speechRecognizer = null
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { if (it.isNotEmpty()) updateStatus("Hearing: $it") }
            }
            override fun onEvent(e: Int, p: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening(); speechRecognizer?.destroy()
        speechRecognizer = null; isListening = false; updateMicIcon(false)
    }

    private fun updateMicIcon(listening: Boolean) {
        if (panelVisible && ::btnMic.isInitialized) {
            btnMic.alpha = if (listening) 1.0f else 0.7f
            btnMic.setColorFilter(if (listening) Color.parseColor("#FF4466") else Color.parseColor("#00F5FF"))
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts.language = Locale.ENGLISH; tts.setSpeechRate(0.95f) }
    }

    private fun speakOut(text: String) { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nemo") }

    private fun updateStatus(msg: String) {
        if (panelVisible && ::tvStatus.isInitialized) tvStatus.text = msg
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Nemo Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nemo is active")
            .setContentText("Tap the bubble to give commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(intent).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel(); stopListening(); tts.shutdown()
        mediaProjection?.stop(); virtualDisplay?.release()
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        if (panelVisible) try { windowManager.removeView(panelView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null
}
