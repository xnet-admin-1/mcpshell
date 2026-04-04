package com.mcpshell

import android.app.Application
import android.util.Log

class McpShellApp : Application() {
    companion object {
        lateinit var instance: McpShellApp; private set
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("McpShell", "Application started")

        // Sticky listener fires immediately if Shizuku is already bound,
        // or later when it becomes available
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            Log.i("McpShell", "Shizuku binder received")
            if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try { rikka.shizuku.Shizuku.requestPermission(0) } catch (_: Exception) {}
            }
        }
    }
}
