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
                        // Set executable for bin/sbin files
                        if (name.contains("/bin/") || name.contains("/sbin/")) {
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

        // dpkg force-unsafe-io + remove PaxHeaders
        File(rootfs, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfs, "etc/dpkg/dpkg.cfg.d/PaxHeaders").let { if (it.exists()) it.deleteRecursively() }
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
