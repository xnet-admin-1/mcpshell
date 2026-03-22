package com.mcpshell.server

import android.util.Log
import com.mcpshell.tools.ToolRegistry
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "McpSseServer"

/**
 * MCP server using SSE transport over NanoHTTPD.
 *
 * Protocol flow:
 *   1. Client GET /sse → receives SSE stream with endpoint event
 *   2. Client POST /message?sessionId=xxx → sends JSON-RPC requests
 *   3. Server responds via the SSE stream
 */
class McpSseServer(
    port: Int,
    private val log: (String) -> Unit = {}
) : NanoHTTPD(port) {

    companion object {
        var instance: McpSseServer? = null
    }

    private val toolRegistry = ToolRegistry()

    // Active SSE sessions: sessionId → output pipe
    private val sessions = ConcurrentHashMap<String, PipedOutputStream>()

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "${session.method} ${session.uri}")
        return when {
            session.method == Method.GET && session.uri == "/sse" -> handleSse(session)
            session.method == Method.POST && session.uri == "/message" -> handleMessage(session)
            session.method == Method.GET && session.uri == "/health" -> newFixedLengthResponse(
                Response.Status.OK, "application/json", """{"status":"ok"}"""
            )
            else -> {
                log("404: ${session.method} ${session.uri}")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found: ${session.uri}")
            }
        }
    }

    private fun handleSse(session: IHTTPSession): Response {
        val sessionId = UUID.randomUUID().toString()
        val pipedIn = PipedInputStream(8192)
        val pipedOut = PipedOutputStream(pipedIn)
        sessions[sessionId] = pipedOut

        log("Client connected: $sessionId")
        Log.i(TAG, "SSE session opened: $sessionId")

        // Send the endpoint event so client knows where to POST
        val port = this.listeningPort
        val endpointUrl = "http://localhost:$port/message?sessionId=$sessionId"
        sendSseEvent(pipedOut, "endpoint", endpointUrl)

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun handleMessage(session: IHTTPSession): Response {
        val sessionId = session.parms["sessionId"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing sessionId")
        val pipe = sessions[sessionId]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unknown session")

        // Read POST body
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        val request = try { JSONObject(body) } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid JSON")
        }

        Log.d(TAG, "← $body")
        val method = request.optString("method")
        log("← $method")

        val response = processRequest(request)
        if (response != null) {
            val data = response.toString()
            Log.d(TAG, "→ $data")
            sendSseEvent(pipe, "message", data)
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

    private fun sendSseEvent(pipe: PipedOutputStream, event: String, data: String) {
        try {
            val msg = "event: $event\ndata: $data\n\n"
            pipe.write(msg.toByteArray())
            pipe.flush()
        } catch (e: Exception) {
            Log.w(TAG, "SSE write failed: ${e.message}")
        }
    }

    override fun stop() {
        sessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        sessions.clear()
        super.stop()
    }
}
