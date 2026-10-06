package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.OomProtectList
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
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
        /** 用户应用允许的最高优先级上限（不得小于此值，否则会超过 system_server）。 */
        const val SAFE_FLOOR = -900

        /** 主进程默认请求值（会被 [clampOom] 钳制到 SAFE_FLOOR）。 */
        const val DEFAULT_MAIN_ADJ = -1000

        /** 子进程默认请求值（更保守，避免抢占系统资源）。 */
        const val DEFAULT_CHILD_ADJ = -700

        /**
         * 把「期望的 oom_score_adj」钳制到安全范围。
         *
         * 规则：不允许 < [SAFE_FLOOR]（即不允许数值更小/优先级更高），
         * 也不允许 > 1000。示例：请求 -1000 → 返回 -900。
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

    override fun onStart() {
        refreshList(force = true)
        Logger.i(name, "OOM 保护名单已加载：${packages.size} 个（安全上限=$SAFE_FLOOR）")
    }

    override fun onStop() {
        // 关闭时把所有改动过的存活进程还原为系统默认，避免残留高优先级。
        val n = touchedPids.size
        for (pid in touchedPids.toList()) {
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
        if (!ctx.config.switch("oom_protect_enable")) {
            // 开关被关闭：若仍有改动过的进程，做一次还原后清空。
            if (touchedPids.isNotEmpty()) {
                for (pid in touchedPids.toList()) {
                    if (ProcessUtils.readFile("/proc/$pid/oom_score_adj") != null) {
                        ProcessUtils.writeFile("/proc/$pid/oom_score_adj", "0")
                    }
                }
                touchedPids.clear()
                lastAdj.clear()
            }
            protected.clear()
            return
        }
        refreshList(force = false)
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
            if (ProcessUtils.readFile("/proc/$pid/oom_score_adj") != null) {
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
    }

    /** 写入 oom_score_adj（仅在变化时写）。 */
    private fun applyAdj(pid: Int, adj: Int) {
        if (lastAdj[pid] == adj) return
        if (ProcessUtils.writeFile("/proc/$pid/oom_score_adj", adj.toString())) {
            lastAdj[pid] = adj
            touchedPids.add(pid)
        }
    }

    private fun restoreAll() {
        for (pid in touchedPids.toList()) {
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