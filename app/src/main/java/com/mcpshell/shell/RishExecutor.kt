package com.mcpshell.shell

import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Elevated shell via Shizuku (adb-level permissions).
 * Ported from AIOPE's ShizukuShell.kt.
 */
object RishExecutor {

    private const val TAG = "RishExecutor"

    fun exec(command: String, timeoutMs: Long = 15_000): String {
        return if (isShizukuReady()) {
            execViaShizuku(command, timeoutMs)
        } else {
            "Error: Shizuku not available. Install Shizuku and grant permission."
        }
    }

    fun isShizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun execViaShizuku(command: String, timeoutMs: Long): String = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java,
            Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as ShizukuRemoteProcess
        val stdout = ShellExecutor.readStream(process.inputStream, timeoutMs)
        val stderr = ShellExecutor.readStream(process.errorStream, 1_000)
        process.waitFor()
        process.destroy()
        val out = (stdout + stderr).trim()
        if (out.length > 8000) out.take(8000) + "\n[truncated]"
        else out.ifEmpty { "(no output, exit ${process.exitValue()})" }
    } catch (e: Exception) {
        Log.w(TAG, "Shizuku exec failed: ${e.message}")
        "Error: Shizuku exec failed: ${e.message}"
    }
}
