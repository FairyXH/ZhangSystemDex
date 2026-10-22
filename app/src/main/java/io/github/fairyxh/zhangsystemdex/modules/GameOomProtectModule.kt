package io.github.fairyxh.zhangsystemdex.modules

import java.io.File
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
/**
 * Game OOM protect module.
 *
 * Protect game processes by maintaining:
 *
 * /proc/<pid>/oom_score_adj
 *
 * A game process existing means game mode.
 *
 * No window focus detection required.
 */
class GameOomProtectModule(
    ctx: DexContext
) : DaemonLoop(
    ctx,
    5_000L,
    pauseAware = false
) {
    private var games: Set<String> = emptySet()
    private var lastRefreshTime = 0L
    private val protectedGames =
        mutableSetOf<String>()

    /**
     * 我们改过 oom_score_adj 的 pid -> 原值。
     *
     * 2026-10-22 修复存量缺陷：原实现只设置不回收，游戏退出或从列表移除后
     * adj 会永久停在 -500/-450，无关进程变成永远不可杀，长期累积会加剧
     * 内存压力（本机 MemAvailable 长期仅 1~2GB）。这里记录原值，不再
     * 需要保护时精确还原；Map 为空时零额外开销。
     */
    private val rewrittenAdj =
        HashMap<Int, Int>()

    /**
     * 持久化改写记录（pid=原值 每行一条）。
     *
     * 必须落盘，否则 daemon 重启后内存里的原值丢失，之前被改成 -500/-450
     * 的进程就再也没人还原，adj 永久停在负值。
     */
    private val adjStoreFile: File
        get() {
            val root = ctx.config.rootDir
            return File(root, "game_oom_adj.sav")
        }
    override fun onStart() {
        loadAdjStore()
        refreshGames(true)
        Logger.i(
            name,
            "游戏OOM保护启动，游戏数量=${games.size}"
        )
    }
    override fun tick() {
        if (!ctx.config.switch("game_oom_protect_enable")) {
            return
        }
        refreshGames(false)
        val currentProtected =
            mutableSetOf<String>()
        /*
         * 2026-10-22 性能修复：原实现对每个游戏各调一次 ProcessUtils.pidsOf()，
         * 而 pidsOf 每次都要遍历整个 /proc（本机约 970 个进程）并读 cmdline。
         * 游戏列表 N 个就是 N 次全扫，本模块常驻 ~28% 单核。
         * 改用已有的 pidsOfAny()：一次扫 /proc 匹配全部游戏，复杂度从
         * O(N*进程数) 降为 O(进程数)。判据与 pidsOf 完全一致。
         */
        val pidMap = ProcessUtils.pidsOfAny(games)
        for (game in games) {
            val pids = pidMap[game] ?: emptyList()
            if (pids.isNotEmpty()) {
                currentProtected.add(game)
                if (!protectedGames.contains(game)) {
                    Logger.i(
                        name,
                        "发现游戏进程: $game"
                    )
                }
                protectGame(
                    game,
                    pids
                )
            }
        }
        // 不再受保护的进程：把 adj 还原成我们记录的原值。
        restoreStale(pidMap)
        // 记录变动（新保护的 pid）落盘，供 daemon 重启后仍然能还原。
        if (rewrittenAdj.isNotEmpty()) saveAdjStore()
        protectedGames.clear()
        protectedGames.addAll(currentProtected)
        // Publish live telemetry for the overview page.
        RuntimeRegistry.put("game_oom_protect", "protectedCount", currentProtected.size)
        RuntimeRegistry.put("game_oom_protect", "knownGames", games.size)
        if (currentProtected.isNotEmpty()) {
            RuntimeRegistry.put("game_oom_protect", "protectedList", currentProtected.joinToString(","))
        }
        if (currentProtected.isEmpty()
            && protectedGames.isNotEmpty()
        ) {
            Logger.i(
                name,
                "所有游戏进程退出，停止OOM保护"
            )
        }
    }
    /**
     * 每60秒刷新一次游戏列表
     */
    private fun refreshGames(
        force: Boolean
    ) {
        val now =
            System.currentTimeMillis()
        if (!force &&
            now - lastRefreshTime < 60_000L
        ) {
            return
        }
        games =
            GameListProvider
                .refresh(
                    ctx.config.switch(
                        "read_game_list_enable"
                    )
                )
                .toSet()
        lastRefreshTime = now
        Logger.i(
            name,
            "刷新游戏列表: ${games.size} 个"
        )
    }
    /**
     * 设置游戏进程OOM优先级
     */
    private fun protectGame(
        pkg: String,
        pids: List<Int>
    ) {
        for ((index, pid) in pids.withIndex()) {
            /*
             * 主进程 / 子进程。
             * 用户要求（2026-10-06）：不要 -1000（与 init 同级，会把游戏钉死导致
             * 系统 OOM/Watchdog 软重启），改为主 -500 / 子 -450，并绝不动系统核心进程。
             */
            if (OomProtectModule.isProtectedSystemProcess(pid)) continue
            val adj =
                if (index == 0) {
                    OomProtectModule.clampOom(-500)
                } else {
                    OomProtectModule.clampOom(-450)
                }
            protectPid(
                pid,
                adj
            )
        }
        Logger.i(
            name,
            "已保护游戏进程: $pkg (${pids.size}个PID)"
        )
    }
    /**
     * 还原本轮已不再被保护的 pid 的 oom_score_adj。
     *
     * 只动自己改过的 pid（[rewrittenAdj]）；pid 已消失则直接丢弃记录，
     * 不会误伤复用了同一 pid 的新进程。仅当当前值仍是负数（说明还是
     * 我们设的）时才还原，避免抢回别人的设置。
     */
    private fun restoreStale(
        currentlyProtected: Map<String, List<Int>>
    ) {
        if (rewrittenAdj.isEmpty()) return
        var changed = false
        val keep = HashSet<Int>()
        for (pids in currentlyProtected.values) keep.addAll(pids)
        val it = rewrittenAdj.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (keep.contains(e.key)) continue
            val cur = readOomScoreAdj(e.key)
            if (cur != null && cur != e.value) {
                setOomScoreAdj(e.key, e.value)
            }
            it.remove()
            changed = true
        }
        if (changed) saveAdjStore()
    }

    /** 从磁盘载入上次运行留下的改写记录（跨重启还原必需）。 */
    private fun loadAdjStore() {
        try {
            val f = adjStoreFile
            if (!f.exists()) return
            f.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                val i = t.indexOf('=')
                if (i <= 0) return@forEachLine
                val pid = t.substring(0, i).trim().toIntOrNull()
                val v = t.substring(i + 1).trim().toIntOrNull()
                if (pid != null && v != null && pid > 0) rewrittenAdj[pid] = v
            }
            Logger.i(name, "载入历史 adj 记录 ${rewrittenAdj.size} 条")
        } catch (_: Throwable) {
        }
    }

    /** 把当前改写记录写回磁盘。条目很少，直接覆盖写。 */
    private fun saveAdjStore() {
        try {
            val sb = StringBuilder()
            for ((pid, v) in rewrittenAdj) sb.append(pid).append('=').append(v).append('\n')
            ProcessUtils.writeFile(adjStoreFile.absolutePath, sb.toString())
        } catch (_: Throwable) {
        }
    }

    /** 读 /proc/<pid>/oom_score_adj；进程不存在返回 null。 */
    private fun readOomScoreAdj(pid: Int): Int? {
        val t = ProcessUtils.readFile("/proc/$pid/oom_score_adj") ?: return null
        return t.trim().toIntOrNull()
    }

    /**
     * 施加游戏保护：把 adj 改成目标值，并记录**首次改写前**的原值。
     *
     * 记录规则刻意宽：只要这个 pid 还没有记录，就无条件记下当前值
     * （哪怕它已经是负数——可能是上一次运行遗留的）。
     *
     * 2026-10-22 踩过的坑：早先版本只在 cur >= 0 时记录，结果上一轮遗留的
     * -450 永远不会被记下，也就永远不会被还原，反而变成永久泄漏。
     */
    private fun protectPid(
        pid: Int,
        target: Int
    ) {
        if (!rewrittenAdj.containsKey(pid)) {
            val cur = readOomScoreAdj(pid)
            if (cur != null) rewrittenAdj[pid] = cur
        }
        setOomScoreAdj(pid, target)
    }

    /**
     * 设置 /proc/<pid>/oom_score_adj
     */
    private fun setOomScoreAdj(
        pid: Int,
        value: Int
    ) {
        ShellExecutor.run(
            "echo $value > /proc/$pid/oom_score_adj"
        )
        Logger.i(
            name,
            "设置 PID=$pid oom_score_adj=$value"
        )
    }
}