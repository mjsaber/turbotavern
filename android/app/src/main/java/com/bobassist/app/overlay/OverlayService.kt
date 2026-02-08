package com.bobassist.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var collapsedView: View? = null
    private var expandedView: View? = null
    private var heroDataJson: String = "[]"
    private var apiKey: String = ""

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()

    private var contentContainer: LinearLayout? = null
    private var micButton: TextView? = null
    private var statusText: TextView? = null
    private var currentView = ViewState.DEFAULT

    private enum class ViewState {
        DEFAULT, LISTENING, PROCESSING, RESULTS, ERROR
    }

    companion object {
        private const val CHANNEL_ID = "bob_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private val TIER_COLORS = mapOf(
            "S" to "#ff6b6b",
            "A" to "#ffa502",
            "B" to "#2ed573",
            "C" to "#1e90ff",
            "D" to "#a4a4a4"
        )
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val prefs = getSharedPreferences("bob_assist_prefs", Context.MODE_PRIVATE)
        apiKey = prefs.getString("claude_api_key", "") ?: ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("heroData")?.let {
            heroDataJson = it
        }
        showCollapsed()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        collapsedView?.let { windowManager.removeView(it) }
        expandedView?.let { windowManager.removeView(it) }
        collapsedView = null
        expandedView = null
        super.onDestroy()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    // ===================== COLLAPSED VIEW =====================

    private fun showCollapsed() {
        expandedView?.let {
            windowManager.removeView(it)
            expandedView = null
        }

        val params = createLayoutParams()
        val size = dpToPx(48)

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1a1a2e"))
            setStroke(dpToPx(2), Color.parseColor("#f5a623"))
        }
        container.background = bgDrawable

        val textView = TextView(this).apply {
            text = "B"
            setTextColor(Color.parseColor("#f5a623"))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(textView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) showExpanded()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
        collapsedView = container
    }

    // ===================== EXPANDED VIEW =====================

    private fun showExpanded() {
        collapsedView?.let {
            windowManager.removeView(it)
            collapsedView = null
        }

        val params = createLayoutParams().apply {
            width = dpToPx(300)
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        val panelBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1a1a2e"))
            cornerRadius = dpToPx(12).toFloat()
            setStroke(dpToPx(1), Color.parseColor("#2a2a4e"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        // === HEADER ROW ===
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(this).apply {
            text = "英雄梯队"
            setTextColor(Color.parseColor("#f5a623"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleText)

        // Mic button
        val micBtn = TextView(this).apply {
            text = "🎤"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            val micBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2a2a4e"))
                cornerRadius = dpToPx(8).toFloat()
            }
            background = micBg
            setOnClickListener { onMicTapped() }
        }
        headerRow.addView(micBtn)
        this.micButton = micBtn

        // Minimize button
        val minimizeBtn = TextView(this).apply {
            text = "−"
            setTextColor(Color.parseColor("#8b8b9e"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setOnClickListener { showCollapsed() }
        }
        headerRow.addView(minimizeBtn)

        // Close button
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#e94560"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
            setOnClickListener { stopSelf() }
        }
        headerRow.addView(closeBtn)

        mainLayout.addView(headerRow)

        // === STATUS TEXT ===
        val statusTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8b8b9e"))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }
        mainLayout.addView(statusTv)
        this.statusText = statusTv

        // === SCROLLABLE CONTENT ===
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(320)
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(8), 0, 0)
        }

        scrollView.addView(content)
        mainLayout.addView(scrollView)
        this.contentContainer = content

        showDefaultView()

        // Drag support on header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(mainLayout, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(mainLayout, params)
        expandedView = mainLayout
    }

    // ===================== DEFAULT VIEW (S+A) =====================

    private fun showDefaultView() {
        currentView = ViewState.DEFAULT
        val container = contentContainer ?: return
        container.removeAllViews()
        statusText?.visibility = View.GONE

        micButton?.let {
            val bg = it.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#2a2a4e"))
        }

        val topTiers = arrayOf("S", "A")

        try {
            val heroes = JSONArray(heroDataJson)
            for (tier in topTiers) {
                val heroesInTier = mutableListOf<JSONObject>()
                for (i in 0 until heroes.length()) {
                    val hero = heroes.getJSONObject(i)
                    if (hero.getString("tier") == tier) {
                        heroesInTier.add(hero)
                    }
                }
                if (heroesInTier.isEmpty()) continue

                val tierHeader = TextView(this).apply {
                    text = "$tier 级"
                    setTextColor(Color.parseColor(TIER_COLORS[tier] ?: "#a4a4a4"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dpToPx(6), 0, dpToPx(3))
                }
                container.addView(tierHeader)

                for (hero in heroesInTier) {
                    container.addView(createHeroRow(hero))
                }
            }

            val showAllLink = TextView(this).apply {
                text = "▼ 查看全部梯队"
                setTextColor(Color.parseColor("#8b8b9e"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(8), 0, dpToPx(4))
                setOnClickListener { showAllTiersView() }
            }
            container.addView(showAllLink)
        } catch (e: Exception) {
            showErrorInContainer("加载英雄数据失败")
        }
    }

    // ===================== ALL TIERS VIEW =====================

    private fun showAllTiersView() {
        val container = contentContainer ?: return
        container.removeAllViews()

        val backLink = TextView(this).apply {
            text = "◀ 返回S+A梯队"
            setTextColor(Color.parseColor("#8b8b9e"))
            textSize = 12f
            setPadding(0, dpToPx(2), 0, dpToPx(6))
            setOnClickListener { showDefaultView() }
        }
        container.addView(backLink)

        val allTiers = arrayOf("S", "A", "B", "C", "D")

        try {
            val heroes = JSONArray(heroDataJson)
            for (tier in allTiers) {
                val heroesInTier = mutableListOf<JSONObject>()
                for (i in 0 until heroes.length()) {
                    val hero = heroes.getJSONObject(i)
                    if (hero.getString("tier") == tier) {
                        heroesInTier.add(hero)
                    }
                }
                if (heroesInTier.isEmpty()) continue

                val tierHeader = TextView(this).apply {
                    text = "$tier 级"
                    setTextColor(Color.parseColor(TIER_COLORS[tier] ?: "#a4a4a4"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dpToPx(6), 0, dpToPx(3))
                }
                container.addView(tierHeader)

                for (hero in heroesInTier) {
                    container.addView(createHeroRow(hero))
                }
            }
        } catch (e: Exception) {
            showErrorInContainer("加载英雄数据失败")
        }
    }

    // ===================== HERO ROW =====================

    private fun createHeroRow(hero: JSONObject): LinearLayout {
        val name = hero.getString("name")
        val tier = hero.optString("tier", "?")
        val tierColor = TIER_COLORS[tier] ?: "#a4a4a4"

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3))

            val badge = TextView(this@OverlayService).apply {
                text = tier
                setTextColor(Color.parseColor(tierColor))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                val badgeBg = GradientDrawable().apply {
                    setColor(Color.parseColor(tierColor + "22"))
                    cornerRadius = dpToPx(4).toFloat()
                    setStroke(dpToPx(1), Color.parseColor(tierColor))
                }
                background = badgeBg
                setPadding(dpToPx(5), dpToPx(1), dpToPx(5), dpToPx(1))
            }
            addView(badge)

            val nameView = TextView(this@OverlayService).apply {
                text = "  $name"
                setTextColor(Color.parseColor("#eaeaea"))
                textSize = 13f
            }
            addView(nameView)
        }
    }

    // ===================== VOICE INPUT =====================

    private fun onMicTapped() {
        if (currentView == ViewState.LISTENING) {
            speechRecognizer?.cancel()
            showDefaultView()
            return
        }

        if (apiKey.isBlank()) {
            showErrorInContainer("请先在应用设置中输入API密钥")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showErrorInContainer("语音识别不可用，请检查设备设置")
            return
        }

        startListening()
    }

    private fun startListening() {
        currentView = ViewState.LISTENING

        micButton?.let {
            val bg = it.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#e94560"))
        }

        statusText?.text = "正在听..."
        statusText?.setTextColor(Color.parseColor("#e94560"))
        statusText?.visibility = View.VISIBLE

        contentContainer?.removeAllViews()
        val listeningView = TextView(this).apply {
            text = "🎤 请说出你想查询的英雄...\n\n例如：\"拉法姆和尤格选哪个\""
            setTextColor(Color.parseColor("#8b8b9e"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32))
        }
        contentContainer?.addView(listeningView)

        // Make overlay focusable for audio capture
        expandedView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }

        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    mainHandler.post {
                        statusText?.text = "处理中..."
                    }
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音，请重试"
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误，请检查麦克风权限"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
                        else -> "语音识别失败（错误码：$error）"
                    }
                    mainHandler.post {
                        restoreNonFocusable()
                        showErrorInContainer(errorMsg)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    mainHandler.post {
                        restoreNonFocusable()
                        if (text.isBlank()) {
                            showErrorInContainer("未识别到语音，请重试")
                        } else {
                            onSpeechResult(text)
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        mainHandler.post {
                            statusText?.text = "\"$partial\""
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun restoreNonFocusable() {
        expandedView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    // ===================== CLAUDE API =====================

    private fun onSpeechResult(text: String) {
        currentView = ViewState.PROCESSING

        statusText?.text = "\"$text\" — AI分析中..."
        statusText?.setTextColor(Color.parseColor("#f5a623"))
        statusText?.visibility = View.VISIBLE

        micButton?.let {
            val bg = it.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#2a2a4e"))
        }

        contentContainer?.removeAllViews()
        val processingView = TextView(this).apply {
            this.text = "正在分析语音内容..."
            setTextColor(Color.parseColor("#f5a623"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(32), dpToPx(16), dpToPx(32))
        }
        contentContainer?.addView(processingView)

        callClaudeForHeroExtraction(text)
    }

    private fun callClaudeForHeroExtraction(userText: String) {
        val heroNames = mutableListOf<String>()
        try {
            val heroes = JSONArray(heroDataJson)
            for (i in 0 until heroes.length()) {
                heroNames.add(heroes.getJSONObject(i).getString("name"))
            }
        } catch (_: Exception) {}

        val systemPrompt = "从用户的自然语言中提取炉石传说酒馆战棋英雄名称。\n" +
            "可用英雄：${heroNames.joinToString("、")}\n" +
            "返回JSON：{\"heroes\":[\"英雄全名1\",\"英雄全名2\"]}\n" +
            "如果用户用昵称/简称/描述，匹配最接近的英雄全名。找不到则返回空数组。只返回JSON。"

        val requestBody = JSONObject().apply {
            put("model", "claude-sonnet-4-5-20250929")
            put("max_tokens", 100)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    showErrorInContainer("网络请求失败：${e.localizedMessage}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                mainHandler.post {
                    if (!response.isSuccessful) {
                        showErrorInContainer("API错误 ${response.code}")
                        return@post
                    }
                    try {
                        val json = JSONObject(body)
                        val content = json.getJSONArray("content")
                            .getJSONObject(0)
                            .getString("text")

                        val cleanJson = content
                            .replace("```json", "").replace("```", "").trim()

                        val result = JSONObject(cleanJson)
                        val heroArray = result.getJSONArray("heroes")
                        val extractedNames = mutableListOf<String>()
                        for (i in 0 until heroArray.length()) {
                            extractedNames.add(heroArray.getString(i))
                        }

                        if (extractedNames.isEmpty()) {
                            showErrorInContainer("未能识别出英雄名称，请重试")
                        } else {
                            showComparisonView(extractedNames, userText)
                        }
                    } catch (e: Exception) {
                        showErrorInContainer("解析AI响应失败")
                    }
                }
            }
        })
    }

    // ===================== COMPARISON VIEW =====================

    private fun showComparisonView(names: List<String>, originalQuery: String) {
        currentView = ViewState.RESULTS
        val container = contentContainer ?: return
        container.removeAllViews()

        statusText?.text = "\"$originalQuery\""
        statusText?.setTextColor(Color.parseColor("#8b8b9e"))
        statusText?.visibility = View.VISIBLE

        try {
            val allHeroes = JSONArray(heroDataJson)
            val matchedHeroes = mutableListOf<JSONObject>()

            for (name in names) {
                for (i in 0 until allHeroes.length()) {
                    val hero = allHeroes.getJSONObject(i)
                    val heroName = hero.getString("name")
                    if (heroName == name || heroName.contains(name) || name.contains(heroName)) {
                        matchedHeroes.add(hero)
                        break
                    }
                }
            }

            if (matchedHeroes.isEmpty()) {
                showErrorInContainer("未找到匹配的英雄")
                return
            }

            val resultTitle = TextView(this).apply {
                text = if (matchedHeroes.size == 1) "查询结果" else "英雄对比"
                setTextColor(Color.parseColor("#f5a623"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dpToPx(4), 0, dpToPx(8))
            }
            container.addView(resultTitle)

            for (hero in matchedHeroes) {
                container.addView(createHeroDetailCard(hero))
            }

            // Recommendation verdict for 2+ heroes
            if (matchedHeroes.size >= 2) {
                val tierOrder = mapOf("S" to 0, "A" to 1, "B" to 2, "C" to 3, "D" to 4)
                val sorted = matchedHeroes.sortedBy { tierOrder[it.optString("tier", "D")] ?: 5 }
                val best = sorted.first()
                val bestTier = best.getString("tier")
                val verdict = TextView(this).apply {
                    text = "推荐: ${best.getString("name")} (${bestTier}级)"
                    setTextColor(Color.parseColor("#2ed573"))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    val verdictBg = GradientDrawable().apply {
                        setColor(Color.parseColor("#2ed57322"))
                        cornerRadius = dpToPx(8).toFloat()
                        setStroke(dpToPx(1), Color.parseColor("#2ed573"))
                    }
                    background = verdictBg
                    setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpToPx(8) }
                }
                container.addView(verdict)
            }

            // Back link
            val backLink = TextView(this).apply {
                text = "◀ 返回梯队列表"
                setTextColor(Color.parseColor("#8b8b9e"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(12), 0, dpToPx(4))
                setOnClickListener { showDefaultView() }
            }
            container.addView(backLink)

        } catch (e: Exception) {
            showErrorInContainer("显示结果失败")
        }
    }

    private fun createHeroDetailCard(hero: JSONObject): LinearLayout {
        val name = hero.getString("name")
        val tier = hero.optString("tier", "?")
        val heroPower = hero.optString("heroPower", "")
        val tips = hero.optString("tips", "")
        val armor = hero.optInt("armor", 0)
        val tierColor = TIER_COLORS[tier] ?: "#a4a4a4"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#16213e"))
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), Color.parseColor(tierColor))
            }
            background = cardBg
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }

            // Name + tier badge row
            val nameRow = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameView = TextView(this@OverlayService).apply {
                text = name
                setTextColor(Color.parseColor("#eaeaea"))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameRow.addView(nameView)

            val badge = TextView(this@OverlayService).apply {
                text = tier
                setTextColor(Color.parseColor(tierColor))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                val badgeBg = GradientDrawable().apply {
                    setColor(Color.parseColor(tierColor + "22"))
                    cornerRadius = dpToPx(4).toFloat()
                    setStroke(dpToPx(1), Color.parseColor(tierColor))
                }
                background = badgeBg
                setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            }
            nameRow.addView(badge)
            addView(nameRow)

            // Hero power
            if (heroPower.isNotBlank()) {
                val powerView = TextView(this@OverlayService).apply {
                    text = "技能: $heroPower"
                    setTextColor(Color.parseColor("#f5a623"))
                    textSize = 12f
                    setPadding(0, dpToPx(4), 0, 0)
                }
                addView(powerView)
            }

            // Tips
            if (tips.isNotBlank()) {
                val tipsView = TextView(this@OverlayService).apply {
                    text = tips
                    setTextColor(Color.parseColor("#8b8b9e"))
                    textSize = 11f
                    setPadding(0, dpToPx(4), 0, 0)
                }
                addView(tipsView)
            }

            // Armor
            if (armor > 0) {
                val armorView = TextView(this@OverlayService).apply {
                    text = "护甲: $armor"
                    setTextColor(Color.parseColor("#a4a4a4"))
                    textSize = 11f
                    setPadding(0, dpToPx(3), 0, 0)
                }
                addView(armorView)
            }
        }
    }

    // ===================== ERROR VIEW =====================

    private fun showErrorInContainer(message: String) {
        currentView = ViewState.ERROR
        val container = contentContainer ?: return
        container.removeAllViews()

        micButton?.let {
            val bg = it.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#2a2a4e"))
        }
        statusText?.visibility = View.GONE

        val errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24))
        }

        val errorText = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#e94560"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        errorView.addView(errorText)

        val retryLink = TextView(this).apply {
            text = "点击返回梯队列表"
            setTextColor(Color.parseColor("#8b8b9e"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(12), 0, 0)
            setOnClickListener { showDefaultView() }
        }
        errorView.addView(retryLink)

        container.addView(errorView)
    }

    // ===================== NOTIFICATION =====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bob Assist 悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Bob Assist")
                .setContentText("英雄梯队悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Bob Assist")
                .setContentText("英雄梯队悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
