package com.delivery.callwatch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TARGET_PACKAGE = "com.woowahan.bros"

        private const val PREFS_NAME = "CallWatch"

        private const val STATUS_NORMAL = "정상 작동 중"
        private const val STATUS_WAITING = "배민커넥트 대기 중"
        private const val STATUS_ERROR = "정보 수집 확인 필요"
        private const val STATUS_RECOGNIZING = "화면인식 중"

        private val GREEN = Color.rgb(0, 220, 80)
        private val RED = Color.rgb(255, 55, 55)
        private val WHITE = Color.WHITE
    }

    private val handler = Handler(Looper.getMainLooper())

    private val database by lazy {
        CallDatabase(applicationContext)
    }

    private val earningsDatabase by lazy {
        DailyEarningsDatabase(applicationContext)
    }

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private lateinit var windowManager: WindowManager

    private var serviceConnected = false
    private var targetForeground = false
    private var screenReadSuccess = false
    private var pendingWindowCheck: Runnable? = null

    // 기존 홈/다른 앱용 보조창
    private var overlayView: FrameLayout? = null
    private var settingsOverlayVisible = false

    // 배민커넥트 전용 전체창 (기존 보조창과 별도 인스턴스)
    private var fullOverlayView: View? = null
    private var fullOverlayParams: WindowManager.LayoutParams? = null
    private var fullStatusView: TextView? = null
    private var todayMetricsView: TextView? = null
    private var weeklyMetricsView: TextView? = null
    private var overlayPanel: LinearLayout? = null
    private var statusView: TextView? = null
    private var dashboardTodayView: TextView? = null
    private var dashboardWeeklyView: TextView? = null
    private var dashboardWaitingView: TextView? = null
    private var phraseView: TextView? = null

    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayCallsContainer: LinearLayout? = null
    private var overlayPageView: TextView? = null
    private var overlayWaitingCalls: List<DispatchFloatingBar.DispatchInfo> = emptyList()
    private var overlayCallPage = 0
    private var overlaySwipeStartX = 0f

    // 신규배차 플로팅바
    private var dispatchFloatingBar: DispatchFloatingBar? = null
    // 최고 금액 대기콜 전용 터치 창
    private var highestCallOverlayView: View? = null
    private var highestCallTextView: TextView? = null
    private var highestCallId: String? = null

    private var statusIndex = 0
    private var phraseIndex = 0
    private var blinkVisible = true

    private val centerPhrases = listOf(
        "자동배차 수락중입니다.",
        "대기콜 가져오는중"
    )

    // 30분이 지난 콜을 주기적으로 정리
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            try {
                database.deleteExpired()
            } catch (_: Exception) {
            }

            handler.postDelayed(this, 10_000L)
        }
    }

    // 실제 상태에 맞는 문구를 2초마다 표시
    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStatusDisplay()
            handler.postDelayed(this, 2_000L)
        }
    }

    // 가운데 문구를 3초마다 변경
    private val phraseRunnable = object : Runnable {
        override fun run() {
            phraseIndex = (phraseIndex + 1) % centerPhrases.size

            phraseView?.text = centerPhrases[phraseIndex]

            handler.postDelayed(this, 3_000L)
        }
    }

    // 가운데 문구 깜박임
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkVisible = true
            phraseView?.alpha = 1f
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceConnected = true

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        if (dispatchFloatingBar == null) {
            dispatchFloatingBar = DispatchFloatingBar(this) { cardId ->
                acceptDispatchCard(cardId)
            }
        }
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 100
        }

        handler.removeCallbacks(cleanupRunnable)
        handler.post(cleanupRunnable)

        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
    }

    private fun isRunning(): Boolean {
        return preferences.getBoolean("logged_in", false) &&
                preferences.getBoolean("monitoring_enabled", false)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventPackage = event.packageName?.toString() ?: return
        val ownPackage = applicationContext.packageName

        if (eventPackage == ownPackage) return

        android.util.Log.i(
            "EarningsTabDebug",
            "EVENT type=${event.eventType}, package=$eventPackage, text=${event.text}"
        )

        if (eventPackage == TARGET_PACKAGE) {
            pendingWindowCheck?.let { handler.removeCallbacks(it) }
            pendingWindowCheck = null
            targetForeground = true
            dispatchFloatingBar?.show()
            showFullOverlay()
            hideOverlay()

            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                checkClickedButton(event)
            }

            val root = rootInActiveWindow
            if (root == null) {
                screenReadSuccess = false
                updateStatusDisplay()
                return
            }

            try {
                val extractedText = StringBuilder()
                val extractedDetails = StringBuilder()

                readNode(root, extractedText, extractedDetails, 0)

                val screenText = extractedText.toString().trim()
                val screenDetails = extractedDetails.toString().trim()
                screenReadSuccess = screenText.isNotBlank()

                if (screenReadSuccess) {
                    preferences.edit()
                        .putString("latest_screen_text", screenText)
                        .putString("latest_screen_details", screenDetails)
                        .putString("latest_screen_package", eventPackage)
                        .putLong("latest_screen_time", System.currentTimeMillis())
                        .apply()

                    val eventSource = event.source
                    try {
                        android.util.Log.i(
                            "EarningsTabDebug",
                            "TYPE=${event.eventType}, CHANGE=${event.contentChangeTypes}, " +
                                    "TEXT=${eventSource?.text}, DESC=${eventSource?.contentDescription}, " +
                                    "ID=${eventSource?.viewIdResourceName}"
                        )
                    } finally {
                        eventSource?.recycle()
                    }

                    updateTodayMetrics(screenText)

                    collectCall(
                        root = root,
                        screenText = screenText,
                        screenDetails = screenDetails
                    )
                }
            } finally {
                root.recycle()
            }

            updateStatusDisplay()
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        pendingWindowCheck?.let { handler.removeCallbacks(it) }

        val checkWindow = Runnable {
            val activeRoot = rootInActiveWindow
            val activePackage = activeRoot?.packageName?.toString()

            try {
                activeRoot?.recycle()
            } catch (_: Exception) {
            }

            when {
                activePackage == TARGET_PACKAGE -> {
                    targetForeground = true
                    dispatchFloatingBar?.show()
                    screenReadSuccess = false
                    showFullOverlay()
                    hideOverlay()
                }

                activePackage == null -> {
                }

                activePackage == ownPackage -> {
                    targetForeground = false
                    dispatchFloatingBar?.hide()
                    screenReadSuccess = false
                    hideFullOverlay()
                    hideOverlay()
                }

                else -> {
                    targetForeground = false
                    dispatchFloatingBar?.hide()
                    screenReadSuccess = false
                    if (isRunning()) showOverlay() else hideOverlay()
                    hideFullOverlay()
                }
            }

            pendingWindowCheck = null
            updateStatusDisplay()
        }

        pendingWindowCheck = checkWindow
        handler.postDelayed(checkWindow, 100L)
    }
    onServiceConnected()
    * 로그인 후 배민커넥트 위에 남는 빈 투명 전체 레이어.
    * FLAG_NOT_TOUCHABLE로 화면 터치를 가로채지 않고 배민커넥트에 통과시킨다.
    * 실제 조작은 배민커넥트 원래 화면에서 수행하며, 홈 화면 보조창은 별도 유지한다.
    */
    private fun showTransparentFullOverlay() {
        if (fullOverlayView != null) hideFullOverlay()

        val transparentLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        // 오늘 / 주간 지표를 한 줄에 좌우로 나란히 배치한다.
        // 각 영역을 동일 너비로 고정해 텍스트와 테두리가 겹치지 않게 한다.
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        fun metricBox(initialText: String): TextView = TextView(this).apply {
            text = initialText
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            isClickable = false
            isFocusable = false
            maxLines = 1
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(
                9, 15, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

        val metrics = metricBox("오늘 0건  0원")
        val weeklyMetrics = metricBox("주간 0건  0원")

        metricsRow.addView(metrics, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            rightMargin = dp(3)
        })
        metricsRow.addView(weeklyMetrics, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
            leftMargin = dp(3)
        })

        val metricsParams = FrameLayout.LayoutParams(
            dp(300), dp(34), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = 495 }
        transparentLayer.addView(metricsRow, metricsParams)
        todayMetricsView = metrics
        weeklyMetricsView = weeklyMetrics

        addFullOverlay(
            transparentLayer,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            Gravity.TOP or Gravity.START
        )

        // 로그인 전에 이미 읽어 둔 최신 화면 정보가 있다면 즉시 표시
        updateTodayMetrics(
            preferences.getString("latest_screen_text", "").orEmpty()
        )
    }

    /** 오늘 완료 건수와 오늘 수입 추출 */
    private fun updateTodayMetrics(screenText: String) {
        if (preferences.getString("selected_earnings_period", "today") != "today") {
            return
        }
        if (
            !screenText.contains("오늘") ||
            !screenText.contains("완료건수") ||
            !screenText.contains("수입")
        ) return

        val count = Regex("""([0-9][0-9,]*)\s*건""")
            .find(screenText)?.groupValues?.get(1) ?: return

        val income = Regex("""([0-9][0-9,]*)\s*원""")
            .find(screenText)?.groupValues?.get(1) ?: return

        val deliveryCount = count.replace(",", "").toIntOrNull() ?: return
        val deliveryFee = income.replace(",", "").toLongOrNull() ?: return

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val formatter = NumberFormat.getIntegerInstance(Locale.KOREA)

        earningsDatabase.updateBaseFromScreen(
            date,
            deliveryCount,
            deliveryFee
        )

        todayMetricsView?.text =
            "오늘 ${deliveryCount}건    ${formatter.format(deliveryFee)}원"

        val weekly = earningsDatabase.getWeeklySummary(date)

        weeklyMetricsView?.text =
            "주간 ${weekly.deliveryCount}건    ${formatter.format(weekly.totalIncome)}원"
    }

    private fun pxToDp(value: Int): Int {
        return (value / resources.displayMetrics.density).toInt()
    }

    private fun fullText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }

    private fun updateHighestCallOverlay(
        infos: List<DispatchFloatingBar.DispatchInfo>
    ) {
        if (!::windowManager.isInitialized) return

        val topCall = infos.firstOrNull()

        if (topCall == null) {
            hideHighestCallOverlay()
            return
        }

        val displayText = buildString {
            append("최고 금액 대기콜  ")
            append(topCall.amount)
            if (topCall.pickupAddress.isNotBlank()) {
                append("\n픽업: ")
                append(topCall.pickupAddress)
            }
            if (topCall.deliveryAddress.isNotBlank()) {
                append("\n도착: ")
                append(topCall.deliveryAddress)
            }
        }

        if (highestCallOverlayView != null &&
            highestCallId == topCall.dispatchId
        ) {
            highestCallTextView?.text = displayText
            return
        }

        hideHighestCallOverlay()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 255, 255, 255))
                setStroke(dp(2), Color.rgb(0, 180, 70))
                cornerRadius = dp(9).toFloat()
            }
        }

        val infoText = TextView(this).apply {
            text = displayText
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(5), dp(4), dp(5), dp(8))
        }

        val acceptButton = Button(this).apply {
            text = "이 콜 수락"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(0, 180, 70)
                )
            )
            setOnClickListener {
                acceptDispatchCard(topCall.dispatchId)
            }
        }

        panel.addView(infoText)
        panel.addView(
            acceptButton,
            LinearLayout.LayoutParams(-1, dp(42))
        )

        val params = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(590)
        }

        try {
            windowManager.addView(panel, params)
            highestCallOverlayView = panel
            highestCallTextView = infoText
            highestCallId = topCall.dispatchId
        } catch (_: Exception) {
            highestCallOverlayView = null
            highestCallTextView = null
            highestCallId = null
        }
    }

    private fun hideHighestCallOverlay() {
        val view = highestCallOverlayView
        if (view != null) {
            try {
                if (::windowManager.isInitialized) {
                    windowManager.removeView(view)
                }
            } catch (_: Exception) {
            }
        }

        highestCallOverlayView = null
        highestCallTextView = null
        highestCallId = null
    }
    private fun hideFullOverlay() {
        hideHighestCallOverlay()
        val view = fullOverlayView ?: return
        try { if (::windowManager.isInitialized) windowManager.removeView(view) } catch (_: Exception) { }
        fullOverlayView = null
        fullOverlayParams = null
        fullStatusView = null
        todayMetricsView = null
        weeklyMetricsView = null
    }

    /**
     * 오버레이 표시
     */
    private fun showOverlay() {
        if (!::windowManager.isInitialized || overlayView != null || !isRunning()) return

        val root = FrameLayout(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(9))
            val opacity = preferences.getFloat("overlay_opacity", 0.94f).coerceIn(0.2f, 1f)
            background = GradientDrawable().apply {
                setColor(Color.argb((242 * opacity).toInt(), 20, 25, 34))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(65, 75, 88))
            }
        }

        fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            }
        }

        fun makeTile(label: String, value: TextView): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(8), dp(7), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(34, 42, 54))
                    cornerRadius = dp(10).toFloat()
                }
                addView(makeText(label, 10f, Color.LTGRAY))
                addView(value, LinearLayout.LayoutParams(-1, dp(29)))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = makeText("배달콜 보조앱", 15f, WHITE, true)
        val settingsButton = Button(this).apply {
            text = "⚙"
            textSize = 16f
            isAllCaps = false
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener {
                val sizeBar = android.widget.SeekBar(this@CallAccessibilityService).apply {
                    max = 300
                    progress = (preferences.getInt("overlay_width", 320) - 220).coerceIn(0, 300)
                }
                val opacityBar = android.widget.SeekBar(this@CallAccessibilityService).apply {
                    max = 80
                    progress = ((preferences.getFloat("overlay_opacity", 0.94f) - 0.2f) * 100).toInt().coerceIn(0, 80)
                }
                val settingsRoot = LinearLayout(this@CallAccessibilityService).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(14))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(25, 31, 42))
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), Color.rgb(80, 95, 115))
                    }
                }

                settingsRoot.addView(TextView(this@CallAccessibilityService).apply {
                    text = "보조창 설정"
                    textSize = 18f
                    setTextColor(WHITE)
                    setTypeface(null, Typeface.BOLD)
                })

                settingsRoot.addView(TextView(this@CallAccessibilityService).apply {
                    text = "창 크기"
                    textSize = 13f
                    setTextColor(WHITE)
                    setPadding(0, dp(14), 0, dp(4))
                })
                settingsRoot.addView(sizeBar)

                settingsRoot.addView(TextView(this@CallAccessibilityService).apply {
                    text = "투명도"
                    textSize = 13f
                    setTextColor(WHITE)
                    setPadding(0, dp(12), 0, dp(4))
                })
                settingsRoot.addView(opacityBar)

                val buttonRow = LinearLayout(this@CallAccessibilityService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(12), 0, 0)
                }

                val settingsParams = WindowManager.LayoutParams(
                    dp(300),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                val cancelButton = Button(this@CallAccessibilityService).apply {
                    text = "취소"
                    isAllCaps = false
                    setOnClickListener {
                        try {
                            windowManager.removeView(settingsRoot)
                        } catch (_: Exception) {} finally {
                            settingsOverlayVisible = false
                        }
                    }
                }

                val applyButton = Button(this@CallAccessibilityService).apply {
                    text = "적용"
                    isAllCaps = false
                    setOnClickListener {
                        val width = 220 + sizeBar.progress
                        val opacity = 0.2f + opacityBar.progress / 100f

                        preferences.edit()
                            .putInt("overlay_width", width)
                            .putFloat("overlay_opacity", opacity)
                            .apply()

                        panel.background = GradientDrawable().apply {
                            setColor(Color.argb((242 * opacity).toInt(), 20, 25, 34))
                            cornerRadius = dp(16).toFloat()
                            setStroke(dp(1), Color.rgb(65, 75, 88))
                        }

                        overlayParams?.let { params ->
                            params.width = dp(width)
                            try {
                                windowManager.updateViewLayout(root, params)
                            } catch (_: Exception) {}
                        }

                        try {
                            windowManager.removeView(settingsRoot)
                        } catch (_: Exception) {} finally {
                            settingsOverlayVisible = false
                        }
                    }
                }

                buttonRow.addView(cancelButton)
                buttonRow.addView(applyButton)
                settingsRoot.addView(buttonRow)

                try {
                    settingsOverlayVisible = true
                    windowManager.addView(settingsRoot, settingsParams)
                } catch (_: Exception) {
                    settingsOverlayVisible = false
                    android.widget.Toast.makeText(
                        this@CallAccessibilityService,
                        "설정창을 열 수 없습니다.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        val closeButton = Button(this).apply {
            text = "×"
            textSize = 18f
            isAllCaps = false
            setOnClickListener {
                preferences.edit().putBoolean("monitoring_enabled", false).apply()
                hideOverlay()
            }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        header.addView(closeButton, LinearLayout.LayoutParams(dp(42), dp(40)))
        panel.addView(header)

        val status = makeText(STATUS_NORMAL, 11f, GREEN, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(status)

        val todayValue = makeText("0원", 16f, WHITE, true)
        val weeklyValue = makeText("0원", 16f, WHITE, true)
        val waitingValue = makeText("0개", 16f, Color.rgb(80, 230, 145), true)
        val stateValue = makeText("정상 작동", 13f, GREEN, true)

        fun addTileRow(left: LinearLayout, right: LinearLayout) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(4) })
                addView(right, LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginStart = dp(4) })
            }
            val lp = LinearLayout.LayoutParams(-1, dp(66))
            lp.bottomMargin = dp(7)
            panel.addView(row, lp)
        }

        addTileRow(makeTile("오늘 수익", todayValue), makeTile("주간 수익", weeklyValue))
        addTileRow(makeTile("대기 콜", waitingValue), makeTile("작동 상태", stateValue))

        val phrase = makeText(centerPhrases[phraseIndex], 10f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        panel.addView(phrase)

        val callsTitle = makeText("대기 콜 목록", 12f, WHITE, true).apply {
            setPadding(dp(2), dp(5), 0, dp(5))
        }
        panel.addView(callsTitle)

        val callsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val pageView = makeText("", 10f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(3))
        }
        panel.addView(callsContainer)
        panel.addView(pageView)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val resizeHandle = makeText("창 이동 · 오른쪽 아래 ↘ 크기 조절", 9f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
        }
        panel.addView(resizeHandle)

        root.addView(panel, FrameLayout.LayoutParams(-1, -2))

        val width = preferences.getInt("overlay_width", 320).coerceIn(220, 520)
        val params = WindowManager.LayoutParams(
            dp(width),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        overlayView = root
        overlayPanel = panel
        statusView = status
        phraseView = phrase
        overlayCallsContainer = callsContainer
        overlayPageView = pageView
        overlayParams = params
        dashboardTodayView = todayValue
        dashboardWeeklyView = weeklyValue
        dashboardWaitingView = waitingValue

        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            overlayView = null
            overlayPanel = null
            statusView = null
            phraseView = null
            overlayCallsContainer = null
            overlayPageView = null
            overlayParams = null
            dashboardTodayView = null
            dashboardWeeklyView = null
            dashboardWaitingView = null
            return
        }

        header.setOnTouchListener(createMoveListener())
        resizeHandle.setOnTouchListener(createResizeListener())

        refreshOverlayWaitingCalls()
        renderOverlayWaitingCalls()
        updateDashboardMetrics()

        callsContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    overlaySwipeStartX = event.rawX
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - overlaySwipeStartX
                    if (kotlin.math.abs(dx) > dp(45)) {
                        val pages = ((overlayWaitingCalls.size + 1) / 2).coerceAtLeast(1)
                        overlayCallPage = if (dx < 0) {
                            (overlayCallPage + 1).coerceAtMost(pages - 1)
                        } else {
                            (overlayCallPage - 1).coerceAtLeast(0)
                        }
                        renderOverlayWaitingCalls()
                    }
                    true
                }
                else -> true
            }
        }

        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
        handler.removeCallbacks(phraseRunnable)
        handler.removeCallbacks(blinkRunnable)
        updateStatusDisplay()
    }

    private fun refreshOverlayWaitingCalls() {
        overlayWaitingCalls = try {
            database.getWaitingCalls()
                .filter { it.dispatchId.matches(Regex("""[0-9]{4}[A-Za-z]{4}""")) }
                .sortedByDescending { it.amount.filter(Char::isDigit).toLongOrNull() ?: 0L }
                .map { record ->
                    DispatchFloatingBar.DispatchInfo(
                        dispatchId = record.dispatchId,
                        pickupAddress = record.pickup,
                        deliveryAddress = record.destination,
                        amount = record.amount,
                        distance = record.distance,
                        recordId = record.id
                    )
                }
        } catch (_: Exception) { emptyList() }
        overlayCallPage = overlayCallPage.coerceIn(0, (((overlayWaitingCalls.size + 1) / 2) - 1).coerceAtLeast(0))
    }

    private fun renderOverlayWaitingCalls() {
        val container = overlayCallsContainer ?: return
        val page = overlayPageView
        container.removeAllViews()
        val totalPages = ((overlayWaitingCalls.size + 1) / 2).coerceAtLeast(1)

        if (overlayWaitingCalls.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "현재 대기 중인 배차가 없습니다"
                setTextColor(Color.LTGRAY)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(15), 0, dp(15))
            })
            page?.text = "0 / 0"
            dashboardWaitingView?.text = "0개"
            return
        }

        dashboardWaitingView?.text = "${overlayWaitingCalls.size}개"
        val start = overlayCallPage * 2
        val end = minOf(start + 2, overlayWaitingCalls.size)

        for (index in start until end) {
            val info = overlayWaitingCalls[index]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(34, 42, 54))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(65, 75, 88))
                }
            }
            card.addView(TextView(this).apply {
                text = "${info.amount}   ${info.distance}".trim()
                setTextColor(Color.rgb(80, 230, 145))
                setTypeface(null, Typeface.BOLD)
                textSize = 15f
            })
            if (info.pickupAddress.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "픽업  ${info.pickupAddress}"
                    setTextColor(WHITE)
                    textSize = 10f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (info.deliveryAddress.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "도착  ${info.deliveryAddress}"
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                })
            }
            card.addView(Button(this).apply {
                text = "배차 수락"
                isAllCaps = false
                setOnClickListener { acceptDispatchCard(info.dispatchId) }
            }, LinearLayout.LayoutParams(-1, dp(38)))

            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(7)
            container.addView(card, lp)
        }
        page?.text = "${overlayCallPage + 1} / $totalPages   ·   좌우로 넘기기"
    }

    private fun updateDashboardMetrics() {
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val formatter = NumberFormat.getIntegerInstance(Locale.KOREA)
            val today = earningsDatabase.getDailySummary(date)
            val weekly = earningsDatabase.getWeeklySummary(date)
            dashboardTodayView?.text = formatter.format(today.totalIncome) + "원"
            dashboardWeeklyView?.text = formatter.format(weekly.totalIncome) + "원"
        } catch (_: Exception) {
        }
    }

    private fun createMoveListener(): View.OnTouchListener {

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        return View.OnTouchListener { _, event ->

            val params = overlayParams ?: return@OnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()

                    updateOverlayLayout()
                    true
                }

                else -> false
            }
        }
    }

    private fun createResizeListener(): View.OnTouchListener {

        var startWidth = 0
        var touchX = 0f

        return View.OnTouchListener { _, event ->

            val params = overlayParams ?: return@OnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    touchX = event.rawX
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val changedWidth =
                        startWidth + (event.rawX - touchX).toInt()

                    val newWidth = changedWidth.coerceIn(
                        dp(220),
                        dp(520)
                    )

                    params.width = newWidth

                    preferences.edit()
                        .putInt(
                            "overlay_width",
                            (newWidth / resources.displayMetrics.density).toInt()
                        )
                        .apply()

                    updateOverlayLayout()
                    true
                }

                else -> false
            }
        }
    }

    private fun updateOverlayLayout() {
        val view = overlayView ?: return
        val params = overlayParams ?: return

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    /**
     * 오버레이 숨기기
     */
    private fun hideOverlay() {

        handler.removeCallbacks(phraseRunnable)
        handler.removeCallbacks(blinkRunnable)

        val view = overlayView

        if (view != null && ::windowManager.isInitialized) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayPanel = null
        statusView = null
        phraseView = null
        overlayParams = null
        overlayCallsContainer = null
        overlayPageView = null
        overlayCallPage = 0
        lastStatusText = null
        lastStatusColor = null
    }

    private fun dp(value: Int): Int {
        return (
                value * resources.displayMetrics.density
                ).toInt()
    }

    override fun onInterrupt() {
        screenReadSuccess = false
        updateStatusDisplay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceConnected = false
        hideOverlay()
        hideFullOverlay()
        return super.onUnbind(intent)
    }

    // 홈/다른 앱의 보조창에서 수락을 누르면 배민커넥트를 앞으로 가져온 뒤 실제 버튼을 누른다.
    private fun acceptDispatchCard(cardId: String) {
        if (cardId.isBlank()) return
        if (targetForeground) {
            performAcceptDispatchCard(cardId)
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(launchIntent)
            handler.postDelayed({ retryAcceptDispatchCard(cardId, 0) }, 350L)
        } catch (_: Exception) {
        }
    }

    private fun retryAcceptDispatchCard(cardId: String, attempt: Int) {
        if (targetForeground) {
            performAcceptDispatchCard(cardId)
        } else if (attempt < 12) {
            handler.postDelayed({ retryAcceptDispatchCard(cardId, attempt + 1) }, 250L)
        }
    }

    private fun performAcceptDispatchCard(cardId: String) {
        val root = rootInActiveWindow ?: return
        try { clickAcceptButton(root, cardId) } finally { root.recycle() }
    }

    private fun clickAcceptButton(
        node: AccessibilityNodeInfo,
        cardId: String
    ): Boolean {
        val nodeId = node.viewIdResourceName.orEmpty()

        if (nodeId.endsWith("ai-list-card-accept-$cardId")) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try {
                clickAcceptButton(child, cardId)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    override fun onDestroy() {

        serviceConnected = false

        dispatchFloatingBar?.destroy()
        dispatchFloatingBar = null

        handler.removeCallbacks(cleanupRunnable)
        handler.removeCallbacks(statusRunnable)
        handler.removeCallbacks(phraseRunnable)
        handler.removeCallbacks(blinkRunnable)

        hideOverlay()
        hideFullOverlay()

        try {
            database.close()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}







