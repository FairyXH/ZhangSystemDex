package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor

/**
 * Game foreground monitor. When a configured game is focused and the screen is
 * on, the global GamePauseCoordinator pauses every other pause-aware module.
 * Replaces pause_on_game_run_monitor.sh and the duplicated pause_on_game()
 * helpers.
 */
class GamePauseModule(ctx: DexContext) : DaemonLoop(ctx, 180_000L, pauseAware = false) {
    private var games: Set<String> = emptySet()
    private var timeoutCount = 0
    private var activeGame: String? = null

    override fun onStart() {
        games = GameListProvider.refresh(ctx.config.switch("read_game_list_enable")).toSet()
        Logger.i(name, "已加载 ${games.size} 个游戏包")
    }

    override fun tick() {
        games = GameListProvider.refresh(ctx.config.switch("read_game_list_enable")).toSet()
        val focus = ProcessUtils.focusedPackage() ?: return
        val screenOn = ProcessUtils.isScreenOn()

        if (games.contains(focus) && screenOn) {
            if (activeGame != focus) {
                Logger.i(name, "检测到游戏前台: $focus，暂停模块循环")
            }
            activeGame = focus
            timeoutCount = 0
            ctx.gamePause.setPaused(true)
            if (ctx.config.switch("boost_game_enable")) {
                boostGame(focus)
            }
            // Prevent Shizuku residue while the game is running.
            FileUtils.rmQuoted("/data/local/tmp/shizuku")
            FileUtils.rmQuoted("/data/local/tmp/shizuku_starter")
            sleepSafe(60_000)
        } else {
            if (activeGame != null && focus == activeGame && !screenOn) {
                // screen off is not a reason to keep pausing; original logic only
                // resumes when the focused app is no longer the game.
            }
            if (activeGame != null && focus != activeGame) {
                timeoutCount++
                Logger.i(name, "焦点已离开游戏 ($focus)，超时计数=$timeoutCount")
                if (timeoutCount >= 10) {
                    Logger.i(name, "游戏离开前台，恢复模块")
                    activeGame = null
                    timeoutCount = 0
                    ctx.gamePause.setPaused(false)
                }
            } else {
                timeoutCount = 0
            }
        }
        if (activeGame == null) {
            ctx.gamePause.setPaused(false)
        }
    }

    private fun boostGame(pkg: String) {
        for (pid in ProcessUtils.pidsOf(pkg)) {
            ProcessUtils.renice(pid, -20)
            ShellExecutor.run("chrt -f -p 30 $pid")
            ProcessUtils.appendCgroup(pid, "/dev/cpuset/top-app/cgroup.procs")
            ProcessUtils.appendCgroup(pid, "/dev/stune/top-app/cgroup.procs")
        }
        Logger.i(name, "已提升游戏进程: $pkg")
    }
}
