package com.mcpshell.server

import android.util.Log
import com.mcpshell.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "McpSseServer"

/**
 * MCP SSE server using raw ServerSocket.
 * NanoHTTPD's chunked encoding breaks SSE streams, so we handle HTTP manually.
 */
class McpSseServer(private val port: Int, private val log: (String) -> Unit = {}) {

    companion object {
        var instance: McpSseServer? = null
    }

    private val toolRegistry = ToolRegistry()
    private val sessions = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
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
        sessions.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 0 // no read timeout for SSE
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            // Read HTTP request line + headers
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
            val path = parts.getOrNull(1) ?: ""

            Log.d(TAG, "$method $path")

            when {
                method == "GET" && path == "/sse" -> handleSse(output, socket)
                method == "POST" && path.startsWith("/message") -> {
                    handleMessage(path, input, headers, output)
                    socket.close()
                }
                method == "GET" && path == "/health" -> {
                    sendHttp(output, 200, "application/json", """{"status":"ok"}""")
                    socket.close()
                }
                else -> {
                    log("404: $method $path")
                    sendHttp(output, 404, "text/plain", "Not found: $path")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleSse(output: OutputStream, socket: Socket) {
        val sessionId = UUID.randomUUID().toString()
        val queue = LinkedBlockingQueue<String>()
        sessions[sessionId] = queue

        log("Client connected: $sessionId")

        // Send HTTP headers for SSE
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.flush()

        // Send endpoint event
        val endpointUrl = "http://localhost:$port/message?sessionId=$sessionId"
        writeSseEvent(output, "endpoint", endpointUrl)

        // Keep connection open, send events from queue
        try {
            while (isAlive && !socket.isClosed) {
                val data = queue.poll(15, TimeUnit.SECONDS)
                if (data != null) {
                    writeSseEvent(output, "message", data)
                } else {
                    // Keepalive
                    output.write(":keepalive\n\n".toByteArray())
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SSE stream ended: ${e.message}")
        } finally {
            sessions.remove(sessionId)
            log("Client disconnected: $sessionId")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleMessage(path: String, input: BufferedReader, headers: Map<String, String>, output: OutputStream) {
        // Parse sessionId from query string
        val sessionId = path.substringAfter("sessionId=", "").substringBefore("&")
        if (sessionId.isEmpty()) {
            sendHttp(output, 400, "text/plain", "Missing sessionId")
            return
        }
        val queue = sessions[sessionId]
        if (queue == null) {
            sendHttp(output, 400, "text/plain", "Unknown session")
            return
        }

        // Read POST body
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyChars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bodyChars, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        val body = String(bodyChars, 0, read)

        val request = try { JSONObject(body) } catch (e: Exception) {
            sendHttp(output, 400, "text/plain", "Invalid JSON")
            return
        }

        Log.d(TAG, "← $body")
        val method = request.optString("method")
        log("← $method")

        val result = processRequest(request)
        if (result != null) {
            val data = result.toString()
            Log.d(TAG, "→ $data")
            queue.put(data)
        }

        sendHttp(output, 202, "application/json", """{"ok":true}""")
    }

    private fun writeSseEvent(output: OutputStream, event: String, data: String) {
        output.write("event: $event\ndata: $data\n\n".toByteArray())
        output.flush()
    }

    private fun sendHttp(output: OutputStream, code: Int, contentType: String, body: String) {
        val status = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 404 -> "Not Found"
            else -> "OK"
        }
        val bytes = body.toByteArray()
        val resp = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
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
                val params = request.optJSONObject("params") ?: JSONObject()
                val name = params.getString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                log("⚡ $name")
                val result = toolRegistry.callTool(name, args)
                val isError = result.startsWith("Error:")
                jsonRpcResult(id, JSONObject().apply {
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "text"); put("text", result)
                    }))
                    if (isError) put("isError", true)
                })
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
}
