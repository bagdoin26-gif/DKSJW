
package com.delivery.callwatch

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("CallWatch", MODE_PRIVATE)
    }

    private lateinit var mainLayout: LinearLayout
    private lateinit var statusText: TextView

    private val navy = Color.rgb(16, 28, 53)
    private val tileColor = Color.rgb(29, 49, 82)
    private val white = Color.WHITE
    private val muted = Color.rgb(170, 184, 208)

    private val colors = linkedMapOf(
        "네이비" to Color.rgb(25, 40, 65),
        "블랙" to Color.BLACK,
        "그레이" to Color.rgb(65, 65, 65),
        "그린" to Color.rgb(25, 75, 55),
        "화이트" to Color.WHITE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLogin()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun makeBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun makeInput(
        hintText: String,
        password: Boolean = false
    ): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 17f
            setSingleLine(true)

            inputType = if (password) {
                InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_NUMBER
            }

            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = makeBackground(
                Color.rgb(245, 247, 248), 12
            )
        }
    }

    override fun onBackPressed() {
        showMain()
    }

    private fun showLogin() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(0, 190, 145),
                    Color.rgb(0, 135, 105)
                )
            )
        }

        val title = TextView(this).apply {
            text = "배민커넥트 보조"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(25), dp(22), dp(25))
            background = makeBackground(white, 20)
        }

        val heading = TextView(this).apply {
            text = "로그인"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 45, 48))
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "보조 계정으로 로그인하세요."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        val id = makeInput("아이디 4자리")
        val pw = makeInput("비밀번호 4자리", true)

        card.addView(heading)
        card.addView(description)
        addSpace(card, 18)
        addField(card, id)
        addField(card, pw)

        root.addView(title)
        addSpace(root, 30)
        root.addView(card)

        val developer = TextView(this).apply {
            text = "개발자: 박상민"
            textSize = 12f
            setTextColor(white)
            gravity = Gravity.CENTER
        }

        addSpace(root, 22)
        root.addView(developer)

        setContentView(root)

        var lastAttempt = ""

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val idValue = id.text.toString()
                val pwValue = pw.text.toString()

                if (idValue.length == 4 && pwValue.length == 4) {
                    val attempt = "$idValue:$pwValue"

                    if (attempt != lastAttempt) {
                        lastAttempt = attempt
                        checkLogin(idValue, pwValue)
                    }
                } else {
                    lastAttempt = ""
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        }

        id.addTextChangedListener(watcher)
        pw.addTextChangedListener(watcher)
    }

    private fun checkLogin(id: String, pw: String) {
        if (id == "0000" && pw == "6079") {
            prefs.edit()
                .putBoolean("logged_in", true)
                .putBoolean("monitoring_enabled", true)
                .apply()

            showMain()
        } else {
            Toast.makeText(
                this,
                "아이디 또는 비밀번호가 일치하지 않습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 메인 메뉴
    private fun showMain() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = false
        }

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(30))
            setBackgroundColor(navy)
        }

        scroll.addView(mainLayout)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "배달 도우미"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        mainLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "메인 메뉴"
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(4), 0, dp(4), dp(24))
        }
        mainLayout.addView(subtitle)

        addMenuRow(
            "🚴", "배차 도우미",
            "📊", "오늘·주간 실적",
            { showAssistantPage() },
            { showFeaturePage("오늘·주간 실적") }
        )

        addMenuRow(
            "📋", "배차 내역",
            "📅", "일별 기록",
            { openHistory() },
            { showFeaturePage("일별 기록") }
        )

        addMenuRow(
            "👁", "화면 인식 상태",
            "⚙", "설정",
            { showRecognitionPage() },
            { showSettingsPage() }
        )

        addSpace(mainLayout, 22)

        val footer = TextView(this).apply {
            text = "배민커넥트 보조"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        mainLayout.addView(footer)
    }

    private fun addMenuRow(
        icon1: String,
        title1: String,
        icon2: String,
        title2: String,
        action1: () -> Unit,
        action2: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            makeMenuTile(icon1, title1, action1),
            LinearLayout.LayoutParams(0, dp(145), 1f).apply {
                rightMargin = dp(6)
                bottomMargin = dp(12)
            }
        )

        row.addView(
            makeMenuTile(icon2, title2, action2),
            LinearLayout.LayoutParams(0, dp(145), 1f).apply {
                leftMargin = dp(6)
                bottomMargin = dp(12)
            }
        )

        mainLayout.addView(row)
    }

    private fun makeMenuTile(
        icon: String,
        title: String,
        action: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = makeBackground(tileColor, 18)
            isClickable = true
            isFocusable = true

            val iconView = TextView(this@MainActivity).apply {
                text = icon
                textSize = 32f
                gravity = Gravity.CENTER
            }

            val titleView = TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(white)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }

            addView(iconView)
            addView(titleView)

            setOnClickListener { action() }
        }
    }

    // 배차 도우미
    private fun showAssistantPage() {
        createSubPage("배차 도우미")

        addPageText(
            "배차 보조 기능 실행 상태를 관리합니다.",
            muted
        )

        statusText = addPageText("", white, 19f, true)

        addPageButton("시작") {
            prefs.edit()
                .putBoolean("logged_in", true)
                .putBoolean("monitoring_enabled", true)
                .apply()
            updateStatus()
        }

        addPageButton("정지") {
            prefs.edit()
                .putBoolean("monitoring_enabled", false)
                .apply()
            updateStatus()
        }

        addPageButton("배차 내역 보기") {
            openHistory()
        }

        updateStatus()
    }

    // 배차 내역
    private fun openHistory() {
        try {
            startActivity(
                Intent(this, CallHistoryActivity::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "배차 내역 화면을 열 수 없습니다. Manifest 등록을 확인하세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 화면 인식 상태
    private fun showRecognitionPage() {
        createSubPage("화면 인식 상태")

        val running = prefs.getBoolean(
            "monitoring_enabled", false
        )

        val status = if (running) {
            "정상 작동 중"
        } else {
            "정보 수집 확인 필요"
        }

        val statusView = addPageText(
            status,
            if (running) Color.rgb(60, 210, 130)
            else Color.rgb(255, 90, 90),
            20f,
            true
        )

        addPageText(
            "배민커넥트 접근성 서비스 상태를 확인하려면 접근성 설정을 열어주세요.",
            muted
        )

        addPageButton("접근성 설정 열기") {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        }

        addPageButton("상태 새로고침") {
            val nowRunning = prefs.getBoolean(
                "monitoring_enabled", false
            )
            statusView.text = if (nowRunning) {
                "정상 작동 중"
            } else {
                "정보 수집 확인 필요"
            }
            statusView.setTextColor(
                if (nowRunning) Color.rgb(60, 210, 130)
                else Color.rgb(255, 90, 90)
            )
        }
    }

    // 오늘·주간 실적 및 날짜별 수입 기록
    private val earningsDb by lazy { DailyEarningsDatabase(this) }

    private fun dateKey(calendar: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(calendar.time)

    private fun parseDateKey(value: String): Calendar =
        Calendar.getInstance().apply {
            time = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).parse(value) ?: Date()
        }

    private fun won(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.KOREA).format(value) + "원"

    private fun showFeaturePage(title: String) {
        if (title == "오늘·주간 실적") showPerformancePage()
        else showDailyRecordsPage()
    }

    private fun showPerformancePage() {
        createSubPage("오늘·주간 실적")
        val todayKey = dateKey(Calendar.getInstance())
        val today = earningsDb.getDailySummary(todayKey)
        val week = earningsDb.getWeeklySummary(todayKey)

        addPageText("오늘 실적", white, 20f, true)
        addPageText("완료 건수  ${today.deliveryCount}건", Color.rgb(70, 220, 150), 19f, true)
        addPageText("배달 수입  ${won(today.deliveryFee)}", white, 16f)
        addPageText("미션비  ${won(today.mission)}   ·   프로모션비  ${won(today.promotion)}", muted, 14f)
        addPageText("오늘 총수익  ${won(today.totalIncome)}", white, 22f, true)

        addPageText("이번 주 실적 (수요일 ~ 화요일)", white, 19f, true)
        addPageText("${week.startDate} ~ ${week.endDate}", muted, 13f)
        addPageText("주간 완료 건수  ${week.deliveryCount}건", Color.rgb(70, 220, 150), 20f, true)
        addPageText("주간 총수익  ${won(week.totalIncome)}", white, 23f, true)
        addPageText("배달 ${won(week.deliveryFee)}  /  미션 ${won(week.mission)}  /  프로모션 ${won(week.promotion)}", muted, 13f)

        addPageText("요일별 수익 그래프", white, 18f, true)
        val start = parseDateKey(week.startDate)
        val summaries = (0..6).map { offset ->
            val day = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            earningsDb.getDailySummary(dateKey(day))
        }
        val maxIncome = (summaries.maxOfOrNull { it.totalIncome } ?: 0L).coerceAtLeast(1L)
        val dayNames = listOf("수", "목", "금", "토", "일", "월", "화")
        summaries.forEachIndexed { index, summary ->
            addPageText("${dayNames[index]}요일  ·  ${summary.deliveryCount}건  ·  ${won(summary.totalIncome)}", white, 13f)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = ((summary.totalIncome * 100L) / maxIncome).toInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 190, 145))
            }
            mainLayout.addView(bar, LinearLayout.LayoutParams(-1, dp(12)).apply {
                bottomMargin = dp(8)
            })
        }
        addPageButton("일별 기록 / 달력") { showDailyRecordsPage() }
    }

    private fun showDailyRecordsPage(initialDate: String = dateKey(Calendar.getInstance())) {
        var selectedDate = initialDate
        val month = parseDateKey(initialDate).apply { set(Calendar.DAY_OF_MONTH, 1) }

        createSubPage("일별 기록 · 달력")
        val monthLabel = addPageText("", white, 19f, true)
        val calendarGrid = GridLayout(this).apply {
            columnCount = 7
            useDefaultMargins = true
        }
        mainLayout.addView(calendarGrid)
        addPageText("선택한 날짜 실적", white, 18f, true)
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = makeBackground(tileColor, 14)
        }
        mainLayout.addView(detail)

        lateinit var refreshDetail: () -> Unit
        lateinit var refreshCalendar: () -> Unit

        refreshDetail = {
            detail.removeAllViews()
            val summary = earningsDb.getDailySummary(selectedDate)
            fun detailLine(value: String, size: Float = 14f) {
                detail.addView(TextView(this).apply {
                    text = value
                    textSize = size
                    setTextColor(white)
                    setPadding(dp(3), dp(5), dp(3), dp(5))
                })
            }
            detailLine(selectedDate, 16f)
            detailLine("완료 건수: ${summary.deliveryCount}건")
            detailLine("배달 수입: ${won(summary.deliveryFee)}")
            detailLine("미션비: ${won(summary.mission)}")
            detailLine("프로모션비: ${won(summary.promotion)}")
            detailLine("총수익: ${won(summary.totalIncome)}", 19f)

            earningsDb.getExtras(selectedDate).forEach { entry ->
                detailLine("${entry.type} · ${won(entry.amount)} ${entry.memo}")
            }
            val baseButton = Button(this).apply {
                text = "이 날짜 배달 건수 / 금액 입력"
                setOnClickListener {
                    showBaseEntryDialog(selectedDate) {
                        refreshDetail()
                        refreshCalendar()
                    }
                }
            }
            val missionButton = Button(this).apply {
                text = "＋ 미션비 추가"
                setOnClickListener {
                    showExtraEntryDialog(selectedDate, DailyEarningsDatabase.TYPE_MISSION) {
                        refreshDetail()
                        refreshCalendar()
                    }
                }
            }
            val promoButton = Button(this).apply {
                text = "＋ 프로모션비 추가"
                setOnClickListener {
                    showExtraEntryDialog(selectedDate, DailyEarningsDatabase.TYPE_PROMOTION) {
                        refreshDetail()
                        refreshCalendar()
                    }
                }
            }
            detail.addView(baseButton)
            detail.addView(missionButton)
            detail.addView(promoButton)
        }

        refreshCalendar = {
            calendarGrid.removeAllViews()
            monthLabel.text = SimpleDateFormat("yyyy년 M월", Locale.KOREA).format(month.time)
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { label ->
                calendarGrid.addView(TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(muted)
                    textSize = 12f
                }, GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(30)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                })
            }
            val firstOffset = month.get(Calendar.DAY_OF_WEEK) - 1
            val totalDays = month.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (slot in 0 until firstOffset + totalDays) {
                if (slot < firstOffset) {
                    calendarGrid.addView(View(this), GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(58)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    })
                } else {
                    val dayNumber = slot - firstOffset + 1
                    val dayCalendar = (month.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, dayNumber)
                    }
                    val key = dateKey(dayCalendar)
                    val summary = earningsDb.getDailySummary(key)
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(3), dp(2), dp(3))
                        background = makeBackground(
                            if (key == selectedDate) Color.rgb(0, 125, 95) else tileColor, 8
                        )
                        setOnClickListener {
                            selectedDate = key
                            refreshDetail()
                            refreshCalendar()
                        }
                    }
                    cell.addView(TextView(this).apply {
                        text = dayNumber.toString()
                        gravity = Gravity.CENTER
                        setTextColor(white)
                        textSize = 13f
                    })
                    if (summary.deliveryCount > 0 || summary.totalIncome > 0) {
                        cell.addView(TextView(this).apply {
                            text = if (summary.totalIncome > 0) "${summary.totalIncome / 1000}천" else "${summary.deliveryCount}건"
                            gravity = Gravity.CENTER
                            setTextColor(Color.rgb(100, 230, 180))
                            textSize = 9f
                        })
                    }
                    calendarGrid.addView(cell, GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(58)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
            }
        }

        val monthButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val previous = Button(this).apply {
            text = "◀ 이전 달"
            setOnClickListener {
                month.add(Calendar.MONTH, -1)
                refreshCalendar()
            }
        }
        val next = Button(this).apply {
            text = "다음 달 ▶"
            setOnClickListener {
                month.add(Calendar.MONTH, 1)
                refreshCalendar()
            }
        }
        monthButtons.addView(previous, LinearLayout.LayoutParams(0, -2, 1f))
        monthButtons.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        mainLayout.addView(monthButtons, 2)
        addPageButton("날짜 직접 선택") {
            DatePickerDialog(
                this,
                { _, year, monthIndex, day ->
                    val chosen = Calendar.getInstance().apply {
                        set(year, monthIndex, day)
                    }
                    selectedDate = dateKey(chosen)
                    month.time = chosen.time
                    month.set(Calendar.DAY_OF_MONTH, 1)
                    refreshCalendar()
                    refreshDetail()
                },
                month.get(Calendar.YEAR),
                month.get(Calendar.MONTH),
                parseDateKey(selectedDate).get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        refreshCalendar()
        refreshDetail()
    }

    private fun showBaseEntryDialog(date: String, onSaved: () -> Unit) {
        val current = earningsDb.getDailySummary(date)
        val baseCount = current.deliveryCount
        val countInput = EditText(this).apply {
            hint = "완료 건수"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(baseCount.toString())
        }
        val amountInput = EditText(this).apply {
            hint = "배달 수입 (원)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.deliveryFee.toString())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(countInput)
            addView(amountInput)
        }
        AlertDialog.Builder(this)
            .setTitle("${date} 배달 실적 입력")
            .setView(fields)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val count = countInput.text.toString().toIntOrNull()
                val amount = amountInput.text.toString().toLongOrNull()
                if (count == null || amount == null || count < 0 || amount < 0) {
                    Toast.makeText(this, "건수와 금액을 올바르게 입력하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    earningsDb.updateBaseFromScreen(date, count, amount)
                    onSaved()
                }
            }.show()
    }

    private fun showExtraEntryDialog(date: String, type: String, onSaved: () -> Unit) {
        val amountInput = EditText(this).apply {
            hint = "${type} 금액 (원)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val memoInput = EditText(this).apply { hint = "메모 (선택)" }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(amountInput)
            addView(memoInput)
        }
        AlertDialog.Builder(this)
            .setTitle("${date} ${type} 추가")
            .setView(fields)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가") { _, _ ->
                val amount = amountInput.text.toString().toLongOrNull()
                if (amount == null || amount < 0) {
                    Toast.makeText(this, "금액을 올바르게 입력하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    // 미션비와 프로모션비는 금액만 기록하며 완료 건수에는 포함하지 않습니다.
                    earningsDb.addExtra(date, type, amount, memoInput.text.toString())
                    onSaved()
                }
            }.show()
    }

    // 설정
    private fun showSettingsPage() {
        createSubPage("설정")

        addPageText("전체화면 배경 색상", white, 17f, true)

        colors.forEach { (name, color) ->
            addPageButton(name) {
                prefs.edit()
                    .putInt("overlay_bg_color", color)
                    .apply()

                Toast.makeText(
                    this,
                    "$name 배경으로 설정했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        addPageText("배경 투명도", white, 17f, true)

        val opacity = SeekBar(this).apply {
            max = 90
            progress = prefs.getInt("overlay_opacity", 80) - 10
        }
        mainLayout.addView(opacity)

        val opacityText = addPageText(
            "${opacity.progress + 10}%",
            white
        )

        opacity.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val value = progress + 10
                    opacityText.text = "${value}%"

                    prefs.edit()
                        .putInt("overlay_opacity", value)
                        .apply()
                }

                override fun onStartTrackingTouch(
                    seekBar: SeekBar?
                ) {}

                override fun onStopTrackingTouch(
                    seekBar: SeekBar?
                ) {}
            }
        )

        addPageButton("접근성 설정") {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        }

        addPageButton("다른 앱 위에 표시 설정") {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "설정을 열 수 없습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        addPageButton("아이디 / 비밀번호 변경") {
            showChangeAccount()
        }

        addPageButton("로그아웃") {
            prefs.edit()
                .putBoolean("logged_in", false)
                .putBoolean("monitoring_enabled", false)
                .apply()

            showLogin()
        }
    }

    private fun createSubPage(title: String) {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(navy)
            isFillViewport = true
        }

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(30))
            setBackgroundColor(navy)
        }

        scroll.addView(mainLayout)
        setContentView(scroll)

        addPageButton("← 메인 메뉴") {
            showMain()
        }

        addPageText(title, white, 24f, true)
    }

    private fun addPageText(
        text: String,
        color: Int,
        size: Float = 15f,
        bold: Boolean = false
    ): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(5), dp(10), dp(5), dp(10))
        }
        mainLayout.addView(view)
        return view
    }

    private fun addPageButton(
        text: String,
        action: () -> Unit
    ) {
        val button = TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(16), dp(14), dp(16))
            background = makeBackground(tileColor, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = dp(10)
        mainLayout.addView(button, params)
    }

    private fun updateStatus() {
        if (!::statusText.isInitialized) return

        val running = prefs.getBoolean(
            "monitoring_enabled", false
        )

        statusText.text = if (running) {
            "정상 작동 중"
        } else {
            "정보 수집 확인 필요"
        }

        statusText.setTextColor(
            if (running) Color.rgb(60, 210, 130)
            else Color.rgb(255, 90, 90)
        )
    }

    private fun showChangeAccount() {
        val id = makeInput("새 아이디 4자리")
        id.setText(prefs.getString("account_id", ""))

        val pw = makeInput("새 비밀번호 4자리", true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(25), dp(10), dp(25), dp(10))
            addView(id)
            addView(pw)
        }

        AlertDialog.Builder(this)
            .setTitle("계정 변경")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val idValue = id.text.toString()
                val pwValue = pw.text.toString()

                if (idValue.length == 4 &&
                    pwValue.length == 4
                ) {
                    prefs.edit()
                        .putString("account_id", idValue)
                        .putString("account_password", pwValue)
                        .putBoolean("logged_in", false)
                        .putBoolean("monitoring_enabled", false)
                        .apply()

                    showLogin()
                } else {
                    Toast.makeText(
                        this,
                        "아이디와 비밀번호를 각각 4자리로 입력하세요.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int
    ) {
        parent.addView(
            View(this),
            LinearLayout.LayoutParams(1, dp(height))
        )
    }

    private fun addField(
        parent: LinearLayout,
        field: EditText
    ) {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        )
        params.bottomMargin = dp(12)
        parent.addView(field, params)
    }
}
