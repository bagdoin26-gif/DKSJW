
package com.delivery.callwatch

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallHistoryActivity : Activity() {

    private lateinit var database: CallDatabase
    private lateinit var listLayout: LinearLayout
    private lateinit var summaryText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshRecords()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = CallDatabase(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(245, 247, 248))
        }

        val title = TextView(this).apply {
            text = "대기배차 기록"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 12)
        }
        root.addView(title)

        summaryText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 12)
        }
        root.addView(summaryText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val refreshButton = Button(this).apply {
            text = "새로고침"
            setOnClickListener { refreshRecords() }
        }

        val deleteButton = Button(this).apply {
            text = "전체 삭제"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@CallHistoryActivity)
                    .setTitle("기록 전체 삭제")
                    .setMessage("저장된 배차 기록을 모두 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ ->
                        database.deleteAll()
                        refreshRecords()
                    }
                    .show()
            }
        }

        buttons.addView(
            refreshButton,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        buttons.addView(
            deleteButton,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(buttons)

        val scroll = ScrollView(this)

        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(listLayout)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        refreshRecords()
    }

    private fun refreshRecords() {
        val records = try {
            database.getCalls()
        } catch (e: Exception) {
            summaryText.text = "기록을 불러오지 못했습니다: ${e.message}"
            return
        }

        val waiting = records.count {
            it.status == CallDatabase.STATUS_WAITING
        }
        val accepted = records.count {
            it.status == CallDatabase.STATUS_ACCEPTED
        }
        val rejected = records.count {
            it.status == CallDatabase.STATUS_REJECTED
        }

        summaryText.text =
            "전체 ${records.size}건 | 대기 ${waiting}건 | " +
                    "수락 ${accepted}건 | 거절 ${rejected}건\n" +
                    "대기콜은 최대 2건이며, 기록은 수집 후 30분 보관됩니다."

        listLayout.removeAllViews()

        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                text = "저장된 배차 기록이 없습니다."
                textSize = 16f
                setTextColor(Color.GRAY)
                setPadding(8, 30, 8, 30)
                gravity = Gravity.CENTER
            }
            listLayout.addView(empty)
            return
        }

        records.forEach { record ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 14, 14, 14)
                setBackgroundColor(Color.WHITE)
            }

            val amount = TextView(this).apply {
                text = "배달 금액: ${record.amount.ifBlank { "미확인" }}"
                textSize = 19f
                setTextColor(Color.BLACK)
            }
            card.addView(amount)

            addLine(card, "상태", record.status)
            addLine(card, "배차 ID", record.dispatchId.ifBlank { "미확인" })
            addLine(card, "픽업지", record.pickup.ifBlank { "미확인" })
            addLine(card, "도착지", record.destination.ifBlank { "미확인" })
            addLine(card, "거리", record.distance.ifBlank { "미확인" })

            val timeFormat = SimpleDateFormat(
                "HH:mm:ss",
                Locale.KOREA
            )

            addLine(
                card,
                "수집 시각",
                timeFormat.format(Date(record.capturedAt))
            )

            val remaining =
                CallDatabase.RETENTION_MS -
                (System.currentTimeMillis() - record.capturedAt)

            val remainingText = if (remaining <= 0L) {
                "삭제 대기"
            } else {
                val minutes = remaining / 60_000L
                val seconds = (remaining % 60_000L) / 1_000L
                "${minutes}분 ${seconds}초"
            }

            addLine(card, "30분 보관 남은 시간", remainingText)

            if (record.processedAt != null) {
                addLine(
                    card,
                    "처리 시각",
                    timeFormat.format(Date(record.processedAt))
                )
            }

            val raw = TextView(this).apply {
                text = "원본 화면 정보:\n${record.rawText.ifBlank { "없음" }}"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 8, 0, 0)
            }
            card.addView(raw)

            val margin = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            margin.bottomMargin = 12
            listLayout.addView(card, margin)
        }
    }

    private fun addLine(
        parent: LinearLayout,
        label: String,
        value: String
    ) {
        val text = TextView(this).apply {
            this.text = "$label: $value"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 5, 0, 5)
        }
        parent.addView(text)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        database.close()
        super.onDestroy()
    }
}
