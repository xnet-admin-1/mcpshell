package com.mcpshell.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of all MCP tools. Dispatches tool/call to the right handler.
 */
class ToolRegistry {

    private val shell = ShellTools()
    private val file  = FileTools()

    fun listTools(): JSONArray {
        val tools = JSONArray()
        shell.register(tools)
        file.register(tools)
        return tools
    }

    fun callTool(name: String, args: JSONObject): String = when (name) {
        "run_sh"      -> shell.runSh(args)
        "run_ubuntu"  -> shell.runUbuntu(args)
        "run_rish"    -> shell.runRish(args)
        "read_file"   -> file.readFile(args)
        "write_file"  -> file.writeFile(args)
        "list_directory" -> file.listDirectory(args)
        "search_files"  -> file.searchFiles(args)
        "get_file_info" -> file.getFileInfo(args)
        else -> "Error: unknown tool '$name'"
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

internal fun tool(tools: JSONArray, name: String, desc: String, schema: JSONObject) {
    tools.put(JSONObject().apply {
        put("name", name); put("description", desc); put("inputSchema", schema)
    })
}

internal fun prop(type: String, desc: String) = JSONObject().apply {
    put("type", type); put("description", desc)
}

internal fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) =
    JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
        put("required", JSONArray(required))
    }
