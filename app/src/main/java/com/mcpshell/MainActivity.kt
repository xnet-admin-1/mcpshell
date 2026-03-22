package com.mcpshell

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.mcpshell.server.McpSseServer
import com.mcpshell.service.McpForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var scrollView: ScrollView
    private var server: McpSseServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText    = findViewById(R.id.logText)
        toggleBtn  = findViewById(R.id.toggleBtn)
        scrollView = findViewById(R.id.logScroll)

        requestPermissions()

        toggleBtn.setOnClickListener {
            if (McpSseServer.instance?.isAlive == true) stopServer() else startServer()
        }

        appendLog("MCP Shell ready. Tap Start to begin.")
    }

    private fun startServer() {
        val port = 3000
        server = McpSseServer(port) { msg -> runOnUiThread { appendLog(msg) } }
        server!!.start()
        McpSseServer.instance = server

        // Start foreground service to keep alive
        val intent = Intent(this, McpForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        statusText.text = "● Running on port $port"
        statusText.setTextColor(0xFF4CAF50.toInt())
        toggleBtn.text = "Stop Server"
        appendLog("MCP SSE server started on http://localhost:$port/sse")
    }

    private fun stopServer() {
        server?.stop()
        McpSseServer.instance = null
        stopService(Intent(this, McpForegroundService::class.java))

        statusText.text = "○ Stopped"
        statusText.setTextColor(0xFFFF5722.toInt())
        toggleBtn.text = "Start Server"
        appendLog("Server stopped.")
    }

    private fun appendLog(msg: String) {
        logText.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
