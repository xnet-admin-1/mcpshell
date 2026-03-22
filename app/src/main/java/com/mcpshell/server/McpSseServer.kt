package com.mcpshell.server

import android.util.Log
import com.mcpshell.tools.ToolRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "McpSseServer"

/**
 * MCP server using SSE transport over NanoHTTPD.
 *
 * Protocol:
 *   1. Client GET /sse → SSE stream, receives endpoint event
 *   2. Client POST /message?sessionId=xxx → JSON-RPC request
 *   3. Server pushes response via SSE stream
 */
class McpSseServer(
    port: Int,
    private val log: (String) -> Unit = {}
) : NanoHTTPD(port) {

    companion object {
        var instance: McpSseServer? = null
    }

    private val toolRegistry = ToolRegistry()

    // Each SSE session has a queue of bytes to send
    private val sessions = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "${session.method} ${session.uri}")
        return when {
            session.method == Method.GET && session.uri == "/sse" -> handleSse(session)
            session.method == Method.POST && session.uri == "/message" -> handleMessage(session)
            session.method == Method.GET && session.uri == "/health" ->
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
            else -> {
                log("404: ${session.method} ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: ${session.uri}")
            }
        }
    }

    private fun handleSse(session: IHTTPSession): Response {
        val sessionId = UUID.randomUUID().toString()
        val queue = LinkedBlockingQueue<ByteArray>()
        sessions[sessionId] = queue

        log("Client connected: $sessionId")

        // Send endpoint event immediately via the queue
        val port = this.listeningPort
        val endpointUrl = "http://localhost:$port/message?sessionId=$sessionId"
        queue.put("event: endpoint\ndata: $endpointUrl\n\n".toByteArray())

        // Create an InputStream that reads from the queue — blocks until data available
        val sseStream = object : InputStream() {
            private var current: ByteArrayInputStream? = null

            override fun read(): Int {
                while (true) {
                    val c = current
                    if (c != null) {
                        val b = c.read()
                        if (b != -1) return b
                        current = null
                    }
                    // Block waiting for next chunk (with keepalive)
                    val data = queue.poll(15, TimeUnit.SECONDS)
                    if (data == null) {
                        // Send SSE comment as keepalive
                        current = ByteArrayInputStream(":keepalive\n\n".toByteArray())
                    } else {
                        current = ByteArrayInputStream(data)
                    }
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // First byte blocks, then read what's available
                val first = read()
                if (first == -1) return -1
                b[off] = first.toByte()
                var count = 1
                while (count < len) {
                    val c = current
                    if (c != null && c.available() > 0) {
                        val n = c.read(b, off + count, minOf(len - count, c.available()))
                        if (n > 0) count += n
                        else break
                    } else break
                }
                return count
            }

            override fun available(): Int = current?.available() ?: 0
        }

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", sseStream)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleMessage(session: IHTTPSession): Response {
        val sessionId = session.parms["sessionId"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing sessionId")
        val queue = sessions[sessionId]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unknown session")

        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val request = try { JSONObject(body) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid JSON")
        }

        Log.d(TAG, "← $body")
        val method = request.optString("method")
        log("← $method")

        val result = processRequest(request)
        if (result != null) {
            val data = result.toString()
            Log.d(TAG, "→ $data")
            queue.put("event: message\ndata: $data\n\n".toByteArray())
        }

        return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", """{"ok":true}""")
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

    override fun stop() {
        sessions.clear()
        super.stop()
    }
}
