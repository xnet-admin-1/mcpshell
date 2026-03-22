# MCP Shell

A standalone Android app that runs an MCP (Model Context Protocol) server, exposing Android shell environments as tools that any MCP client can use.

## What It Does

Runs an SSE-based MCP server on `localhost:39811`. Any MCP client (Claude Desktop, Cursor, VS Code, AIOPE, etc.) can connect and execute commands on the Android device through three shell environments:

- **sh** — Standard Android shell (unprivileged)
- **ubuntu** — Full proot Debian/Ubuntu userland (apt, python, node, git, gcc, etc.)
- **rish** — Elevated Shizuku shell (adb-level permissions)

Plus filesystem tools: `read_file`, `write_file`, `list_directory`, `search_files`, `get_file_info`.

## MCP Tools

| Tool | Description |
|---|---|
| `run_sh` | Execute command in Android sh shell |
| `run_ubuntu` | Execute command in proot Ubuntu environment |
| `run_rish` | Execute command with Shizuku elevated permissions |
| `read_file` | Read file contents |
| `write_file` | Write/create files |
| `list_directory` | List directory contents |
| `search_files` | Search for files by name pattern |
| `get_file_info` | Get file metadata |

## Client Configuration

### Claude Desktop / Cursor / etc.

```json
{
  "mcpServers": {
    "android": {
      "transport": "sse",
      "url": "http://<phone-ip>:39811/sse"
    }
  }
}
```

### Health Check

```
GET http://localhost:39811/health
```

## Requirements

- Android 8.0+ (API 26)
- Shizuku (for rish shell — optional)
- proot + Ubuntu rootfs (for ubuntu shell — optional)

## Building

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

AGPL-3.0
