package io.github.fairyxh.zhangsystemdex.modules
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
/**
 * Game process monitor.
 *
 * When a configured game process exists,
 * the global GamePauseCoordinator pauses
 * every other pause-aware module.
 *
 * No longer depends on window focus.
 * A running game process means game mode.
 */
class GamePauseModule(
    ctx: DexContext
) : DaemonLoop(
    ctx,
    5_000L,
    pauseAware = false
) {
    private var games: Set<String> = emptySet()
    private var activeGame: String? = null
    override fun onStart() {
        games =
            GameListProvider
                .refresh(
                    ctx.config.switch("read_game_list_enable")
                )
                .toSet()
        Logger.i(
            name,
            "已加载 ${games.size} 个游戏包"
        )
    }
    override fun tick() {
        games =
            GameListProvider
                .refresh(
                    ctx.config.switch("read_game_list_enable")
                )
                .toSet()
        val runningGame =
            findRunningGame()
        if (runningGame != null) {
            if (activeGame != runningGame) {
                Logger.i(
                    name,
                    "检测到游戏进程: $runningGame，暂停模块循环"
                )
            }
            activeGame = runningGame
            ctx.gamePause.setPaused(true)
            if (ctx.config.switch("boost_game_enable")) {
                boostGame(runningGame)
            }
            if (ctx.config.switch("game_oom_protect_enable")) {
                GameOomProtect(runningGame)
            }
            // Prevent Shizuku residue while game is running.
            FileUtils.rmQuoted(
                "/data/local/tmp/shizuku"
            )
            FileUtils.rmQuoted(
                "/data/local/tmp/shizuku_starter"
            )
        } else {
            if (activeGame != null) {
                Logger.i(
                    name,
                    "游戏进程退出，恢复模块循环"
                )
                activeGame = null
            }
            ctx.gamePause.setPaused(false)
        }
    }
    /**
     * 查找正在运行的游戏进程
     */
    private fun findRunningGame(): String? {
        for (game in games) {
            if (ProcessUtils.pidsOf(game).isNotEmpty()) {
                return game
            }
        }
        return null
    }
    /**
     * 游戏性能提升
     */
    private fun boostGame(pkg: String) {
        val pids =
            ProcessUtils.pidsOf(pkg)
        for (pid in pids) {
            ProcessUtils.renice(
                pid,
                -20
            )
            ShellExecutor.run(
                "chrt -f -p 30 $pid"
            )
            ProcessUtils.appendCgroup(
                pid,
                "/dev/cpuset/top-app/cgroup.procs"
            )
            ProcessUtils.appendCgroup(
                pid,
                "/dev/stune/top-app/cgroup.procs"
            )
        }
        Logger.i(
            name,
            "已提升游戏进程: $pkg"
        )
    }
    /**
     * 游戏OOM保护
     */
    private fun GameOomProtect(
        pkg: String
    ) {
        val pids =
            ProcessUtils.pidsOf(pkg)
        pids.forEachIndexed { index, pid ->
            /*
             * 主进程最高保护
             * 子进程降低保护等级
             */
            if (index == 0) {
                setOomScoreAdj(
                    pid,
                    -1000
                )
            } else {
                setOomScoreAdj(
                    pid,
                    -500
                )
            }
        }
    }
    /**
     * 设置 oom_score_adj
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