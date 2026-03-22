package com.mcpshell.shell

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
                val p = Runtime.getRuntime().exec(arrayOf("tar", "xzf", tarball.absolutePath, "-C", rootfs.absolutePath))
                p.waitFor()
                if (p.exitValue() != 0) {
                    log("ERROR: tar extraction failed (exit ${p.exitValue()})")
                    return false
                }
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

        // Standard dirs
        for (d in listOf("tmp", "var/tmp", "var/cache/apt", "var/lib/apt", "home", "root")) {
            File(rootfs, d).mkdirs()
        }
        File(rootfs, "tmp").setWritable(true, false)

        // Bashrc
        File(rootfs, "root/.bashrc").writeText(
            "export TERM=xterm-256color\nexport LANG=C.UTF-8\nexport LC_ALL=C.UTF-8\n" +
            "export PS1=\"\\[\\033[01;32m\\]\\u@mcpshell\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ \"\n" +
            "alias ls='ls --color=auto'\nalias ll='ls -lh'\n"
        )

        // Proot stubs (ldconfig, invoke-rc.d, start-stop-daemon)
        val stub = "#!/bin/sh\nexit 0\n"
        for (rel in listOf("sbin/ldconfig", "usr/sbin/ldconfig", "usr/sbin/invoke-rc.d",
                           "sbin/start-stop-daemon", "usr/sbin/start-stop-daemon")) {
            val f = File(rootfs, rel)
            if (f.exists()) {
                val bak = File(f.parent, f.name + ".real")
                if (!bak.exists()) f.copyTo(bak)
                f.writeText(stub); f.setExecutable(true)
            }
        }

        // dpkg force-unsafe-io
        File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg.d/00proot").writeText("force-unsafe-io\n")

        // Android GIDs
        val groupFile = File(rootfs, "etc/group")
        if (groupFile.exists()) {
            val existing = groupFile.readText()
            val extras = listOf(
                "aid_inet:x:3003:", "aid_net_raw:x:3004:", "aid_net_admin:x:3005:",
                "aid_everybody:x:9997:", "shell:x:2000:"
            ).filter { ":${it.split(":")[2]}:" !in existing }
            if (extras.isNotEmpty()) groupFile.appendText(extras.joinToString("\n", postfix = "\n"))
        }

        log("Rootfs patched (DNS, stubs, dpkg, GIDs)")
    }
}
