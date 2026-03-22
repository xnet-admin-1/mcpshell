package com.mcpshell.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class FileTools {

    fun register(tools: JSONArray) {
        tool(tools, "read_file", "Read file contents",
            schema("path" to prop("string", "Absolute file path"), required = listOf("path")))
        tool(tools, "write_file", "Write content to a file (creates parent dirs)",
            schema("path" to prop("string", "Absolute file path"),
                   "content" to prop("string", "File content"), required = listOf("path", "content")))
        tool(tools, "list_directory", "List directory contents with type and size",
            schema("path" to prop("string", "Absolute directory path"), required = listOf("path")))
        tool(tools, "search_files", "Search for files matching a name pattern",
            schema("path" to prop("string", "Root directory to search"),
                   "pattern" to prop("string", "Filename pattern (substring match)"),
                   required = listOf("path", "pattern")))
        tool(tools, "get_file_info", "Get file metadata",
            schema("path" to prop("string", "Absolute file path"), required = listOf("path")))
    }

    fun readFile(args: JSONObject): String {
        val f = File(args.getString("path"))
        if (!f.exists()) return "Error: not found: ${f.path}"
        if (!f.isFile) return "Error: not a file: ${f.path}"
        if (f.length() > 500_000) return "Error: file too large (${f.length()} bytes)"
        return f.readText()
    }

    fun writeFile(args: JSONObject): String {
        val f = File(args.getString("path"))
        f.parentFile?.mkdirs()
        f.writeText(args.getString("content"))
        return "Written ${f.length()} bytes to ${f.path}"
    }

    fun listDirectory(args: JSONObject): String {
        val d = File(args.getString("path"))
        if (!d.isDirectory) return "Error: not a directory: ${d.path}"
        return d.listFiles()?.sortedBy { it.name }?.joinToString("\n") { f ->
            val type = if (f.isDirectory) "dir " else "file"
            val size = if (f.isFile) " (${f.length()})" else ""
            "$type ${f.name}$size"
        } ?: "Empty directory"
    }

    fun searchFiles(args: JSONObject): String {
        val root = File(args.getString("path"))
        val pattern = args.getString("pattern").lowercase()
        val matches = root.walkTopDown().take(5000)
            .filter { it.isFile && it.name.lowercase().contains(pattern) }
            .take(50)
            .map { it.absolutePath }
            .toList()
        return if (matches.isEmpty()) "No files matching '$pattern'" else matches.joinToString("\n")
    }

    fun getFileInfo(args: JSONObject): String {
        val f = File(args.getString("path"))
        if (!f.exists()) return "Error: not found: ${f.path}"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return "path: ${f.absolutePath}\nsize: ${f.length()}\nmodified: ${fmt.format(f.lastModified())}\nreadable: ${f.canRead()}\nwritable: ${f.canWrite()}\ndirectory: ${f.isDirectory}"
    }
}
