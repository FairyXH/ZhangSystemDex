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
    /** 上次加载游戏列表的时间，用于节流 refresh()。 */
    private var lastRefreshMs: Long = 0L
    override fun onStart() {
        lastRefreshMs = System.currentTimeMillis()
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
        /*
         * 2026-10-22 性能修复：原实现每 5 秒无条件 refresh 一次游戏列表。
         * refresh() 会读 game_pause.conf，并在 read_game_list_enable 打开时
         * 启动 sqlite3 子进程查 OPLUS 游戏库——结果几乎不变，却让本模块
         * 常驻 ~26% 单核。改为 60 秒节流（与 GameOomProtectModule 同口径），
         * 代价只是新装游戏最多晚 60 秒识别，不影响任何已有功能。
         */
        val now = System.currentTimeMillis()
        if (games.isEmpty() || now - lastRefreshMs >= GAME_LIST_TTL_MS) {
            games =
                GameListProvider
                    .refresh(
                        ctx.config.switch("read_game_list_enable")
                    )
                    .toSet()
            lastRefreshMs = now
        }
        val running =
            findRunningGame()
        val runningGame = running?.first
        val runningPids = running?.second ?: emptyList()
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
                boostGame(runningGame, runningPids)
            }
            if (ctx.config.switch("game_oom_protect_enable")) {
                GameOomProtect(runningGame, runningPids)
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
    private fun findRunningGame(): Pair<String, List<Int>>? {
        /*
         * 2026-10-22 性能修复：原实现 for 循环逐个调 ProcessUtils.pidsOf()，
         * 每个都要全扫 /proc（本机约 970 进程），最坏 6 次全扫 / 5 秒。
         * 改 pidsOfAny() 一次扫完，并顺带把 pids 返回，避免后续
         * boostGame()/GameOomProtect() 重复再扫。判据与原实现一致。
         */
        if (games.isEmpty()) return null
        // 一次扫 /proc 匹配全部游戏（而不是每个游戏各扫一次），
        // 再按 games 的顺序挑出第一个在跑的，语义与原实现一致。
        val pidMap = ProcessUtils.pidsOfAny(games)
        for (game in games) {
            val pids = pidMap[game]
            if (!pids.isNullOrEmpty()) {
                return game to pids
            }
        }
        return null
    }
    /**
     * 游戏性能提升
     */
    private fun boostGame(
        pkg: String,
        pids: List<Int>
    ) {
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
        pkg: String,
        pids: List<Int>
    ) {
        pids.forEachIndexed { index, pid ->
            /*
             * 主进程保护，子进程降低保护等级。
             * 用户要求（2026-10-06）：不要 -1000 那么激进（-1000 与 init 同级，
             * 会把游戏钉死在内存里导致系统 OOM/Watchdog 软重启），改为主 -500 / 子 -450。
             * 并**绝不动系统核心进程**。
             */
            if (OomProtectModule.isProtectedSystemProcess(pid)) return@forEachIndexed
            if (index == 0) {
                setOomScoreAdj(
                    pid,
                    OomProtectModule.clampOom(-500)
                )
            } else {
                setOomScoreAdj(
                    pid,
                    OomProtectModule.clampOom(-450)
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

    companion object {
        /** 游戏列表缓存时长：60 秒。 */
        private const val GAME_LIST_TTL_MS = 60_000L
    }
}
