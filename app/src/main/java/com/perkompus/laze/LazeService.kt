package com.perkompus.laze

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random
import kotlin.math.abs

class LazeService : AccessibilityService() {

    private var isSwiping = false
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()
    private var overlayManager: OverlayManager? = null

    companion object {
        var isRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        overlayManager = OverlayManager(this)
        // Optionally show overlay on start or let user toggle it
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && 
            android.provider.Settings.canDrawOverlays(this)) {
            overlayManager?.show()
        }
        Log.d("LazeService", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        
        // Filter for target apps
        if (packageName == "com.google.android.youtube" || 
            packageName == "com.zhiliaoapp.musically" || 
            packageName == "com.instagram.android") {
                
            val rootNode = rootInActiveWindow ?: return
            checkForProgressBar(rootNode)
        }
    }

    private fun checkForProgressBar(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. Check for RangeInfo (ProgressBar, SeekBar)
        val rangeInfo = node.rangeInfo
        if (rangeInfo != null) {
            val current = rangeInfo.current
            val max = rangeInfo.max
            
            // Heuristic: If we are close to max (> 95%) AND we haven't just swiped
            if (max > 0 && current >= (max * 0.95f) && !isSwiping) {
                Log.d("LazeService", "Video ended (approx). Swiping.")
                performSwipe()
                return true // Stop searching
            }
        }

        // 2. Recursive traversal
        for (i in 0 until node.childCount) {
            if (checkForProgressBar(node.getChild(i))) {
                return true
            }
        }
        return false
    }

    private fun performSwipe() {
        if (isSwiping) return
        isSwiping = true

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        // Randomize start and end points (+/- 15%)
        val startX = (width / 2f) + randomOffset(width * 0.1f)
        val startY = (height * 0.75f) + randomOffset(height * 0.1f)
        val endX = (width / 2f) + randomOffset(width * 0.1f)
        val endY = (height * 0.25f) + randomOffset(height * 0.1f)

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val duration = 250L + random.nextInt(200) // 250 - 450ms

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong()))
            .build()

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("LazeService", "Swipe Completed")
                // Cooldown to prevent double swipes
                handler.postDelayed({ isSwiping = false }, 2000)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                isSwiping = false
            }
        }, null)
    }

    private fun randomOffset(maxOffset: Float): Float {
        return (random.nextFloat() - 0.5f) * 2 * maxOffset
    }

    override fun onInterrupt() {
        isRunning = false
        Log.d("LazeService", "Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        overlayManager?.hide()
    }
}
