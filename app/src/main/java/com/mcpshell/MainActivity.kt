package com.mcpshell

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

import com.mcpshell.server.McpSseServer
import com.mcpshell.service.McpForegroundService
import com.mcpshell.shell.ProotBootstrap
import com.mcpshell.shell.ProotExecutor
import com.mcpshell.shell.RishExecutor

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var shellStatus: TextView
    private lateinit var logText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var setupBtn: Button
    private lateinit var shizukuBtn: Button
    private lateinit var scrollView: ScrollView
    // ScrollView removed - using scrollable TextView instead
    private var server: McpSseServer? = null

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            runOnUiThread {
                appendLog(if (granted) "Shizuku permission granted" else "Shizuku permission denied")
                updateShellStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText  = findViewById(R.id.statusText)
        shellStatus = findViewById(R.id.shellStatus)
        logText     = findViewById(R.id.logText)
        toggleBtn   = findViewById(R.id.toggleBtn)
        setupBtn    = findViewById(R.id.setupBtn)
        shizukuBtn  = findViewById(R.id.shizukuBtn)
        scrollView  = findViewById(R.id.logScroll)

        // Enable text selection and scrolling for the log text view
        logText.setTextIsSelectable(true)
        logText.setMovementMethod(ScrollingMovementMethod.getInstance())
        logText.setHorizontallyScrolling(true)
        logText.setMaxLines(1000)

        requestPermissions()
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        updateShellStatus()

        toggleBtn.setOnClickListener {
            if (McpSseServer.instance?.isAlive == true) stopServer() else startServer()
        }

        setupBtn.setOnClickListener { setupUbuntu() }

        findViewById<Button>(R.id.testBtn).setOnClickListener { runSelfTest() }

        findViewById<Button>(R.id.shizukuBtn).setOnClickListener { connectShizuku() }

        findViewById<Button>(R.id.updateBtn).setOnClickListener { runProotCommand("Update", "apt-get update -qq && apt-get upgrade -y -qq -o Dpkg::Options::=--force-unsafe-io") }
        findViewById<Button>(R.id.fixDpkgBtn).setOnClickListener { runProotCommand("Fix dpkg", "dpkg --configure -a --force-unsafe-io && apt-get install -f -y -qq") }
        findViewById<Button>(R.id.cancelBtn).setOnClickListener {
            ProotExecutor.cancel()
            appendLog("⚡ Cancelled")
            setProotButtonsEnabled(true)
        }

        findViewById<Button>(R.id.copyLogsBtn).setOnClickListener {
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("MCP Shell Log", logText.text))
            android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clearLogsBtn).setOnClickListener {
            logText.text = ""
        }

        appendLog("MCP Shell ready. Tap Start to begin.")
    }

    override fun onDestroy() {
        super.onDestroy()
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun startServer() {
        val port = 39811
        val bind = "127.0.0.1"
        server = McpSseServer(port, bind) { msg -> runOnUiThread { appendLog(msg) } }
        server!!.start()
        McpSseServer.instance = server

        val intent = Intent(this, McpForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        statusText.text = "● Running on $bind:$port"
        statusText.setTextColor(0xFF4CAF50.toInt())
        toggleBtn.text = "Stop Server"
        appendLog("MCP Streamable HTTP server started on http://$bind:$port/mcp")
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

    private fun runProotCommand(label: String, command: String) {
        if (!ProotBootstrap.isInstalled(this)) {
            appendLog("Ubuntu not installed. Run Setup first.")
            return
        }
        setProotButtonsEnabled(false)
        appendLog("─── $label ───")
        Thread {
            val result = ProotExecutor.exec(this, command, timeoutMs = 120_000)
            runOnUiThread {
                appendLog(result)
                appendLog("─── $label done ───")
                setProotButtonsEnabled(true)
            }
        }.start()
    }

    private fun setProotButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.updateBtn).isEnabled = enabled
        findViewById<Button>(R.id.fixDpkgBtn).isEnabled = enabled
        setupBtn.isEnabled = enabled
    }

    private fun connectShizuku() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                appendLog("Shizuku is not running. Start Shizuku app first.")
                return
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                appendLog("Shizuku already connected.")
                updateShellStatus()
                return
            }
            appendLog("Requesting Shizuku permission...")
            rikka.shizuku.Shizuku.requestPermission(0)
        } catch (e: Exception) {
            appendLog("Shizuku error: ${e.message}")
        }
    }

    private fun updateShellStatus() {
        val shells = mutableListOf("sh ✓")
        if (ProotBootstrap.isInstalled(this)) shells.add("ubuntu ✓")
        else shells.add("ubuntu ✗")
        val shizukuReady = RishExecutor.isShizukuReady()
        if (shizukuReady) shells.add("rish ✓") else shells.add("rish ✗")
        shellStatus.text = "Shells: ${shells.joinToString("  ")}"

        setupBtn.text = if (ProotBootstrap.isInstalled(this)) "Ubuntu ✓" else "Setup Ubuntu"
        shizukuBtn.text = if (shizukuReady) "Shizuku ✓" else "Setup Shizuku"
        shizukuBtn.requestLayout()
    }

    private fun appendLog(msg: String) {
        // Use append for simplicity to avoid recursion issues
        logText.append("$msg\n")
        
        // Check if there's an active selection
        val hasSelection = logText.selectionStart != logText.selectionEnd
        
        // Auto-scroll to bottom only if there's no selection
        if (!hasSelection) {
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
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
