package io.github.fairyxh.zhangsystemdex.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Last-resort shell execution. Only used when there is no framework/file API
 * equivalent (resetprop persistence, chattr, sync/fstrim, pm/am/service shell
 * paths that fail through framework API first).
 */
object ShellExecutor {
    private const val DEFAULT_TIMEOUT_MS = 15000L

    fun run(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                return null
            }
            p.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        }
    }

    /** Run and return the exit code; -1 when the process could not run/timed out. */
    fun runExit(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                return -1
            }
            p.inputStream.bufferedReader().use { it.readText() }
            p.exitValue()
        } catch (t: Throwable) {
            -1
        }
    }

    fun runBackground(cmd: String) {
        try {
            ProcessBuilder("/system/bin/sh", "-c", cmd).start()
        } catch (t: Throwable) {
            Logger.w("ShellExecutor", "background exec failed: ${t.message}")
        }
    }

    fun fileExists(path: String): Boolean = File(path).exists()
}
