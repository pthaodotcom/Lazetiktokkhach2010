package com.perkompus.laze

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayManager(service: AccessibilityService, onStop: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val stopButton = TextView(service).apply {
        text = service.getString(R.string.stop_test)
        textSize = 14f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(40, 40, 40))
        val padding = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        isClickable = true
        isFocusable = true
        contentDescription = service.getString(R.string.stop_test)
        setOnClickListener { onStop() }
    }
    private var visible = false
    fun show(): Boolean = runCatching {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 48 }
        manager.addView(stopButton, params)
        visible = true
        true
    }.getOrDefault(false)
    fun update(state: String) { stopButton.text = stopButton.context.getString(R.string.stop_test_state, state) }
    fun hide() {
        if (visible) runCatching { manager.removeView(stopButton) }
        visible = false
    }
}
