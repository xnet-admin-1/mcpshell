package com.mcpshell.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads and configures the proot Ubuntu rootfs.
 * Pure Kotlin port of AIOPE's aiope_cli.py env_setup + ProcessManager patches.
 */
object ProotBootstrap {

    private const val TAG = "ProotBootstrap"

    fun envDir(ctx: Context) = File(ctx.filesDir, "env")
    fun rootfsDir(ctx: Context) = File(envDir(ctx), "ubuntu")
    private fun marker(ctx: Context) = File(envDir(ctx), ".rootfs_installed")

    fun isInstalled(ctx: Context) = marker(ctx).exists() && rootfsDir(ctx).isDirectory

    /**
     * Full bootstrap: create dirs, copy talloc, download rootfs, patch.
     * Call on a background thread.
     */
    fun setup(ctx: Context, log: (String) -> Unit): Boolean {
        try {
            val filesDir = ctx.filesDir
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val envDir = envDir(ctx)
            val rootfs = rootfsDir(ctx)

            // 1. Create dirs
            log("Creating directories...")
            listOf(envDir, rootfs, File(filesDir, "tmp"), File(filesDir, "home")).forEach { it.mkdirs() }

            // 2. Copy libtalloc.so → libtalloc.so.2 (proot needs this)
            val tallocSrc = File(nativeDir, "libtalloc.so")
            val tallocDst = File(filesDir, "libtalloc.so.2")
            if (tallocSrc.exists()) {
                tallocSrc.inputStream().use { i -> tallocDst.outputStream().use { o -> i.copyTo(o) } }
                log("libtalloc.so.2 ready (${tallocDst.length()} bytes)")
            } else {
                log("ERROR: libtalloc.so not found in $nativeDir")
                return false
            }

            // 3. Download rootfs if needed
            if (!marker(ctx).exists()) {
                val arch = System.getProperty("os.arch")?.lowercase() ?: "aarch64"
                val ubuntuArch = when {
                    "aarch64" in arch || "arm64" in arch -> "arm64"
                    "armv7" in arch || "arm" in arch -> "armhf"
                    "x86_64" in arch -> "amd64"
                    else -> "arm64"
                }
                val url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-$ubuntuArch.tar.gz"
                val tarball = File(envDir, "rootfs.tar.gz")

                log("Downloading Ubuntu 24.04 rootfs ($ubuntuArch)...")
                download(url, tarball, log)

                log("Extracting rootfs...")
                extractTarGz(tarball, rootfs, log)
                tarball.delete()
                marker(ctx).writeText("installed")
                log("Rootfs extracted")
            } else {
                log("Rootfs already installed")
            }

            // 4. Patch rootfs
            patchRootfs(rootfs, log)

            log("Ubuntu environment ready!")
            return true
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Log.e(TAG, "setup failed", e)
            return false
        }
    }

    private fun extractTarGz(tarGz: File, destDir: File, log: (String) -> Unit) {
        var count = 0
        GZIPInputStream(FileInputStream(tarGz)).use { gzip ->
            val buf = ByteArray(512)
            while (true) {
                val headerRead = readFully(gzip, buf)
                if (headerRead < 512 || buf.all { it == 0.toByte() }) break

                val name = String(buf, 0, 100).trim('\u0000').trim()
                if (name.isEmpty()) break
                val sizeStr = String(buf, 124, 12).trim('\u0000').trim()
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val typeFlag = buf[156]

                // Skip PaxHeaders entries (cause dpkg errors)
                if (typeFlag.toInt().toChar() == 'x' || typeFlag.toInt().toChar() == 'g'
                    || name.contains("PaxHeaders")) {
                    val pad = (512 - (size % 512)) % 512
                    skipBytes(gzip, size + pad)
                    continue
                }

                val outFile = File(destDir, name)

                when (typeFlag.toInt().toChar()) {
                    '5', 'D' -> outFile.mkdirs()
                    '2' -> {
                        // Symlink
                        val linkTarget = String(buf, 157, 100).trim('\u0000').trim()
                        try {
                            outFile.parentFile?.mkdirs()
                            if (outFile.exists()) outFile.delete()
                            Runtime.getRuntime().exec(arrayOf("ln", "-sf", linkTarget, outFile.absolutePath)).waitFor()
                        } catch (_: Exception) {}
                        skipBytes(gzip, size)
                    }
                    else -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            var remaining = size
                            val dataBuf = ByteArray(65536)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, dataBuf.size.toLong()).toInt()
                                val n = gzip.read(dataBuf, 0, toRead)
                                if (n <= 0) break
                                out.write(dataBuf, 0, n)
                                remaining -= n
                            }
                        }
                        // Set executable for binaries, libraries, and linkers
                        if (name.contains("/bin/") || name.contains("/sbin/")
                            || name.endsWith(".so") || name.contains(".so.")
                            || name.contains("/lib/ld-")) {
                            outFile.setExecutable(true)
                        }
                        // Skip padding to 512-byte boundary
                        val pad = (512 - (size % 512)) % 512
                        skipBytes(gzip, pad)
                    }
                }
                count++
                if (count % 500 == 0) log("  Extracted $count files...")
            }
        }
        log("  Extracted $count files")
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return off
            off += n
        }
        return off
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var remaining = count
        val skip = ByteArray(4096)
        while (remaining > 0) {
            val n = input.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit) {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        // Manual redirect loop (HttpURLConnection doesn't follow https→https redirects reliably)
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 5) { log("ERROR: too many redirects"); return }
                continue
            }
            if (code != 200) {
                log("ERROR: HTTP $code from $url")
                conn.disconnect()
                return
            }
            break
        }
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0 && downloaded % (1024 * 1024) < 65536) {
                        log("  ${downloaded / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
                    }
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024}KB)")
    }

    private fun patchRootfs(rootfs: File, log: (String) -> Unit) {
        // DNS
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Fix permissions on ELF interpreter and shared libs (tar extraction may miss +x)
        listOf("lib/ld-linux-aarch64.so.1", "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
               "lib/aarch64-linux-gnu/ld-2.39.so", "lib/ld-linux-armhf.so.3",
               "usr/bin/env", "bin/sh", "bin/bash", "bin/dash").forEach { rel ->
            val f = File(rootfs, rel)
            if (f.exists() && !f.isDirectory) f.setExecutable(true)
        }
        // Bulk chmod +x on all .so files in lib dirs
        listOf("lib", "lib/aarch64-linux-gnu", "usr/lib", "usr/lib/aarch64-linux-gnu").forEach { dir ->
            File(rootfs, dir).listFiles()?.filter { it.name.endsWith(".so") || it.name.contains(".so.") }
                ?.forEach { it.setExecutable(true) }
        }

        // Standard dirs
        for (d in listOf("tmp", "var/tmp", "var/cache/apt", "var/lib/apt", "home", "root",
                         "root/.config/pip", "root/.config/procps")) {
            File(rootfs, d).mkdirs()
        }
        File(rootfs, "tmp").setWritable(true, false)

        // --- Agent-optimized defaults ---

        // apt: silent, no recommends, no periodic updates
        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d/99-agent-optimizations").writeText("""
APT::Get::Assume-Yes "true";
APT::Get::Show-Upgraded "false";
APT::Install-Recommends "false";
APT::Install-Suggests "false";
APT::Quiet "2";
APT::Periodic::Update-Package-Lists "0";
APT::Periodic::Unattended-Upgrade "0";
Acquire::Retries "3";
Acquire::https::Timeout "30";
Acquire::http::Timeout "30";
""".trimIndent() + "\n")

        // dpkg: force-unsafe-io, no prompts, remove PaxHeaders
        File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg.d/PaxHeaders").let { if (it.exists()) it.deleteRecursively() }
        File(rootfs, "etc/dpkg/dpkg.cfg.d/99-agent-optimizations").writeText(
            "force-unsafe-io\nforce-confdef\nforce-confold\nno-debsig\n")

        // bashrc: minimal prompt, aliases, no history
        File(rootfs, "root/.bashrc").writeText("""
export TERM=xterm-256color
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DEBIAN_FRONTEND=noninteractive
export PS1='\u@mcpshell:\w\$ '
export HISTSIZE=0
export HISTFILESIZE=0
alias ls='ls --color=auto'
alias ll='ls -lah'
alias apt='apt -qq'
""".trimIndent() + "\n")

        // profile: PATH, locale, noninteractive
        File(rootfs, "root/.profile").writeText("""
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DEBIAN_FRONTEND=noninteractive
""".trimIndent() + "\n")

        // inputrc: completion, no bell
        File(rootfs, "root/.inputrc").writeText(
            "set editing-mode emacs\nset bell-style none\nset completion-ignore-case on\nTAB: complete\n")

        // wget: quiet, timeout
        File(rootfs, "root/.wgetrc").writeText("quiet=on\ntimeout=30\ntries=3\n")

        // curl: silent, timeout
        File(rootfs, "root/.curlrc").writeText("--silent\n--fail\n--connect-timeout 30\n--max-time 120\n--retry 3\n")

        // git: no pager, no ssl verify
        File(rootfs, "root/.gitconfig").writeText(
            "[core]\n\tpager = cat\n[http]\n\tsslVerify = false\n[advice]\n\tdetachedHead = false\n")

        // pip: no cache, quiet
        File(rootfs, "root/.config/pip/pip.conf").writeText(
            "[global]\nno-cache-dir = true\nquiet = 1\nprogress-bar = off\n")

        // npm: no progress
        File(rootfs, "root/.npmrc").writeText("progress=false\nloglevel=error\n")

        // nano: auto-indent, line numbers
        File(rootfs, "root/.nanorc").writeText("set autoindent\nset linenumbers\nset nobackup\n")

        // --- Proot stubs ---
        // Only ldconfig needs a wrapper — it must fall through to ldconfig.real
        // AIOPE does NOT stub invoke-rc.d or start-stop-daemon — those work fine in proot
        val ldconfigWrapper = buildString {
            appendLine("#!/bin/sh")
            appendLine()
            appendLine("if  test \$# = 0\t\t\t\t\t\t\t\\")
            appendLine("    && test x\"\${LDCONFIG_NOTRIGGER}\" = x\t\t\t\t\\")
            appendLine(" && test x\"\${DPKG_MAINTSCRIPT_PACKAGE}\" != x\t\t\t\\")
            appendLine(" && dpkg-trigger --check-supported 2>/dev/null")
            appendLine("then")
            appendLine("\tif dpkg-trigger --no-await ldconfig; then")
            appendLine("\t\tif test x\"\${LDCONFIG_TRIGGER_DEBUG}\" != x; then")
            appendLine("\t\t\techo \"ldconfig: wrapper deferring update (trigger activated)\"")
            appendLine("\t\tfi")
            appendLine("\t\texit 0")
            appendLine("\tfi\t")
            appendLine("fi")
            appendLine()
            appendLine("exec /sbin/ldconfig.real \"\$@\"")
        }
        for (rel in listOf("sbin/ldconfig")) {
            val f = File(rootfs, rel)
            if (f.exists()) {
                val bak = File(f.parent, f.name + ".real")
                if (!bak.exists()) f.copyTo(bak)
                f.writeText(ldconfigWrapper); f.setExecutable(true)
            }
        }

        // Android GIDs — full list ported from AIOPE ProcessManager.kt
        val groupFile = File(rootfs, "etc/group")
        if (groupFile.exists()) {
            val existing = groupFile.readText()
            val knownGids = mapOf(
                1000 to "system",        1001 to "radio",
                1002 to "bluetooth",     1003 to "graphics",
                1004 to "input",         1005 to "audio",
                1006 to "camera",        1007 to "log",
                1008 to "compass",       1009 to "mount",
                1010 to "wifi",          1011 to "adb",
                1012 to "install",       1013 to "media",
                1014 to "dhcp",          1015 to "sdcard_rw",
                1016 to "vpn",           1017 to "keystore",
                1018 to "usb",           1019 to "drm",
                1020 to "mdnsr",         1021 to "gps",
                1023 to "media_rw",      1024 to "mtp",
                1026 to "drmrpc",        1027 to "nfc",
                1028 to "sdcard_r",      1029 to "clat",
                1030 to "loop_radio",    1031 to "mediadrm",
                1032 to "package_info",  1033 to "sdcard_pics",
                1034 to "sdcard_av",     1035 to "sdcard_all",
                1036 to "logd",          1037 to "shared_relro",
                1038 to "dbus",          1039 to "tlsdate",
                1040 to "mediaex",       1041 to "audioserver",
                1042 to "metrics_coll",  1043 to "metricsd",
                1044 to "webserv",       1045 to "debuggerd",
                1046 to "mediacodec",    1047 to "cameraserver",
                1048 to "firewall",      1049 to "trunks",
                1050 to "nvram",         1051 to "dns",
                1052 to "dns_tether",    1053 to "webview_zygote",
                1054 to "vehicle_network", 1055 to "media_audio",
                1056 to "media_video",   1057 to "media_image",
                1058 to "tombstoned",    1059 to "media_obb",
                1060 to "ese",           1061 to "ota_update",
                1062 to "automotive_evs",1063 to "lowpan",
                1064 to "reserved_1064", 1065 to "statsd",
                1066 to "incidentd",     1067 to "secure_element",
                1068 to "lmkd",          1069 to "llkd",
                1070 to "iorapd",        1071 to "gpu_service",
                1072 to "network_stack", 1073 to "gsid",
                1074 to "fsverity_cert", 1075 to "credstore",
                1076 to "external_storage", 1077 to "ext_data_rw",
                1078 to "ext_obb_rw",    1079 to "reserved_1079",
                2000 to "shell",         2001 to "cache",
                2002 to "diag",
                3001 to "aid_net_bt_admin", 3002 to "aid_net_bt",
                3003 to "aid_inet",      3004 to "aid_net_raw",
                3005 to "aid_net_admin", 3006 to "aid_net_bw_stats",
                3007 to "aid_net_bw_acct", 3008 to "aid_readproc",
                3009 to "aid_wakelock",  3010 to "aid_uhid",
                3011 to "aid_readtracefs", 3012 to "aid_debugfs_restrict",
                9997 to "aid_everybody", 9998 to "aid_misc",
                9999 to "aid_nobody"
            )
            val toAdd = knownGids.filter { (gid, _) -> ":$gid:" !in existing }
                .map { (gid, name) -> "$name:x:$gid:" }
            // Also add dynamic app-specific GIDs
            try {
                val uid = android.os.Process.myUid()
                val dynamicGids = listOf(
                    "u0_a${uid - 10000}:x:$uid:",
                    "all_a${uid - 10000}:x:${uid + 10000}:",
                    "cache_$uid:x:${uid + 40000}:"
                ).filter { entry -> ":${entry.split(":")[2]}:" !in existing }
                val allNew = toAdd + dynamicGids
                if (allNew.isNotEmpty()) groupFile.appendText(allNew.joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                if (toAdd.isNotEmpty()) groupFile.appendText(toAdd.joinToString("\n", postfix = "\n"))
            }
        }

        log("Rootfs patched (DNS, stubs, dpkg, agent defaults, GIDs)")
    }
}
