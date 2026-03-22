package com.mcpshell.shell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Executes commands inside a proot Ubuntu/Debian environment.
 * Ported from AIOPE's aiope_cli.py proot logic into pure Kotlin.
 */
object ProotExecutor {

    private const val TAG = "ProotExecutor"

    fun exec(context: Context, command: String, timeoutMs: Long = 30_000): String {
        val filesDir = context.filesDir.absolutePath
        val envDir = File(filesDir, "env")
        val rootfs = File(envDir, "ubuntu")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val prootBin = File(nativeLibDir, "libproot.so")
        if (!prootBin.exists()) return "Error: proot binary not found at ${prootBin.path}"
        if (!rootfs.isDirectory) return "Error: Ubuntu rootfs not installed at ${rootfs.path}. Run setup first."

        val args = buildProotArgs(rootfs, filesDir, command)
        val env = buildProotEnv(filesDir, nativeLibDir)

        Log.d(TAG, "proot ${args.joinToString(" ")}")

        return try {
            val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()

            val output = ShellExecutor.readStream(process.inputStream, timeoutMs)
            process.waitFor()
            val out = output.trim()
            if (out.length > 8000) out.take(8000) + "\n[truncated]"
            else out.ifEmpty { "(no output, exit ${process.exitValue()})" }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun buildProotArgs(rootfs: File, filesDir: String, command: String): List<String> {
        val args = mutableListOf("--kill-on-exit")

        fun bind(src: String, dst: String? = null) {
            val spec = if (dst != null) "$src:$dst" else src
            args.addAll(listOf("-b", spec))
        }

        // System directories
        for (mnt in listOf("/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                           "/linkerconfig/ld.config.txt",
                           "/linkerconfig/com.android.art/ld.config.txt")) {
            val real = File(mnt).let { if (it.exists()) it.canonicalPath else null }
            if (real != null && File(real).exists()) bind(real)
        }

        bind("/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/sys")
        bind("/proc/self/fd", "/dev/fd")
        bind(filesDir)

        // /dev/shm
        val tmpDir = File(rootfs, "tmp").also { it.mkdirs() }
        bind(tmpDir.absolutePath, "/dev/shm")

        // /dev/stdin/stdout/stderr
        if (File("/proc/self/fd/0").exists()) bind("/proc/self/fd/0", "/dev/stdin")
        if (File("/proc/self/fd/1").exists()) bind("/proc/self/fd/1", "/dev/stdout")
        if (File("/proc/self/fd/2").exists()) bind("/proc/self/fd/2", "/dev/stderr")

        // Fake fips_enabled
        val fipsFile = File(tmpDir, "fips_enabled").also { it.writeText("0\n") }
        bind(fipsFile.absolutePath, "/proc/sys/crypto/fips_enabled")

        args.addAll(listOf(
            "-r", rootfs.absolutePath,
            "-0", "--link2symlink", "--sysvipc", "-L",
            "-w", "/root",
            "/usr/bin/env",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "USER=root", "TERM=xterm-256color",
            "TMPDIR=/tmp", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "/bin/bash", "-c", command
        ))
        return args
    }

    private fun buildProotEnv(filesDir: String, nativeLibDir: String): Map<String, String> {
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to "$filesDir/tmp",
            "LD_LIBRARY_PATH" to filesDir
        )
        val loader = File(nativeLibDir, "libproot.so")
        val loader32 = File(nativeLibDir, "libproot32.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) env["PROOT_LOADER32"] = loader32.absolutePath
        return env
    }
}
