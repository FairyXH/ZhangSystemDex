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
    /**
     * 全局并发上限。**2026-10-06 软重启事故后从 2 收紧到 1（重命令完全串行）**：
     * daemon 有 30+ 个循环线程，各自独立发起 dumpsys/cmd 调用；允许 2 个并发时
     * 实测短时间就有数十条命令同时悬停在 system_server 的 Binder 线程里，
     * 叠加 ColorOS 的 AppBatteryTracker 同一把锁 → Binder 池（上限 16）耗尽 →
     * Watchdog 15s → 杀死 system_server。串行化把峰值压到最低。
     */
    private const val MAX_CONCURRENT = 1
    /** 两次新调用之间的最小间隔（毫秒）。 */
    private const val MIN_INTERVAL_MS = 300L
    /** 内存吃紧时的间隔（毫秒）。 */
    private const val BUSY_INTERVAL_MS = 1200L

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
        // ⓪ **熔断闸门**（2026-10-06 软重启根治）：
        //    重命令（dumpsys / cmd package / cmd appops / settings / pm）最终都在
        //    system_server 内执行。daemon 有 30+ 个循环线程，各自独立发起 IPC，
        //    即便单点做了限流，总量仍会打满 Binder 池 → Watchdog 杀 system_server。
        //    因此在这里做**全局熔断**：一旦系统压力指标超阈值，直接拒绝重命令
        //    （返回 code=-1，调用方按"失败"处理），并进入冷却期。
        //    判定只读 /proc/pressure（纯文件读，零 binder），不会二次加压。
        if (isHeavyCommand(cmd)) {
            val blockedByHot = psiHot()
            if (circuitOpen() || blockedByHot) {
                circuitSkips++
                // 冷却期内静默拒绝；首次提示一次，避免刷屏。
                if (circuitSkips % 200 == 1L) {
                    Logger.w(
                        "ShellExecutor",
                        "熔断保护中（冷却=${circuitOpen()} 内存压力高=$blockedByHot）：拒绝重命令" +
                            "（累计跳过 $circuitSkips 条），cmd=${cmd.take(60)}"
                    )
                }
                return -1 to ""
            }
        }
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
            val t0 = System.currentTimeMillis()
            val r = execLocked(cmd, timeoutMs)
            // ③ 失败/超时的重命令 → 记一次"系统拥塞"证据，触发熔断冷却。
            if (isHeavyCommand(cmd)) {
                val cost = System.currentTimeMillis() - t0
                if (r.first < 0 || (r.first != 0 && cost >= SLOW_CMD_MS)) {
                    onHeavyFailure()
                }
            }
            return r
        } finally {
            semaphore.release()
        }
    }

    // ======================= 熔断保护 =======================

    /** 重命令判定：这些命令的最终执行体是 system_server，会占用其 Binder 线程。 */
    private fun isHeavyCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return HEAVY_PREFIXES.any { c.startsWith(it) } ||
            c.contains("dumpsys ") || c.contains("cmd package ") ||
            c.contains("cmd appops ") || c.contains("cmd notification ") ||
            c.startsWith("pm ") || c.startsWith("am ") || c.contains("settings ")
    }

    /** 熔断是否处于打开（冷却）状态。 */
    private fun circuitOpen(): Boolean {
        val until = circuitUntilMs
        if (until == 0L) return false
        if (System.currentTimeMillis() < until) return true
        circuitUntilMs = 0L // 冷却结束
        return false
    }

    /**
     * 主动预防：PSI 内存压力已经很高（`some avg10 > 阈值`）时，即便还没出现失败，
     * 也直接拒绝重命令。原因：system_server 一旦开始换页/回收，任何 dump 都会
     * 长时间悬停，成为压垮 Binder 池的最后一根稻草。
     *
     * 只读 `/proc/pressure/memory`（零 binder），每 [PSI_CHECK_MS] 才真正读一次文件。
     */
    private fun psiHot(): Boolean {
        val now = System.currentTimeMillis()
        if (now - psiCheckedAt < PSI_CHECK_MS) return psiHotCached
        psiCheckedAt = now
        psiHotCached = psiSome() >= PSI_HOT_AVG10
        return psiHotCached
    }

    @Volatile private var psiCheckedAt: Long = 0L
    @Volatile private var psiHotCached: Boolean = false
    private const val PSI_CHECK_MS = 2000L
    /** PSI `some avg10` 超过该值视为"内存压力高"。 */
    private const val PSI_HOT_AVG10 = 25.0

    /** 记录一次系统拥塞，打开熔断冷却窗（指数退避，上限 120s）。 */
    private fun onHeavyFailure() {
        val now = System.currentTimeMillis()
        if (now - lastFailureAt < 3000L) return // 3s 内只记一次，避免风暴
        lastFailureAt = now
        failStreak = (failStreak + 1).coerceAtMost(12)
        val cool = (COOLDOWN_BASE_MS shl (failStreak - 1)).coerceAtMost(COOLDOWN_MAX_MS)
        circuitUntilMs = now + cool
        Logger.w("ShellExecutor", "检测到 system_server 拥塞（连续 $failStreak 次），熔断 ${cool / 1000}s，期间拒绝重命令")
    }

    /** 读取 PSI 压力值（纯文件读，不产生 binder 流量）。 */
    private fun psiSome(): Double {
        return try {
            val txt = File("/proc/pressure/memory").readText()
            // 形如: some avg10=0.78 avg60=2.42 ...
            Regex("some avg10=([0-9.]+)").find(txt)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    @Volatile private var circuitUntilMs: Long = 0L
    @Volatile private var failStreak: Int = 0
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var circuitSkips: Long = 0L
    private const val SLOW_CMD_MS = 6000L
    private const val COOLDOWN_BASE_MS = 20_000L
    private const val COOLDOWN_MAX_MS = 120_000L
    private val HEAVY_PREFIXES = listOf("dumpsys", "cmd ", "pm ", "am ", "service ", "settings ")

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
