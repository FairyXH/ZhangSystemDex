package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.BuiltinConfig
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.OomProtectList
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import java.io.File

/**
 * OOM 保护名单模块（任意应用，用户可编辑）。
 *
 * 与 [GameOomProtectModule] 同源，但作用于用户在 `oom_protect.conf`
 * 中列出的包名，并且**强制安全上限**以保护系统稳定性。
 *
 * ## 安全模型（关键）
 *
 * Android 的 `oom_score_adj` 范围是 -1000..1000，数值越小越不容易被杀。
 * 系统自身核心进程的取值（由 init 设定）：
 * - `init`        ≈ -1000
 * - `system_server` / `zygote` / 持久系统进程 ≈ -900
 *
 * 因此**用户应用绝不允许超过 -900**：即最激进也只能到 -900，
 * 与 system_server 同级但永不越过 init。这样既可显著保活目标应用，
 * 又不会因为比系统核心更"硬"而破坏系统回收链（导致系统不稳定/ANR）。
 *
 * 该钳制在 [clampOom] 中集中实现并被单元测试覆盖。
 *
 * ## 内存泄露 / 无法释放 的处理
 * 1. [lastAdj] 记录每个 pid 上次写入值，避免重复写 sysfs（减少 IO/日志）。
 * 2. 每轮只保留**当前存活**的 pid；进程退出即从所有内部表移除 → 有界。
 * 3. [touchedPids] 记录本模块改动过的 pid；当 pid 不再属于保护名单、
 *    或模块关闭（[onStop]）时，把 `oom_score_adj` **还原为系统默认 0**
 *    （仅当进程仍存活），避免残留高优先级使进程无法被系统回收。
 * 4. [onStop] 清空全部内部结构，不留悬挂引用。
 */
class OomProtectModule(ctx: DexContext) : DaemonLoop(ctx, 5_000L, pauseAware = false) {

    companion object {
        /**
         * 用户应用允许的最高优先级上限（不得小于此值）。
         *
         * 用户要求（2026-10-06）：**不要 -900/-1000 那么激进**，-500 左右即可，
         * 只要不被频繁杀死就行。过高（-900）会把应用钉死在内存里，
         * 导致大内存应用（如 Scene）只增不减 → 系统 OOM → Watchdog 软重启。
         */
        const val SAFE_FLOOR = -500

        /** 主进程默认值。用户要求 ≈ -500。 */
        const val DEFAULT_MAIN_ADJ = -500
        /** 子进程默认值，更保守（仅在主进程基础上略低）。 */
        const val DEFAULT_CHILD_ADJ = -450

        /**
         * **绝不允许触碰的系统核心进程名单**（comm 精确匹配）。
         *
         * 用户明确要求：「坚决不动 system_server 等系统级进程」。
         * 这些进程的 oom_score_adj 由 init/系统管理，模块任何情况下都不得修改。
         */
        val PROTECTED_SYSTEM_COMMS = setOf(
            "init",
            "system_server",
            "zygote",
            "zygote64",
            "zygote32",
            "surfaceflinger",
            "servicemanager",
            "hwservicemanager",
            "vndservicemanager",
            "logd",
            "ueventd",
            "vold",
            "keystore2",
            "apexd",
            "netd",
            "adbd",
            "lmkd",
            "media.codec",
            "media.swcodec",
            "android.hardware.audio.service",
            "vendor.qti.hardware.display.allocator-service",
        )

        /**
         * 该 pid 是否属于「绝不可动」的系统核心进程。
         *
         * 判定依据：`/proc/<pid>/comm`（可执行名）或 cmdline 首段落在
         * [PROTECTED_SYSTEM_COMMS] 中；另外任何 `/system/bin/` 或
         * `/system_ext/bin/` 下的原生服务也不动。
         */
        fun isProtectedSystemProcess(pid: Int): Boolean {
            val comm = ProcessUtils.readFile("/proc/$pid/comm")?.trim().orEmpty()
            if (comm in PROTECTED_SYSTEM_COMMS) return true
            // comm 截断到 15 字符，做前缀兜底（如 android.hardware.audio.service）。
            if (PROTECTED_SYSTEM_COMMS.any { comm.isNotEmpty() && it.startsWith(comm) }) return true
            val rawCmd = ProcessUtils.readFile("/proc/$pid/cmdline")
                ?.replace('\u0000', ' ')?.trim().orEmpty()
            val exe = rawCmd.substringBefore(' ')
            // /system、/vendor 等系统二进制路径 → 系统进程。
            if (exe.isNotEmpty() && (
                    exe.startsWith("/system/bin/") || exe.startsWith("/system_ext/bin/") ||
                        exe.startsWith("/vendor/bin/") || exe.startsWith("/odm/bin/") ||
                        exe.startsWith("/apex/")
                    )
            ) {
                return true
            }
            // 无路径的裸名（如 zygote64/zygote，comm=main）→ 按 arg0/arg1 精确匹配。
            if (exe in PROTECTED_SYSTEM_COMMS) return true
            val arg1 = rawCmd.split(' ').getOrNull(1).orEmpty()
            if (arg1 in PROTECTED_SYSTEM_COMMS) return true
            // comm=main 且 cmdline 含 zygote 关键字（zygote/zygote64 的 comm 均为 main）。
            if (comm == "main" && rawCmd.contains("zygote")) return true
            return false
        }

        /**
         * 内存看门狗触发阈值：物理内存「已用」百分比达到该值即介入。
         *
         * 用户需求（2026-10-06）：95%。但实测 95% 时系统已进入疯狂回收/卡死，
         * 来不及挽救，因此**提前到 90%**（仍保留 95% 的语义为「已触发过」）。
         */
        const val WATCHDOG_MEM_USED_TRIGGER = 90

        /**
         * 判定「模块 OOM 保护是元凶」的最小证据：
         * 受保护进程集合的 RSS 合计占总内存的比例达到该值，或单个受保护进程
         * RSS ≥ 总内存的 [WATCHDOG_SINGLE_SHARE] 时，认为保护对象是高占用主因。
         */
        const val WATCHDOG_TOTAL_SHARE = 40   // 受保护进程合计 ≥ 总内存 40%
        const val WATCHDOG_SINGLE_SHARE = 25  // 单个受保护进程 ≥ 总内存 25%

        /** 触发后冷却（毫秒），避免连续轮询反复杀进程。 */
        const val WATCHDOG_COOLDOWN_MS = 60_000L

        /**
         * 把「期望的 oom_score_adj」钳制到安全范围。
         *
         * 规则：不允许 < [SAFE_FLOOR]（即不允许数值更小/优先级更高），
         * 也不允许 > 1000。示例：请求 -1000 → 返回 -500。
         */
        fun clampOom(requested: Int): Int = requested.coerceIn(SAFE_FLOOR, 1000)
    }

    private var packages: List<String> = emptyList()
    private var lastListRefresh = 0L
    private var lastListMtime = -1L

    /** pid -> 上次写入的 oom_score_adj（避免重复写）。 */
    private val lastAdj = HashMap<Int, Int>()

    /** 本模块改动过的 pid（用于退出/关闭时还原）。 */
    private val touchedPids = HashSet<Int>()
    /** 本轮受保护的应用（去重后的包名）。 */
    private val protected = LinkedHashSet<String>()
    /** 看门狗上次触发时间（冷却用）。 */
    private var lastWatchdogAt = 0L
    /** 看门狗累计触发次数（供状态展示）。 */
    private var watchdogHits = 0L

    override fun onStart() {
        // 组件探测（是否含无障碍/通知）改为**后台异步**，避免在启动路径同步跑
        // N 次 `cmd package dump` 阻塞 system_server（2026-10-06 软重启事故根因）。
        val root = File(ctx.config.rootDir)
        BuiltinConfig.loadForcedCache(root)
        BuiltinConfig.startProbeWorker(root)
        refreshList(force = true)
        Logger.i(name, "OOM 保护名单已加载：${packages.size} 个（安全上限=$SAFE_FLOOR）")
    }

    override fun onStop() {
        // 关闭时把所有改动过的存活进程还原为系统默认，避免残留高优先级。
        val n = touchedPids.size
        for (pid in touchedPids.toList()) {
            // 系统核心进程绝不触碰（其 adj 由系统管理）。
            if (isProtectedSystemProcess(pid)) continue
            if (ProcessUtils.readFile("/proc/$pid/oom_score_adj") != null) {
                ProcessUtils.writeFile("/proc/$pid/oom_score_adj", "0")
            }
        }
        touchedPids.clear()
        lastAdj.clear()
        protected.clear()
        Logger.i(name, "OOM 保护已停止，已还原 $n 个进程")
    }

    override fun tick() {
        // 总开关关闭：用户名单停用，但**模块内置应用**仍必须受保护
        // （用户要求：内置应用的 OOM 保活不听从配置）。
        if (!ctx.config.switch("oom_protect_enable")) {
            val builtin = OomProtectList.builtinPackages(File(ctx.config.rootDir))
            if (builtin.isEmpty()) {
                // 无内置应用：做一次还原后清空（保持原行为）。
                if (touchedPids.isNotEmpty()) restoreAll()
                packages = emptyList()
                protected.clear()
                publish()
                return
            }
            packages = builtin
        } else {
            refreshList(force = false)
        }
        if (packages.isEmpty()) {
            // 名单为空：把所有曾改动的进程还原，避免"改过但不保护"的悬挂状态。
            restoreAll()
            publish()
            return
        }

        val aliveNow = HashSet<Int>()
        val protectedNow = LinkedHashSet<String>()

        val mainAdj = clampOom(readIntConfig("oom_protect_main_adj", DEFAULT_MAIN_ADJ))
        val childAdj = clampOom(readIntConfig("oom_protect_child_adj", DEFAULT_CHILD_ADJ))

        for (pkg in packages) {
            val pids = ProcessUtils.pidsOf(pkg)
            if (pids.isEmpty()) continue
            protectedNow.add(pkg)
            for ((index, pid) in pids.withIndex()) {
                aliveNow.add(pid)
                val adj = if (index == 0) mainAdj else childAdj
                applyAdj(pid, adj)
                // 仅对主进程温和提升 CPU 优先级；不做 RT 抢占。
                if (index == 0) ProcessUtils.renice(pid, -10)
            }
        }

        // 清理：本轮未出现、但之前动过的 pid（进程可能仍在但已移出名单）
        // → 还原并移除，防止"名单删了却仍高优先级"的泄露。
        val stale = touchedPids.filter { it !in aliveNow }
        for (pid in stale) {
            if (!isProtectedSystemProcess(pid) &&
                ProcessUtils.readFile("/proc/$pid/oom_score_adj") != null
            ) {
                ProcessUtils.writeFile("/proc/$pid/oom_score_adj", "0")
            }
            lastAdj.remove(pid)
            touchedPids.remove(pid)
        }
        // 进程已退出的条目从 lastAdj 清除（有界化）。
        lastAdj.keys.retainAll(aliveNow)
        protected.clear()
        protected.addAll(protectedNow)
        publish()
        checkMemoryWatchdog()
    }

    /**
     * 内存看门狗：物理内存「已用」≥ [WATCHDOG_MEM_USED_TRIGGER] 时介入。
     *
     * 判断模块 OOM 保护是否为高占用主因：统计**受保护进程**的 RSS 合计，
     * 若合计占总内存 ≥ [WATCHDOG_TOTAL_SHARE] 或单个受保护进程 ≥
     * [WATCHDOG_SINGLE_SHARE]，则：
     *   1. **取消保护**：把全部被本模块改动过的存活进程 `oom_score_adj` 还原为 0；
     *   2. **终止高占用对象**：强制停止占用最大的受保护应用（force-stop）；
     *   3. 记录日志并进入 [WATCHDOG_COOLDOWN_MS] 冷却。
     *
     * 若高占用不来自受保护进程（系统本身吃满），则**不动手**，仅记录一次告警。
     */
    private fun checkMemoryWatchdog() {
        val used = ProcessUtils.memUsedPercent()
        RuntimeRegistry.put("oom_protect", "memUsedPercent", used)
        if (used < WATCHDOG_MEM_USED_TRIGGER) return
        val now = System.currentTimeMillis()
        if (now - lastWatchdogAt < WATCHDOG_COOLDOWN_MS) return
        lastWatchdogAt = now

        // 统计受保护进程的 RSS（仅统计本模块 touched 的存活进程）。
        val totalKb = ProcessUtils.memTotalKb()
        var sumKb = 0L
        var topPid = -1
        var topKb = 0L
        val alive = touchedPids.filter { ProcessUtils.readFile("/proc/$it/oom_score_adj") != null }
        for (pid in alive) {
            val kb = ProcessUtils.rssKbOf(pid)
            sumKb += kb
            if (kb > topKb) { topKb = kb; topPid = pid }
        }
        val sumShare = if (totalKb > 0) (sumKb * 100 / totalKb).toInt() else 0
        val topShare = if (totalKb > 0) (topKb * 100 / totalKb).toInt() else 0
        val ours = sumShare >= WATCHDOG_TOTAL_SHARE || topShare >= WATCHDOG_SINGLE_SHARE

        RuntimeRegistry.put("oom_protect", "watchdogMemUsed", used)
        RuntimeRegistry.put("oom_protect", "watchdogProtectedShare", sumShare)
        if (!ours) {
            Logger.w(
                name,
                "内存看门狗：物理内存已用 ${used}%（≥${WATCHDOG_MEM_USED_TRIGGER}%）" +
                    "，但受保护进程占比仅 ${sumShare}%（最大单进程 ${topShare}%），非本模块所致，不处理"
            )
            return
        }

        // 找出占用最大的受保护「包名」，用于 force-stop。
        val topPkg = resolvePackageForPid(topPid)
        Logger.w(
            name,
            "内存看门狗触发：物理内存已用 ${used}%，受保护进程合计 ${sumShare}%（最大 ${topShare}%），" +
                "判定为模块 OOM 保护所致 → 取消保护并终止高占用对象（pkg=${topPkg ?: "?"} pid=$topPid rss=${topKb}kB）"
        )
        // 1) 取消保护（还原所有被本模块改动过的存活进程）。
        val n = touchedPids.size
        restoreAll()
        // 2) 终止高占用对象（系统核心进程绝不终止）。
        if (topPid > 0 && isProtectedSystemProcess(topPid)) {
            Logger.w(name, "内存看门狗：高占用对象是系统核心进程（pid=$topPid），不处理")
        } else if (topPkg != null) {
            try {
                FrameworkOps.forceStop(topPkg)
                Logger.w(name, "内存看门狗：已强制停止 $topPkg")
            } catch (t: Throwable) {
                Logger.w(name, "内存看门狗：强制停止 $topPkg 失败: ${t.message}")
            }
        } else if (topPid > 0) {
            ProcessUtils.writeFile("/proc/$topPid/oom_score_adj", "1000")
            ShellExecutor.run("kill -9 $topPid")
            Logger.w(name, "内存看门狗：无法解析包名，直接终止 pid=$topPid（标注 adj=1000）")
        }
        watchdogHits++
        RuntimeRegistry.put("oom_protect", "watchdogHits", watchdogHits)
        Logger.w(name, "内存看门狗：已取消 $n 个进程的保护；本轮处理完成")
    }

    /** 由 pid 反查所属包名（读取 /proc/<pid>/cmdline 首段）。 */
    private fun resolvePackageForPid(pid: Int): String? {
        if (pid <= 0) return null
        val cmd = ProcessUtils.readFile("/proc/$pid/cmdline") ?: return null
        val first = cmd.replace('\u0000', ' ').trim().split(' ').firstOrNull() ?: return null
        return first.takeIf { OomProtectList.isValidPackage(it) }
    }

    /** 写入 oom_score_adj（仅在变化时写）。系统核心进程**绝不触碰**。 */
    private fun applyAdj(pid: Int, adj: Int) {
        // 用户要求：坚决不动 system_server 等系统级进程。
        if (isProtectedSystemProcess(pid)) {
            if (lastAdj.remove(pid) != null) touchedPids.remove(pid)
            return
        }
        if (lastAdj[pid] == adj) return
        if (ProcessUtils.writeFile("/proc/$pid/oom_score_adj", adj.toString())) {
            lastAdj[pid] = adj
            touchedPids.add(pid)
        }
    }

    private fun restoreAll() {
        for (pid in touchedPids.toList()) {
            // 系统核心进程绝不触碰（避免把 system_server 等改回 0）。
            if (isProtectedSystemProcess(pid)) continue
            if (ProcessUtils.readFile("/proc/$pid/oom_score_adj") != null) {
                ProcessUtils.writeFile("/proc/$pid/oom_score_adj", "0")
            }
        }
        touchedPids.clear()
        lastAdj.clear()
    }

    /** mtime 门控的名单刷新（文件没变就不重读）。 */
    private fun refreshList(force: Boolean) {
        // 生效名单 = oom_protect.conf ∪ 通知保活名单 ∪ 无障碍保活名单
        // （保活的应用自动获得 OOM 保护，见 OomProtectList.effectivePackages）
        val files = buildList {
            add(OomProtectList.file(File(ctx.config.rootDir)))
            for (kind in io.github.fairyxh.zhangsystemdex.core.KeepAliveKind.entries) {
                add(io.github.fairyxh.zhangsystemdex.core.KeepAliveList.file(File(ctx.config.rootDir), kind))
            }
        }
        val mtime = files.sumOf { if (it.exists()) it.lastModified() else 0L }
        val now = System.currentTimeMillis()
        if (!force && mtime == lastListMtime && now - lastListRefresh < 60_000L) return
        val list = OomProtectList.effectivePackages(File(ctx.config.rootDir))
        if (list != packages) {
            Logger.i(name, "OOM 保护名单更新：${packages.size} → ${list.size}")
        }
        packages = list
        lastListMtime = mtime
        lastListRefresh = now
    }

    private fun readIntConfig(key: String, def: Int): Int =
        ctx.config.getString(key, def.toString()).toIntOrNull() ?: def

    private fun publish() {
        RuntimeRegistry.put("oom_protect", "protectedCount", protected.size)
        RuntimeRegistry.put("oom_protect", "knownPackages", packages.size)
        RuntimeRegistry.put("oom_protect", "protectedList", protected.joinToString(","))
        RuntimeRegistry.put("oom_protect", "trackedPids", lastAdj.size)
    }

    /** 供 HTTP /api/oom/status 读取当前受保护快照。 */
    fun snapshotProtected(): List<String> = synchronized(protected) { protected.toList() }
}