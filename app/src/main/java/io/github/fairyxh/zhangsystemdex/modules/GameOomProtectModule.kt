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
        val currentProtected =
            mutableSetOf<String>()
        for (game in games) {
            val pids =
                ProcessUtils.pidsOf(game)
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
        protectedGames.clear()
        protectedGames.addAll(currentProtected)
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
             * 主进程:
             * -1000 最高保护
             *
             * 子进程:
             * -500
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
        Logger.i(
            name,
            "已保护游戏进程: $pkg (${pids.size}个PID)"
        )
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