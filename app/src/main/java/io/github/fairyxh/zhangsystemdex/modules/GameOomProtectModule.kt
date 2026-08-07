package io.github.fairyxh.zhangsystemdex.modules
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
/**
 * Game OOM protect module.
 *
 * Maintain:
 *
 * /proc/<pid>/oom_score_adj
 *
 * for foreground games.
 *
 * Prevent Android LMK from killing important game processes.
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
    private var lastGame: String? = null
    override fun onStart() {
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
        val focus =
            ProcessUtils.focusedPackage()
                ?: return
        if (!games.contains(focus)) {
            if (lastGame != null) {
                Logger.i(
                    name,
                    "离开游戏，停止OOM保护: $lastGame"
                )
                lastGame = null
            }
            return
        }
        if (lastGame != focus) {
            Logger.i(
                name,
                "检测到游戏前台: $focus"
            )
            lastGame = focus
        }
        protectGame(focus)
    }
    /**
     * 每60秒刷新一次游戏列表
     */
    private fun refreshGames(force: Boolean) {
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
    private fun protectGame(pkg: String) {
        val pids =
            ProcessUtils.pidsOf(pkg)
        if (pids.isEmpty()) {
            Logger.w(
                name,
                "未找到游戏进程: $pkg"
            )
            return
        }
        for ((index, pid) in pids.withIndex()) {
            /*
             * 主进程最高保护
             * 子进程降低保护等级
             */
            val adj =
                if (index == 0) {
                    -1000
                } else {
                    -500
                }
            setOomScoreAdj(
                pid,
                adj
            )
        }
    }
    /**
     * 写入 /proc/<pid>/oom_score_adj
     */
    private fun setOomScoreAdj(
        pid: Int,
        value: Int
    ) {
        val cmd =
            "echo $value > /proc/$pid/oom_score_adj"
        ShellExecutor.run(cmd)
        Logger.i(
            name,
            "设置 PID=$pid oom_score_adj=$value"
        )
    }
}