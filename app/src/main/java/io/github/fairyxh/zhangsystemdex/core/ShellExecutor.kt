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

    /**
     * 并发读取子进程输出，避免「管道缓冲写满 → 子进程阻塞 → waitFor 超时」死锁。
     *
     * 这是必须的：`dumpsys notification` 等命令输出可达 1MB+，远超管道缓冲
     * （约 64KB）。若先 waitFor 再读流，子进程写满缓冲后会一直阻塞，
     * waitFor 永远不返回，最终超时并把结果误判为失败。
     *
     * @return (exitCode, output)。超时时 exitCode = -1，output = 已读到的部分。
     */
    private fun exec(cmd: String, timeoutMs: Long): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val sb = StringBuilder()
            // 守护线程负责把 stdout 一次性读完，主线程只等进程结束。
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) { sb.append(buf, 0, n) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                reader.join(1000)
                return -1 to synchronized(sb) { sb.toString() }
            }
            reader.join(3000)
            p.exitValue() to synchronized(sb) { sb.toString() }
        } catch (t: Throwable) {
            -1 to ""
        }
    }

    fun run(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? {
        val (code, out) = exec(cmd, timeoutMs)
        return if (code < 0) null else out
    }

    /** Run and return the exit code; -1 when the process could not run/timed out. */
    fun runExit(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Int =
        exec(cmd, timeoutMs).first

    /** Run and return both the exit code and stdout+stderr (merged). */
    fun runWithCode(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Pair<Int, String?> {
        val (code, out) = exec(cmd, timeoutMs)
        return code to if (code < 0) null else out
    }

    fun runBackground(cmd: String) {
        try {
            ProcessBuilder("/system/bin/sh", "-c", cmd).start()
        } catch (t: Throwable) {
            Logger.w("ShellExecutor", "后台执行失败: ${t.message}")
        }
    }

    fun fileExists(path: String): Boolean = File(path).exists()
}
