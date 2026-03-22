package com.mcpshell

import android.Manifest
import android.content.Intent
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
import com.mcpshell.shell.ProotBootstrap
import com.mcpshell.shell.RishExecutor

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var shellStatus: TextView
    private lateinit var logText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var setupBtn: Button
    private lateinit var scrollView: ScrollView
    private var server: McpSseServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText  = findViewById(R.id.statusText)
        shellStatus = findViewById(R.id.shellStatus)
        logText     = findViewById(R.id.logText)
        toggleBtn   = findViewById(R.id.toggleBtn)
        setupBtn    = findViewById(R.id.setupBtn)
        scrollView  = findViewById(R.id.logScroll)

        requestPermissions()
        updateShellStatus()

        toggleBtn.setOnClickListener {
            if (McpSseServer.instance?.isAlive == true) stopServer() else startServer()
        }

        setupBtn.setOnClickListener { setupUbuntu() }

        findViewById<Button>(R.id.testBtn).setOnClickListener { runSelfTest() }

        appendLog("MCP Shell ready. Tap Start to begin.")
    }

    private fun startServer() {
        val port = 39811
        server = McpSseServer(port) { msg -> runOnUiThread { appendLog(msg) } }
        server!!.start()
        McpSseServer.instance = server

        val intent = Intent(this, McpForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        statusText.text = "● Running on localhost:$port"
        statusText.setTextColor(0xFF4CAF50.toInt())
        toggleBtn.text = "Stop Server"
        appendLog("MCP Streamable HTTP server started on http://localhost:$port/mcp")
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

    private fun setupUbuntu() {
        setupBtn.isEnabled = false
        setupBtn.text = "Installing..."
        appendLog("─── Ubuntu Setup ───")
        Thread {
            val ok = ProotBootstrap.setup(this) { msg ->
                runOnUiThread { appendLog(msg) }
            }
            runOnUiThread {
                setupBtn.isEnabled = true
                if (ok) {
                    setupBtn.text = "Ubuntu ✓"
                    appendLog("Ubuntu setup complete!")
                } else {
                    setupBtn.text = "Retry Setup"
                    appendLog("Ubuntu setup failed.")
                }
                updateShellStatus()
            }
        }.start()
    }

    private fun runSelfTest() {
        appendLog("─── Self Test ───")
        Thread {
            fun test(name: String, fn: () -> String) {
                runOnUiThread { appendLog("[$name] running...") }
                val result = try { fn() } catch (e: Exception) { "ERROR: ${e.message}" }
                runOnUiThread { appendLog("[$name] $result") }
            }
            test("sh") { com.mcpshell.shell.ShellExecutor.exec("echo 'Hello from Android shell!'") }
            if (com.mcpshell.shell.ProotBootstrap.isInstalled(this@MainActivity)) {
                test("ubuntu") { com.mcpshell.shell.ProotExecutor.exec(this@MainActivity, "echo 'Hello from Ubuntu shell!'") }
            } else {
                runOnUiThread { appendLog("[ubuntu] skipped (not installed)") }
            }
            if (com.mcpshell.shell.RishExecutor.isShizukuReady()) {
                test("rish") { com.mcpshell.shell.RishExecutor.exec("echo 'Hello from Shizuku!'") }
            } else {
                runOnUiThread { appendLog("[rish] skipped (Shizuku not available)") }
            }
            runOnUiThread { appendLog("─── Done ───") }
        }.start()
    }

    private fun updateShellStatus() {
        val shells = mutableListOf("sh ✓")
        if (ProotBootstrap.isInstalled(this)) shells.add("ubuntu ✓")
        else shells.add("ubuntu ✗")
        if (RishExecutor.isShizukuReady()) shells.add("rish ✓")
        else shells.add("rish ✗")
        shellStatus.text = "Shells: ${shells.joinToString("  ")}"

        setupBtn.text = if (ProotBootstrap.isInstalled(this)) "Ubuntu ✓" else "Setup Ubuntu"
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
