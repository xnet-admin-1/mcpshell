package com.mcpshell.server

import android.util.Log
import com.mcpshell.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "McpSseServer"

/**
 * MCP server using Streamable HTTP transport (2025-03-26 spec).
 * Single endpoint: POST /mcp → JSON-RPC request/response.
 * No SSE, no long-lived connections.
 */
class McpSseServer(private val port: Int, private val log: (String) -> Unit = {}) {

    companion object {
        var instance: McpSseServer? = null
    }

    private val toolRegistry = ToolRegistry()
    private var serverSocket: ServerSocket? = null
    @Volatile var isAlive = false; private set

    fun start() {
        isAlive = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Listening on port $port")
                while (isAlive) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handleClient(socket) }.start()
                }
            } catch (e: Exception) {
                if (isAlive) Log.e(TAG, "Server error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isAlive = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1)?.substringBefore("?") ?: ""

            Log.d(TAG, "$method $path")

            when {
                method == "POST" && (path == "/mcp" || path == "/message" || path == "/sse") -> {
                    handleMcp(input, headers, output)
                }
                method == "GET" && (path == "/health" || path == "/") -> {
                    sendHttp(output, 200, "application/json", """{"status":"ok","transport":"streamable-http"}""")
                }
                // Support GET on /mcp for SSE-style clients (return 405 with Allow header)
                method == "GET" && path == "/mcp" -> {
                    val resp = "HTTP/1.1 405 Method Not Allowed\r\nAllow: POST\r\nContent-Length: 0\r\n\r\n"
                    output.write(resp.toByteArray()); output.flush()
                }
                else -> {
                    log("404: $method $path")
                    sendHttp(output, 404, "text/plain", "Not found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleMcp(input: BufferedReader, headers: Map<String, String>, output: OutputStream) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyChars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyChars, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        val body = String(bodyChars, 0, read)
        Log.d(TAG, "← $body")

        // Could be a single request or a batch
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            // Batch — process each, return array
            val arr = JSONArray(trimmed)
            val results = JSONArray()
            for (i in 0 until arr.length()) {
                val req = arr.getJSONObject(i)
                val res = processRequest(req)
                if (res != null) results.put(res)
            }
            if (results.length() > 0) {
                sendHttp(output, 200, "application/json", results.toString())
            } else {
                sendHttp(output, 202, "application/json", "")
            }
        } else {
            val request = try { JSONObject(trimmed) } catch (e: Exception) {
                sendHttp(output, 400, "text/plain", "Invalid JSON"); return
            }
            val method = request.optString("method")
            log("← $method" + if (method == "tools/call") " | $body" else "")

            val result = processRequest(request)
            if (result != null) {
                Log.d(TAG, "→ ${result}")
                sendHttp(output, 200, "application/json", result.toString())
            } else {
                // Notification — no response body needed
                sendHttp(output, 202, "application/json", "")
            }
        }
    }

    private fun sendHttp(output: OutputStream, code: Int, contentType: String, body: String) {
        val status = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "OK"
        }
        val bytes = body.toByteArray()
        val resp = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(resp.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun processRequest(request: JSONObject): JSONObject? {
        val method = request.optString("method")
        val id = request.opt("id")

        return when (method) {
            "initialize" -> jsonRpcResult(id, JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("serverInfo", JSONObject().apply {
                    put("name", "mcpshell"); put("version", "0.1.0")
                })
                put("capabilities", JSONObject().apply {
                    put("tools", JSONObject().apply { put("listChanged", true) })
                })
            })
            "notifications/initialized" -> null
            "tools/list" -> jsonRpcResult(id, JSONObject().apply {
                put("tools", toolRegistry.listTools())
            })
            "tools/call" -> {
                try {
                    val params = request.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    log("⚡ $name ${args.toString().take(80)}")
                    if (name.isEmpty()) {
                        jsonRpcError(id, -32602, "Missing tool name in params. Got: ${params.keys().asSequence().toList()}")
                    } else {
                        val result = toolRegistry.callTool(name, args)
                        val isError = result.startsWith("Error:")
                        jsonRpcResult(id, JSONObject().apply {
                            put("content", JSONArray().put(JSONObject().apply {
                                put("type", "text"); put("text", result)
                            }))
                            if (isError) put("isError", true)
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "tools/call failed: ${e.message}\nrequest: $request", e)
                    jsonRpcError(id, -32603, "Internal error: ${e.message}")
                }
            }
            "ping" -> jsonRpcResult(id, JSONObject())
            else -> {
                Log.w(TAG, "Unknown method: $method")
                null
            }
        }
    }

    private fun jsonRpcResult(id: Any?, result: JSONObject) = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", id); put("result", result)
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String) = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", JSONObject().apply { put("code", code); put("message", message) })
    }
}
