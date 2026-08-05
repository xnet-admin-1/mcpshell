package com.mcpshell

import android.app.Application
import android.util.Log
import rikka.shizuku.Shizuku

class McpShellApp : Application() {
    companion object {
        lateinit var instance: McpShellApp; private set
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i("McpShell", "Application started")

        // Re-enable ADB over TCP on app start for persistence
        // Shizuku restarts ADB over USB - this forces TCP on port 5555
        try {
            Runtime.getRuntime().exec(arrayOf("am", "service", "call", "adbd", "1"))
            Log.i("McpShell", "Restarted adbd service")
        } catch (e: Exception) {
            Log.w("McpShell", "Could not restart adbd: ${e.message}")
        }

        // Sticky listener fires immediately if Shizuku is already bound,
        // or later when it becomes available
        Shizuku.addBinderReceivedListenerSticky {
            Log.i("McpShell", "Shizuku binder received")
            try {
                if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.i("McpShell", "Requesting Shizuku permission...")
                    Shizuku.requestPermission(0)
                } else {
                    Log.i("McpShell", "Shizuku permission already granted")
                }
                Log.i("McpShell", "Shizuku pingBinder: ${Shizuku.pingBinder()}")
            } catch (e: Exception) {
                Log.e("McpShell", "Shizuku permission check failed: ${e.message}")
            }
        }
    }
}
