package com.mcpshell.tools

import android.util.Log
import com.mcpshell.McpShellApp
import com.mcpshell.shell.ProotExecutor
import com.mcpshell.shell.RishExecutor
import com.mcpshell.shell.ShellExecutor
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ShellTools"

class ShellTools {

    fun register(tools: JSONArray) {
        tool(tools, "run_sh", "Run a command in the Android sh shell (app-level, unprivileged)",
            schema("command" to prop("string", "Shell command to execute"),
                   "timeout" to prop("integer", "Timeout in seconds (default 15)"),
                   required = listOf("command")))

        tool(tools, "run_ubuntu",
            "Run a command in a proot Ubuntu/Debian environment. Has apt, python, node, git, etc. " +
            "Use for package installs, builds, and anything needing a full Linux userland.",
            schema("command" to prop("string", "Command to execute inside Ubuntu"),
                   "timeout" to prop("integer", "Timeout in seconds (default 30)"),
                   required = listOf("command")))

        tool(tools, "run_rish",
            "Run a command with elevated (adb-level) permissions via Shizuku. " +
            "Can access system settings, install APKs, manage packages. Requires Shizuku to be running.",
            schema("command" to prop("string", "Shell command to execute with elevated permissions"),
                   "timeout" to prop("integer", "Timeout in seconds (default 15)"),
                   required = listOf("command")))
    }

    fun runSh(args: JSONObject): String {
        val cmd = args.getString("command")
        val timeout = args.optLong("timeout", 15)
        Log.d(TAG, "run_sh: $cmd")
        return ShellExecutor.exec(cmd, timeout * 1000)
    }

    fun runUbuntu(args: JSONObject): String {
        val cmd = args.getString("command")
        val timeout = args.optLong("timeout", 60)
        Log.d(TAG, "run_ubuntu: $cmd")
        val ctx = McpShellApp.instance
        return ProotExecutor.exec(ctx, cmd, timeout * 1000)
    }

    fun runRish(args: JSONObject): String {
        val cmd = args.getString("command")
        val timeout = args.optLong("timeout", 15)
        Log.d(TAG, "run_rish: $cmd")
        return RishExecutor.exec(cmd, timeout * 1000)
    }
}
