package com.mcpshell.shell

import java.io.InputStream

/** Basic Android sh shell executor. */
object ShellExecutor {

    fun exec(command: String, timeoutMs: Long = 15_000): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = readStream(process.inputStream, timeoutMs)
        val stderr = readStream(process.errorStream, 1_000)
        process.waitFor()
        val out = (stdout + stderr).trim()
        if (out.length > 8000) out.take(8000) + "\n[truncated]" else out.ifEmpty { "(no output, exit ${process.exitValue()})" }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    internal fun readStream(stream: InputStream, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        val reader = stream.bufferedReader()
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                val line = reader.readLine() ?: break
                sb.appendLine(line)
                if (sb.length > 8000) break
            } else Thread.sleep(20)
        }
        return sb.toString()
    }
}
