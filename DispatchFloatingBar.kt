package com.delivery.callwatch

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 배민커넥트 화면의 검정색 배차 버튼과, 버튼을 눌렀을 때 열리는 최고금액 콜 창 */
class DispatchFloatingBar(
    private val context: Context,
    private val onAccept: (dispatchId: String) -> Unit
) {
    data class DispatchInfo(
        val dispatchId: String,
        val storeName: String = "",
        val pickupAddress: String = "",
        val deliveryAddress: String = "",
        val amount: String = "",
        val distance: String = "",
        val promotion: String = "",
        val recordId: Long = -1L
    )

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var currentCall: DispatchInfo? = null
    private var attached = false
    private var expanded = false

    private val params = WindowManager.LayoutParams(
        dp(36), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        x = dp(4)
        y = -30
    }

    fun show() {
        if (attached) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val button = TextView(context).apply {
            text = ">"
            textSize = 26f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.BLACK)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { togglePanel() }
        }
        container.addView(button, LinearLayout.LayoutParams(dp(36), dp(36)))
        root = container
        try {
            wm.addView(container, params)
            attached = true
        } catch (_: Exception) {
            root = null
            attached = false
        }
    }

    private fun togglePanel() {
        if (expanded) closeDetails() else openDetails()
    }

    private fun openDetails() {
        val container = root ?: return
        val info = currentCall ?: run {
            // 콜이 없어도 버튼을 눌렀을 때 창이 열려 대기 상태를 확인할 수 있게 한다.
            val emptyPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 10, Color.DKGRAY)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(label("현재 최고 금액 대기콜이 없습니다.", 13f, Color.DKGRAY, true))
            }
            attachPanel(container, emptyPanel)
            return
        }

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 10, Color.DKGRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        details.addView(label("최고 금액 대기콜  ${info.amount}", 16f, Color.BLACK, true))
        if (info.pickupAddress.isNotBlank()) details.addView(label("픽업  ${info.pickupAddress}", 12f, Color.DKGRAY))
        if (info.deliveryAddress.isNotBlank()) details.addView(label("도착  ${info.deliveryAddress}", 12f, Color.DKGRAY))
        if (info.distance.isNotBlank()) details.addView(label("거리  ${info.distance}", 12f, Color.DKGRAY))
        val accept = TextView(context).apply {
            text = "배차 수락"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(22, 139, 82), 8)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { onAccept(info.dispatchId) }
        }
        details.addView(accept, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(7) })
        attachPanel(container, details)
    }

    private fun attachPanel(container: LinearLayout, content: LinearLayout) {
        panel = content
        container.addView(content, LinearLayout.LayoutParams(dp(285), -2).apply {
            leftMargin = dp(6)
        })
        expanded = true
        params.width = dp(350)
        try { wm.updateViewLayout(container, params) } catch (_: Exception) { }
    }

    fun closeDetails() {
        val container = root ?: return
        panel?.let { container.removeView(it) }
        panel = null
        expanded = false
        params.width = dp(58)
        try { wm.updateViewLayout(container, params) } catch (_: Exception) { }
    }

    fun hide() {
        root?.let { try { if (attached) wm.removeView(it) } catch (_: Exception) { } }
        root = null
        panel = null
        attached = false
        expanded = false
    }

    fun update(info: DispatchInfo?) { currentCall = info }

    fun updateCalls(infos: List<DispatchInfo>) {
        currentCall = infos.maxByOrNull { amountNumber(it.amount) }
        if (expanded) {
            closeDetails()
            openDetails()
        }
    }

    fun setCalls(infos: List<DispatchInfo>) = updateCalls(infos)
    fun setEarnings(today: String, weekly: String) = Unit
    fun showAutomatically(info: DispatchInfo) = showAutomatically(listOf(info))
    fun showAutomatically(infos: List<DispatchInfo>) {
        updateCalls(infos)
        show()
        // 신규콜이 와도 창을 강제로 펼치지 않고 검정색 배차 버튼만 유지한다.
    }
    fun expandManually() = openDetails()
    fun destroy() = hide()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun amountNumber(value: String): Long = value.filter { it.isDigit() }.toLongOrNull() ?: 0L
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
