<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-blue" />
  <img src="https://img.shields.io/badge/Transport-localhost-orange" />
  <img src="https://img.shields.io/badge/License-AGPL--3.0-purple" />
</p>

# 🐚 MCPShell

**Turn any Android device into an MCP server.** MCPShell exposes Android shell environments, a full Ubuntu userland, and filesystem tools to any MCP-compatible AI client — all running locally on the device.

Connect your AI chat app → it gets a real Linux terminal, package manager, and file access on your phone. No cloud. No root. No PC required.

---

## How It Works

```
┌─────────────────────┐         POST /mcp          ┌──────────────────────┐
│   MCP Client App    │ ◄─────────────────────────► │     MCPShell         │
│   (Kelivo, Claude,  │    JSON-RPC over HTTP       │                      │
│    AIOPE, etc.)     │    localhost:39811           │  ┌─ sh (Android)     │
└─────────────────────┘                             │  ├─ Ubuntu (proot)   │
                                                    │  ├─ rish (Shizuku)   │
                                                    │  └─ File tools       │
                                                    └──────────────────────┘
```

MCPShell runs a **Streamable HTTP** MCP server on `localhost:39811`. AI clients on the same device send JSON-RPC requests, MCPShell executes them and returns results. Simple request/response — no SSE, no WebSocket, no sessions.

---

## 🛠 Tools

### Shell Environments

| Tool | Environment | What You Get |
|------|-------------|-------------|
| `run_sh` | Android sh | Native Android shell — fast, always available |
| `run_ubuntu` | proot Ubuntu 24.04 | Full Linux userland — apt, python, node, gcc, git, curl, etc. |
| `run_rish` | Shizuku shell | ADB-level permissions — install apps, manage system settings |

### Filesystem

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents (text, with line limit) |
| `write_file` | Create or overwrite files |
| `list_directory` | List directory contents with metadata |
| `search_files` | Recursive file search by name pattern |
| `get_file_info` | File size, permissions, timestamps |

---

## 🚀 Quick Start

1. **Install** the APK on your Android device
2. **Open** MCPShell → tap **Start**
3. **Setup Ubuntu** (optional) → downloads ~40MB rootfs, takes ~1 min
4. **Connect** your MCP client to `http://localhost:39811/mcp`

That's it. Your AI can now run commands on your phone.

---

## 📱 Client Configuration

MCPShell uses **Streamable HTTP** transport. Point your MCP client at:

```
http://localhost:39811/mcp
```

The server also accepts requests on `/message` and `/sse` for client compatibility.

### Example: MCP Client Config

```json
{
  "mcpServers": {
    "mcpshell": {
      "url": "http://localhost:39811/mcp"
    }
  }
}
```

### Health Check

```
GET http://localhost:39811/health
→ {"status":"ok","tools":8}
```

---

## 🐧 Ubuntu Environment

The proot Ubuntu environment is a real Ubuntu 24.04 (arm64) userland running without root via [proot](https://proot-me.github.io/). On first setup, MCPShell:

- Downloads the official Ubuntu Base 24.04 rootfs (~40MB)
- Extracts and patches it for proot compatibility
- Configures agent-optimized defaults (silent apt, no prompts, timeouts)

Once set up, your AI can:

```bash
apt update && apt install -y python3 nodejs git
python3 -c "print('Hello from Android!')"
git clone https://github.com/user/repo && cd repo && cat README.md
```

### Pre-configured Defaults

The Ubuntu environment comes optimized for headless AI agent use:

- **apt/dpkg**: Silent mode, no recommends, no interactive prompts, auto-retries
- **Shell**: Minimal prompt, no history, `DEBIAN_FRONTEND=noninteractive`
- **Network tools**: wget/curl with 30s timeouts and retries
- **Dev tools**: git with no pager, pip/npm with no progress bars

---

## 🏗 Building from Source

```bash
git clone https://github.com/xnet-admin-1/mcpshell.git
cd mcpshell
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Requirements

- Android SDK (API 26+, target 34)
- JDK 17
- Gradle 8.4+

---

## 📐 Architecture

```
com.mcpshell/
├── MainActivity.kt              # UI: start/stop, setup ubuntu, self-test, logs
├── McpShellApp.kt               # Application singleton
├── server/
│   └── McpSseServer.kt          # Raw ServerSocket, Streamable HTTP, JSON-RPC
├── shell/
│   ├── ShellExecutor.kt         # Android sh via Runtime.exec()
│   ├── ProotExecutor.kt         # proot Ubuntu (arm64 native binary)
│   ├── ProotBootstrap.kt        # Ubuntu rootfs download + setup
│   └── RishExecutor.kt          # Shizuku elevated shell
├── tools/
│   ├── ToolRegistry.kt          # Tool dispatch + JSON schema
│   ├── ShellTools.kt            # run_sh, run_ubuntu, run_rish
│   └── FileTools.kt             # read_file, write_file, list_directory, etc.
└── service/
    └── McpForegroundService.kt  # Keeps server alive in background
```

### Key Design Decisions

- **Raw ServerSocket** instead of NanoHTTPD — NanoHTTPD's chunked encoding broke client parsing
- **Streamable HTTP** instead of SSE — simpler, more reliable, no session management
- **Blocking stream reads** instead of polling — returns output immediately when process finishes
- **Native proot binaries** bundled as `.so` in jniLibs — `libproot-xed.so` (proot), `libproot.so` (loader), `libtalloc.so`
- **Pure Kotlin tar extraction** — Android's toybox `tar` lacks gzip support

---

## ⚠️ Security

MCPShell binds to **localhost only**. It is not accessible from other devices on the network. The MCP client must be running on the same Android device.

`run_sh` and `run_ubuntu` execute arbitrary commands as the app user. `run_rish` executes with ADB-level permissions via Shizuku. Use responsibly.

---

## 📋 Requirements

| Requirement | Status |
|-------------|--------|
| Android 8.0+ (API 26) | Required |
| ~50MB storage (Ubuntu rootfs) | For `run_ubuntu` |
| [Shizuku](https://shizuku.rikka.app/) | For `run_rish` (optional) |
| Internet (first setup only) | To download Ubuntu rootfs |

---

## License

[AGPL-3.0](LICENSE)
