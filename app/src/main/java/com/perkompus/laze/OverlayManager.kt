package com.perkompus.laze

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private val prefs: SharedPreferences = context.getSharedPreferences("LazePrefs", Context.MODE_PRIVATE)

    fun show() {
        if (bubbleView != null) return

        // Create the view programmatically to avoid extra layout files
        val imageView = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble)
            alpha = 0.8f
        }
        bubbleView = imageView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        imageView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val touchSlop = 10

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        
                        if (diffX < touchSlop && diffY < touchSlop) {
                            // Tap detected
                            saveCoordinates(params.x, params.y + v.height) // Save bottom of bubble ? Or center?
                            // Logic: Users place bubble ON the progress bar. So we want the Y of the bubble.
                            // Let's go with Center of the bubble.
                            val centerY = params.y + (v.height / 2)
                            saveCoordinates(params.x, centerY)
                            Toast.makeText(context, "Location Saved: $centerY", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            bubbleView = null
        }
    }

    private fun saveCoordinates(x: Int, y: Int) {
        // In a real scenario, we might want to map this to the CURRENTLY running app.
        // But detecting the foreground app from here requires AccessibilityService or UsageStats.
        // For now, we save to a generic "last_set_coords" or we can try to infer.
        // Actually, the simpler "Slider-Sync" logic might not even NEED exact coordinates 
        // if we just find the node.
        // BUT the requirements said: "Point-to-Map Overlay: ... manually tap to 'anchor' ... Save these (x, y)".
        // So we will save them.
        prefs.edit()
            .putInt("anchor_x", x)
            .putInt("anchor_y", y)
            .apply()
    }
}
