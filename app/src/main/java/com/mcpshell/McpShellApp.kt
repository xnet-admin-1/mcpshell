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
    }
}
