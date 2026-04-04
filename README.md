<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/MCP-Streamable%20HTTP-blue" />
  <img src="https://img.shields.io/badge/Transport-0.0.0.0-orange" />
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
│    AIOPE, etc.)     │    0.0.0.0:39811            │  ┌─ sh (Android)     │
└─────────────────────┘                             │  ├─ Ubuntu (proot)   │
                                                    │  ├─ rish (Shizuku)   │
                                                    │  └─ File tools       │
                                                    └──────────────────────┘
```

MCPShell runs a **Streamable HTTP** MCP server on `0.0.0.0:39811`. AI clients send JSON-RPC requests, MCPShell executes them and returns results. Simple request/response — no SSE, no WebSocket, no sessions.

---

## 🛠 Tools

### Shell Environments

| Tool | Environment | What You Get |
|------|-------------|-------------|
| `run_sh` | Android sh | Native Android shell — fast, always available |
| `run_ubuntu` | proot Ubuntu 24.04 | Full Linux userland — apt, python, gcc, git, curl, etc. |
| `run_rish` | Shizuku shell | ADB-level permissions — install apps, manage system settings |

### Filesystem

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents (text, with size limit) |
| `write_file` | Create or overwrite files |
| `list_directory` | List directory contents with metadata |
| `search_files` | Recursive file search by name pattern |
| `get_file_info` | File size, permissions, timestamps |

---

## 🚀 Quick Start

1. **Install** the APK on your Android device
2. **Open** MCPShell → tap **Start**
3. **Setup Ubuntu** (optional) → downloads ~17MB rootfs, extracts and patches
4. **Connect** your MCP client to `http://<device-ip>:39811/mcp`

---

## 📱 Client Configuration

MCPShell uses **Streamable HTTP** transport. Point your MCP client at:

```
http://<device-ip>:39811/mcp
```

The server also accepts requests on `/message` and `/sse` for client compatibility.

### Example: MCP Client Config

```json
{
  "mcpServers": {
    "mcpshell": {
      "url": "http://192.168.0.66:39811/mcp"
    }
  }
}
```

### Health Check

```
GET http://<device-ip>:39811/health
→ {"status":"ok","transport":"streamable-http","tools":8}
```

---

## 🐧 Ubuntu Environment

The proot Ubuntu environment is a real Ubuntu 24.04 (arm64) userland running without root via [PRoot](https://proot-me.github.io/). On first setup, MCPShell:

- Downloads the Ubuntu 24.04 rootfs (~17MB proot-distro repackaged)
- Extracts via bsdtar (libarchive) — handles hardlinks gracefully on Android
- Patches for proot compatibility (DNS, stubs, permissions, Android GIDs)
- Configures agent-optimized defaults (silent apt, no prompts, timeouts)

Once set up, your AI can:

```bash
apt update && apt install -y python3 git curl
python3 -c "print('Hello from Android!')"
git clone https://github.com/user/repo && cd repo && cat README.md
```

### Pre-configured Defaults

- **apt/dpkg**: Silent mode, no recommends, no interactive prompts, auto-retries
- **Shell**: Minimal prompt, no history, `DEBIAN_FRONTEND=noninteractive`
- **Network tools**: wget/curl with 30s timeouts and retries
- **Dev tools**: git with no pager, pip with no progress bars

---

## 🏗 Native Binary Pipeline

MCPShell ships 5 cross-compiled native binaries in `jniLibs/arm64-v8a/`:

| Binary | Source | Role |
|--------|--------|------|
| `libproot-xed.so` | Termux-patched PRoot | The proot binary — ptrace-based syscall translation |
| `libproot.so` | Termux PRoot loader | W^X bypass loader for Android ≥10 |
| `libproot32.so` | Termux PRoot loader | 32-bit loader |
| `libtalloc.so` | talloc | PRoot dependency — hierarchical memory allocator |
| `libbsdtar.so` | libarchive | PRoot dependency — archive extraction (also used for rootfs setup) |

PRoot's two dependencies are talloc and libarchive. All binaries are cross-compiled for `aarch64-linux-android` using the Android NDK toolchain.

The rootfs is downloaded from [Box](https://github.com/xnet-admin-1/box) releases — a Canonical ubuntu-base cloud image repackaged as tar.xz for proot compatibility (hardlink dereference, per-arch builds, sha256 checksums).

---

## 📐 Architecture

```
com.mcpshell/
├── MainActivity.kt              # UI: start/stop, setup ubuntu, self-test, logs
├── McpShellApp.kt               # Application singleton
├── server/
│   └── McpSseServer.kt          # Raw ServerSocket on 0.0.0.0, Streamable HTTP, JSON-RPC
├── shell/
│   ├── ShellExecutor.kt         # Android sh via Runtime.exec()
│   ├── ProotExecutor.kt         # proot Ubuntu (arm64 native binary)
│   ├── ProotBootstrap.kt        # Ubuntu rootfs download + bsdtar extraction + patching
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
- **0.0.0.0 bind** — accessible from other devices on the network for dev/testing
- **bsdtar (libarchive)** for rootfs extraction — handles all archive formats, gracefully works around Android's hardlink limitation
- **Native proot binaries** bundled as `.so` in jniLibs — executed from nativeLibraryDir (exec-allowed mount)
- **Rootfs from proot-distro** — custom repackaged Ubuntu cloud image with proot-specific patches

---

## 🏗 Building from Source

```bash
git clone https://github.com/xnet-mobile/mcpshell.git
cd mcpshell
./build.sh            # builds via Docker (xnet-dev container)
./build.sh --install  # build + adb install
```

Or directly with Gradle:

```bash
./gradlew :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Requirements

- Android SDK (API 26+, target 34)
- JDK 17
- Gradle 8.4+

---

## ⚠️ Security

MCPShell binds to `0.0.0.0` — it is accessible from the local network. Restrict access at the network level if needed.

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
