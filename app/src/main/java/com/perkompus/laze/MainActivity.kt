package com.perkompus.laze

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateStatus(); handler.postDelayed(this, 1000) }
    }
    private val logFile get() = File(filesDir, LazeService.LOG_FILE)
    private var exportSnapshot: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        exportSnapshot = savedInstanceState?.getByteArray("exportSnapshot")
        val prefs = getSharedPreferences("test_config", MODE_PRIVATE)
        findViewById<EditText>(R.id.videoUrl).setText(prefs.getString("url", ""))
        findViewById<EditText>(R.id.videoMarker).setText(prefs.getString("marker", ""))
        findViewById<Button>(R.id.btnEnableService).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val service = LazeService.instance
            if (service == null) { toast(getString(R.string.enable_service_first)); return@setOnClickListener }
            try {
                fun value(id: Int) = findViewById<EditText>(id).text.toString().trim()
                val config = TestConfig(value(R.id.videoUrl), value(R.id.videoMarker),
                    value(R.id.navigationCount).toIntOrNull() ?: 0,
                    value(R.id.awaySeconds).toIntOrNull() ?: 0,
                    value(R.id.playbackTimeout).toIntOrNull() ?: 0)
                prefs.edit().putString("url", config.videoUrl).putString("marker", config.videoMarker).apply()
                service.startTest(config)
                updateStatus()
            } catch (e: IllegalArgumentException) { toast(e.message ?: getString(R.string.invalid_config)) }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { LazeService.instance?.stopTest(); updateStatus() }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            exportSnapshot = runCatching { AtomicFile(logFile).readFully() }.getOrNull()
            if (exportSnapshot == null) { toast(getString(R.string.no_session)); return@setOnClickListener }
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "laze-session-${System.currentTimeMillis()}.json")
            }, 10)
        }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putByteArray("exportSnapshot", exportSnapshot)
        super.onSaveInstanceState(outState)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10) return
        if (resultCode == RESULT_OK) {
            val bytes = exportSnapshot
            val success = runCatching {
                val uri = requireNotNull(data?.data)
                val stream = requireNotNull(contentResolver.openOutputStream(uri))
                stream.use { it.write(requireNotNull(bytes)) }
            }.isSuccess
            toast(getString(if (success) R.string.export_saved else R.string.export_failed))
        }
        exportSnapshot = null
    }
    private fun updateStatus() {
        val json = runCatching { JSONObject(String(AtomicFile(logFile).readFully(), Charsets.UTF_8)) }.getOrNull()
        val active = json != null && json.optString("state") != "STOP" && LazeService.isRunning
        findViewById<TextView>(R.id.tvServiceStatus).setText(
            if (LazeService.isRunning) R.string.service_status_running else R.string.service_status_stopped)
        findViewById<Button>(R.id.btnStart).isEnabled = LazeService.isRunning && !active
        findViewById<Button>(R.id.btnStop).isEnabled = active
        findViewById<Button>(R.id.btnExport).isEnabled = json != null && !active
        listOf(R.id.videoUrl, R.id.videoMarker, R.id.navigationCount, R.id.awaySeconds, R.id.playbackTimeout)
            .forEach { findViewById<EditText>(it).isEnabled = !active }
        findViewById<TextView>(R.id.tvResult).text = if (json == null) getString(R.string.no_session) else
            getString(R.string.session_result, json.optString("state"), json.optString("outcome"),
                json.optInt("startedPlaybacks"), json.optInt("completedPlaybacks"),
                json.optInt("verifiedNavigations"), json.optString("reason").ifBlank { "—" })
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
