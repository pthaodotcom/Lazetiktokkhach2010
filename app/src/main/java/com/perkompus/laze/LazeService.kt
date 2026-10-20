package com.perkompus.laze

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LazeService : AccessibilityService() {
    companion object {
        var instance: LazeService? = null
            private set
        val isRunning get() = instance != null
        const val LOG_FILE = "latest-session.json"
    }
    private val handler = Handler(Looper.getMainLooper())
    private val reader = TikTokScreenReader()
    private var session: TestSession? = null
    private var overlay: OverlayManager? = null
    private var events = JSONArray()
    private var sessionId = ""
    private var startedAt = 0L
    private var generation = 0
    private var logFailed = false
    private var lastSample: ScreenSample? = null
    private val poll = object : Runnable {
        override fun run() {
            val running = session ?: return
            if (!running.active) return
            try {
                val sample = readScreen(running)
                lastSample = sample
                act(running.observe(sample, SystemClock.elapsedRealtime()))
            } catch (e: Exception) {
                running.fail("SCREEN_READ_FAILED", SystemClock.elapsedRealtime())
                Log.e("Laze", "Screen read failed", e)
            }
            if (running.active) handler.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Reconnecting never resumes gestures after a killed process.
        val file = File(filesDir, LOG_FILE)
        runCatching {
            if (file.exists()) {
                val json = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
                if (json.optString("state") != "STOP") {
                    json.put("state", "STOP").put("outcome", "STOPPED").put("reason", "PROCESS_INTERRUPTED")
                    writeLog(json)
                }
            }
        }.onFailure { Log.e("Laze", "Cannot recover previous log", it) }
    }

    fun startTest(config: TestConfig) {
        if (session?.active == true) return
        generation++
        handler.removeCallbacks(poll)
        logFailed = false
        events = JSONArray()
        lastSample = null
        sessionId = UUID.randomUUID().toString()
        startedAt = System.currentTimeMillis()
        val running = TestSession(config) { state, code ->
            val event = JSONObject().put("atMs", System.currentTimeMillis()).put("state", state.name).put("event", code)
            lastSample?.let { sample ->
                event.put("screen", JSONObject().put("isTikTok", sample.isTikTok).put("targetMatched", sample.targetMatched)
                    .put("identity", sample.identity ?: JSONObject.NULL).put("progress", sample.progress ?: JSONObject.NULL)
                    .put("blocker", sample.blocker ?: JSONObject.NULL))
            }
            events.put(event)
            persist()
            overlay?.update(state.name)
            if (state == TestState.STOP) {
                generation++
                handler.removeCallbacks(poll)
                overlay?.hide()
                if (logFailed) android.widget.Toast.makeText(this, R.string.log_write_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        session = running
        running.start(SystemClock.elapsedRealtime())
        if (logFailed) { running.fail("LOG_WRITE_FAILED", SystemClock.elapsedRealtime()); return }
        overlay = OverlayManager(this) { stopTest() }
        if (overlay?.show() != true) { running.fail("STOP_CONTROL_UNAVAILABLE", SystemClock.elapsedRealtime()); return }
        val installed = runCatching { packageManager.getPackageInfo(TikTokScreenReader.TIKTOK_PACKAGE, 0) }.isSuccess
        try { act(running.checked(installed, SystemClock.elapsedRealtime())) }
        catch (e: Exception) { running.fail("ACTION_FAILED", SystemClock.elapsedRealtime()) }
        if (running.active) handler.postDelayed(poll, 500)
    }

    fun stopTest() { session?.stop(SystemClock.elapsedRealtime()) }

    private fun readScreen(running: TestSession): ScreenSample {
        val root = rootInActiveWindow
        return try {
            reader.read(root, running.config.videoMarker,
                resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        } finally { @Suppress("DEPRECATION") root?.recycle() }
    }

    private fun act(action: TestAction) {
        val running = session ?: return
        if (!running.active) return
        if (logFailed) { running.fail("LOG_WRITE_FAILED", SystemClock.elapsedRealtime()); return }
        when (action) {
            TestAction.OPEN_VIDEO -> {
                val success = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(running.config.videoUrl))
                        .setPackage(TikTokScreenReader.TIKTOK_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                running.opened(success, SystemClock.elapsedRealtime())
            }
            TestAction.SWIPE -> {
                val sample = readScreen(running)
                if (!sample.isTikTok || sample.blocker != null || sample.progress == null ||
                    sample.identity != running.expectedVideoIdentity) {
                    running.fail("UNSAFE_SCREEN_BEFORE_GESTURE", SystemClock.elapsedRealtime())
                    return
                }
                val metrics = resources.displayMetrics
                val path = Path().apply {
                    moveTo(metrics.widthPixels * 0.5f, metrics.heightPixels * 0.75f)
                    lineTo(metrics.widthPixels * 0.5f, metrics.heightPixels * 0.3f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build()
                val token = generation
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (generation == token) running.gestureFinished(true, SystemClock.elapsedRealtime())
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (generation == token) running.gestureFinished(false, SystemClock.elapsedRealtime())
                    }
                }, handler)
                if (!accepted) running.gestureFinished(false, SystemClock.elapsedRealtime())
            }
            TestAction.NONE -> Unit
        }
    }

    private fun persist() {
        val running = session ?: return
        val json = JSONObject().put("schemaVersion", 1).put("sessionId", sessionId).put("startedAtMs", startedAt)
            .put("updatedAtMs", System.currentTimeMillis()).put("state", running.state.name)
            .put("outcome", running.outcome).put("reason", running.reason)
            .put("config", JSONObject().put("videoUrl", running.config.videoUrl).put("videoMarker", running.config.videoMarker)
                .put("navigationCount", running.config.navigationCount).put("awaySeconds", running.config.awaySeconds)
                .put("playbackTimeoutSeconds", running.config.playbackTimeoutSeconds))
            .put("startedPlaybacks", running.startedPlaybacks).put("completedPlaybacks", running.completedPlaybacks)
            .put("completionEvidence", running.completionEvidence ?: JSONObject.NULL)
            .put("verifiedNavigations", running.verifiedNavigations).put("returnedToTarget", running.returnedToTarget)
            .put("analyticsViews", JSONObject.NULL).put("events", events)
        try { writeLog(json) } catch (e: Exception) {
            logFailed = true
            Log.e("Laze", "Cannot save session", e)
        }
    }

    private fun writeLog(json: JSONObject) {
        writeAtomic(File(filesDir, LOG_FILE), json)
        if (json.optString("state") == "STOP") {
            val directory = File(filesDir, "sessions").apply { mkdirs() }
            val id = json.optString("sessionId").takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return
            writeAtomic(File(directory, "$id.json"), json)
        }
    }

    private fun writeAtomic(target: File, json: JSONObject) {
        val file = AtomicFile(target)
        val stream = file.startWrite()
        try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { session?.stop(SystemClock.elapsedRealtime(), "SERVICE_INTERRUPTED") }
    override fun onDestroy() {
        session?.stop(SystemClock.elapsedRealtime(), "SERVICE_DESTROYED")
        generation++
        handler.removeCallbacksAndMessages(null)
        overlay?.hide()
        instance = null
        super.onDestroy()
    }
}
