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
     * 全局 shell 调用节流（2026-10-06 新增）。
     *
     * ## 为什么需要
     *
     * `dumpsys` / `cmd package` 等命令最终在 **system_server** 内执行，并占用其
     * **Binder 线程池**。当短时间并发发起大量此类调用时，system_server 的 Binder
     * 线程会被这些「悬停的 dump」占满，导致：
     *   - `Watchdog$BinderThreadMonitor` 拿不到 Binder 线程 →
     *     `blockUntilThreadAvailable` 等待 15s → **Watchdog 杀死 system_server**
     *     → 系统界面崩溃 / 软重启。
     *
     * 实测（2026-10-06 17:39）：多条 `PackageManagerShellCommand.runDump` 同时卡在
     * `AppBatteryTracker.updateBatteryUsageStatsIfNecessary` 的同一把锁上，即为此症状。
     *
     * ## 策略
     *
     *   1. 同一时刻最多 [MAX_CONCURRENT] 个 shell 进程；
     *   2. 两次**新**调用之间至少间隔 [MIN_INTERVAL_MS]（合并突发）；
     *   3. 系统内存吃紧（MemAvailable 低于阈值）时把间隔放大到 [BUSY_INTERVAL_MS]。
     *
     * 这样即使各模块逻辑同时触发，也不会把 system_server 的 Binder 池打满。
     */
    private const val MAX_CONCURRENT = 2
    private const val MIN_INTERVAL_MS = 120L
    private const val BUSY_INTERVAL_MS = 600L

    private val semaphore = java.util.concurrent.Semaphore(MAX_CONCURRENT)
    private val gateLock = Any()
    @Volatile private var lastLaunchMs = 0L

    /** 系统是否处于内存吃紧（据此放大节流间隔）。 */
    private fun systemBusy(): Boolean = try {
        val mi = File("/proc/meminfo").readText()
        val avail = Regex("MemAvailable:\\s+(\\d+) kB").find(mi)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
        // 可用内存 < 900MB 视为吃紧（设备总内存 ~11GB，正常可用 2GB+）。
        avail < 900L * 1024L
    } catch (_: Throwable) {
        false
    }

    /** 通过节流门：串行化「启动间隙」，避免突发。 */
    private fun throttle() {
        synchronized(gateLock) {
            val gap = if (systemBusy()) BUSY_INTERVAL_MS else MIN_INTERVAL_MS
            val now = System.currentTimeMillis()
            val wait = lastLaunchMs + gap - now
            if (wait > 0) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) { }
            }
            lastLaunchMs = System.currentTimeMillis()
        }
    }

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
        // ① 并发闸门：最多 MAX_CONCURRENT 个 shell 同时运行。
        try {
            semaphore.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1 to ""
        }
        try {
            // ② 启动间隙节流：合并突发，避免瞬时打满 system_server 的 Binder 线程池。
            throttle()
            return execLocked(cmd, timeoutMs)
        } finally {
            semaphore.release()
        }
    }

    private fun execLocked(cmd: String, timeoutMs: Long): Pair<Int, String> {
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
