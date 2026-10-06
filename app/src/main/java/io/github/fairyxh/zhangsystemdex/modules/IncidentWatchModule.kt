package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 「事故哨兵」模块（2026-10-06 新增）。
 *
 * ## 为什么需要
 *
 * 排查 system_server Watchdog / 软重启时，以往靠 `Agent 反复执行 dumpsys / logcat /
 * cmd package dump` 取证 —— 而这些命令**本身就是压垮 system_server Binder 线程池的
 * 同类操作**，等于「一边救火一边浇油」。
 *
 * 本模块让**常驻 daemon 自己**在后台用**纯文件读取**（不 fork 任何 shell、不产生 Binder
 * 事务）持续采集事故证据，之后一律通过 HTTP API 读取，彻底摆脱 su 命令行。
 *
 * ## 采集内容（全部零 shell）
 *
 * - **重启检测**：读 `/proc/uptime`，uptime 变小即判定发生重启，记录上一段 uptime 与时刻；
 * - **Watchdog / 软重启**：扫描 `/data/system/dropbox/` 文件名中的
 *   `SYSTEM_SERVER_WATCHDOG` / `SYSTEM_RESTART` / `system_server_pre_watchdog`，
 *   记录时间戳；对新的 `pre_watchdog` 抽取 `Subject:` 首行作为摘要（读取该 gz 的
 *   解压内容由 API 侧按需完成，这里只登记路径与大小）。
 * - **native crash / ANR**：登记 `/data/system/dropbox/`、`/data/anr/` 的新条目。
 * - **内存水位**：读 `/proc/meminfo` 记录 MemAvailable 低点（用于关联换页风暴）。
 *
 * 结果写入 `ctx.config.rootDir/incidents.log`（追加，单行 JSON），由
 * `/api/guard/alerts` 读取返回。
 */
class IncidentWatchModule(ctx: DexContext) : DaemonLoop(ctx, 15_000L) {

    private val dropbox = File("/data/system/dropbox")
    private val anrDir = File("/data/anr")
    private val logFile get() = File(ctx.config.rootDir, "incidents.log")
    private val stateFile get() = File(ctx.config.rootDir, "incidents.state")

    /** 已登记过的文件名集合（避免重复写日志）。 */
    private val seen = HashSet<String>()

    private var lastUptimeSec = 0L

    override fun onStart() {
        Logger.i(name, "事故哨兵启动 — 每 15s 采集 watchdog/crash/重启（零 shell）")
        // 冷启动时把现存条目全部标记为「已见」，只关注启动之后的新事故。
        try {
            dropbox.listFiles()?.forEach { seen.add(it.name) }
            anrDir.listFiles()?.forEach { seen.add("anr/" + it.name) }
        } catch (_: Throwable) {
        }
        lastUptimeSec = readUptimeSec()
    }

    override fun tick() {
        checkReboot()
        scanDir(dropbox, "")
        scanDir(anrDir, "anr/")
        checkMemory()
    }

    private fun readUptimeSec(): Long = try {
        File("/proc/uptime").readText().substringBefore(' ').toDouble().toLong()
    } catch (_: Throwable) {
        0L
    }

    private fun checkReboot() {
        val up = readUptimeSec()
        if (up <= 0) return
        if (lastUptimeSec > 0 && up < lastUptimeSec - 5) {
            record(
                "reboot",
                "检测到重启：uptime ${lastUptimeSec}s → ${up}s（上一段运行约 " +
                    "${lastUptimeSec / 60} 分钟）"
            )
        }
        lastUptimeSec = up
    }

    private fun scanDir(dir: File, prefix: String) {
        val files = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        for (f in files) {
            val key = prefix + f.name
            if (!seen.add(key)) continue
            val n = f.name.lowercase()
            val kind = when {
                n.contains("system_server_watchdog") -> "watchdog"
                n.contains("pre_watchdog") -> "pre_watchdog"
                n.contains("system_restart") -> "restart"
                n.contains("native_crash") -> "native_crash"
                n.contains("system_app_crash") || n.contains("data_app_crash") -> "crash"
                n.contains("anr") -> "anr"
                else -> continue
            }
            record(kind, "${dir.name}/${f.name}  (${f.length()}B)")
        }
    }

    /** 记录内存低点（用于把事故与换页风暴关联）。 */
    private var memLowMark = Long.MAX_VALUE

    private fun checkMemory() {
        val avail = try {
            val mi = File("/proc/meminfo").readText()
            Regex("MemAvailable:\\s+(\\d+) kB").find(mi)?.groupValues?.get(1)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } ?: return
        val availMb = avail / 1024
        // 每下降 200MB 记录一次低点，避免刷屏。
        if (availMb < memLowMark - 200) {
            memLowMark = availMb
            if (availMb < 1200) record("mem_low", "MemAvailable=${availMb}MB")
        }
        // 回升后重置低点锚。
        if (availMb > memLowMark + 600) memLowMark = availMb
    }

    private fun record(kind: String, detail: String) {
        val line = "{\"t\":${System.currentTimeMillis()},\"kind\":\"$kind\"," +
            "\"uptime\":${readUptimeSec()},\"detail\":\"${detail.replace("\"", "'")}\"}"
        Logger.w(name, "[$kind] $detail")
        try {
            logFile.appendText(line + "\n")
            // 控制文件大小：超过 256KB 时截断保留末 64KB。
            if (logFile.length() > 256 * 1024) {
                val keep = logFile.readText().takeLast(64 * 1024)
                logFile.writeText(keep)
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** 供 API 读取最近 N 条记录。 */
        fun readRecent(ctx: DexContext, limit: Int): List<String> {
            val f = File(ctx.config.rootDir, "incidents.log")
            if (!f.exists()) return emptyList()
            return try {
                f.readLines().filter { it.isNotBlank() }.takeLast(limit)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}
