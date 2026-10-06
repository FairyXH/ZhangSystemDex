package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import io.github.fairyxh.zhangsystemdex.core.ShizukuResidue
import java.io.File

/**
 * Shizuku 守护：**保活（含服务进程）** + **防检测（/data/local 痕迹清理）**。
 *
 * 由两个独立开关控制，互不影响：
 *   - `shizuku_keepalive_enable`  Shizuku 保活
 *   - `shizuku_detect_enable`     Shizuku 防检测
 *
 * ## 保活（keepalive）
 *
 * Shizuku 运行时有**两个进程**，缺一不可：
 *
 * | 进程 | uid | 作用 |
 * |---|---|---|
 * | `moe.shizuku.privileged.api`（主） | u0_aNNN | UI/管理端 |
 * | `moe.shizuku.privileged.api`（服务） | u999_aNNN 或 root | 实际提权服务端 |
 *
 * 判定「Shizuku 是否真的在工作」不能只看 package 进程是否存在 ——
 * 必须确认**服务端**（uid 999 / root 的那个）也在。检测方式：
 *   1. `pgrep` 包名 → 拿到全部 pid；
 *   2. 读 `/proc/<pid>/status` 的 Uid 行，区分主进程与服务端；
 *   3. 统计到的进程数 < 2 或服务端缺失 → 判定掉线，重启 starter。
 *
 * 重启方式（依次尝试，任一成功即止）：
 *   1. **官方 root 命令**：`<nativeLibraryDir>/libshizuku.so --apk=<sourceDir>`
 *      （源码依据 `Shizuku/manager/.../starter/Starter.kt#internalCommand`，
 *      对应 app 内「Start」按钮的 root 分支；见 docs/SHIZUKU_START_DESIGN.md）
 *   2. 旧 starter 兜底：`/data/local/tmp/shizuku_starter`（adb 模式遗留）
 *   3. 旧 starter 兜底：`/data/local/shizuku_starter`
 *
 * **禁止改 Shizuku 包**：本模块只调用官方入口，不重打包、不替换 APK，
 * 以保证换机通用性与 Shizuku 升级后的可持续性。
 *
 * ## 防检测（detect）
 *
 * 见 [ShizukuResidue]。核心是**精确白名单**清理 `/data/local*` 下的
 * shizuku 痕迹文件；默认不删会影响自启的 starter（需开关解锁）。
 *
 * 注意：Shizuku 每次启动都会重建这些文件，所以防检测是**周期任务**，
 * 只有持续运行才能把「暴露窗口」压到最短。
 */
class ShizukuModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L, pauseAware = false) {

    private var lastKeepAliveMs = 0L
    private var lastDetectMs = 0L
    private var restartCount = 0L
    private var cleanCount = 0L
    private var cleanedBytes = 0L

    /**
     * RuntimeRegistry 的键。Main 在 factory 执行后设置 [registryKey]
     * （值为 ModuleEntry 名，如 `shizuku_guard`）；未设置时回退到类名。
     * 直接用 [name]（`ShizukuModule`）会写到一个未注册的 key，被静默丢弃。
     */
    private val regKey: String get() = registryKey ?: name

    private val keepAliveOn: Boolean get() = ctx.config.switch("shizuku_keepalive_enable")
    private val detectOn: Boolean get() = ctx.config.switch("shizuku_detect_enable")
    private val allowCleanStarter: Boolean get() = ctx.config.switch("shizuku_detect_clean_starter")

    /** 保活检查周期（秒，默认 30）。 */
    private fun keepAliveIntervalMs(): Long =
        (ctx.config.getString("shizuku_keepalive_interval", "30").toIntOrNull() ?: 30)
            .coerceIn(10, 600) * 1000L

    override fun onStart() {
        Logger.i(name, "Shizuku 守护启动（保活=$keepAliveOn，防检测=$detectOn）")
        if (keepAliveOn) checkKeepAlive()
        if (detectOn) cleanResidue()
    }

    override fun tick() {
        val now = System.currentTimeMillis()
        if (keepAliveOn && now - lastKeepAliveMs >= keepAliveIntervalMs()) {
            checkKeepAlive()
        }
        if (detectOn && now - lastDetectMs >= 60_000L) {
            cleanResidue()
        }
        publish()
    }

    // ==================================================================
    // 保活
    // ==================================================================

    /**
     * Shizuku 进程快照。纯函数式解析，便于 SelfTest。
     *
     * @param pids 包名匹配到的全部 pid
     * @param uidOf 读取某 pid 的 uid（注入便于测试）
     */
    data class Snapshot(
        val mainPids: List<Int>,
        val serverPids: List<Int>,
    ) {
        /** 是否「健康」：主进程与服务端都在。 */
        val healthy: Boolean get() = mainPids.isNotEmpty() && serverPids.isNotEmpty()

        /**
         * 服务是否**可用**：只要服务端在，Shizuku 能力即可用（主应用只是 UI/授权前端）。
         *
         * 2026-10-06：本设备以 root starter 起服务端，主应用常年不在内存。
         * 若仅以 [healthy] 判定，会每 30s 无意义重启一次（实测 restartCount 已达 40），
         * 既刷日志又加重负担。改为：**服务端在即视为可用，不触发重启**。
         */
        val serverUsable: Boolean get() = serverPids.isNotEmpty()

        val total: Int get() = mainPids.size + serverPids.size
    }

    companion object {
        /**
         * 服务端进程名。官方 starter 以 `--nice-name=shizuku_server` 启动服务端，
         * 该进程名**不含包名**，因此不能只靠 [ShizukuResidue.PACKAGE] 匹配。
         */
        const val SERVER_PROC = "shizuku_server"

        /** 全部需要纳入快照的进程名（包名 + 服务端进程名）。 */
        val PROC_PATTERNS: List<String> = listOf(ShizukuResidue.PACKAGE, SERVER_PROC)

        /**
         * APK 内 lib 子目录候选（按优先级）。注意目录名与 ABI 名不同：
         * arm64-v8a -> arm64，armeabi-v7a -> arm。
         */
        val ABI_DIRS: List<String> = listOf("arm64", "arm", "x86_64", "x86")

        /**
         * 从 (pid, uid) 列表划分主进程 / 服务端。
         *
         * Android uid 编码为 `userId * 100000 + appId`。Shizuku 两种进程的
         * 实际 uid（真机实测）：
         *
         * | 进程 | 原始 uid | uid % 100000 | 用户段 |
         * |---|---|---|---|
         * | 主应用 | 10335 | 10335 | 0 |
         * | 服务端（root 模式 Launcher） | 99910335 | 10335 | 999 |
         * | 服务端（adb 模式） | 2000 (shell) / 0 (root) | — | — |
         *
         * 因此**不能**用「uid 大小」判断 —— 两者 appId 相同（10335）。
         * 正确规则：
         *   - uid == 0（root）或 uid == 2000（shell）→ **服务端**；
         *   - 否则若 `uid / 100000 >= 900`（即 uid 用户段为 999 之类的特殊用户）
         *     → **服务端**；
         *   - 其余（用户段 0..899，含 userId=0 的普通应用）→ **主进程**。
         *
         * 纯函数，供 SelfTest 断言。
         */
        fun classify(procs: List<Pair<Int, Int>>): Snapshot {
            val main = ArrayList<Int>()
            val server = ArrayList<Int>()
            for ((pid, uid) in procs) {
                if (uid < 0) continue
                val userId = uid / 100000
                val isServer = uid == 0 || uid == 2000 || userId >= 900
                if (isServer) server.add(pid) else main.add(pid)
            }
            return Snapshot(main, server)
        }

        /** 读取 /proc/<pid>/status 的 uid（第一个值）。 */
        fun uidOf(pid: Int): Int {
            val text = ProcessUtils.readFile("/proc/$pid/status") ?: return -1
            for (line in text.lineSequence()) {
                if (line.startsWith("Uid:")) {
                    val parts = line.removePrefix("Uid:").trim().split(Regex("\\s+"))
                    return parts.firstOrNull()?.toIntOrNull() ?: -1
                }
            }
            return -1
        }
    }

    /**
     * 当前快照（真实读取）。
     *
     * 同时匹配**包名**与**服务端进程名**（`shizuku_server`）后的 pid 去重，
     * 再统一交给 [classify] 按 uid 划分。旧实现只看包名 → 服务端永远匹配不到。
     */
    fun snapshot(): Snapshot {
        val pids = LinkedHashSet<Int>()
        for (p in PROC_PATTERNS) pids.addAll(ProcessUtils.pidsOf(p))
        return classify(pids.map { it to uidOf(it) })
    }

    private fun checkKeepAlive() {
        lastKeepAliveMs = System.currentTimeMillis()
        try {
            val snap = snapshot()
            RuntimeRegistry.put(regKey, "mainPids", snap.mainPids.size)
            RuntimeRegistry.put(regKey, "serverPids", snap.serverPids.size)
            if (snap.healthy) {
                RuntimeRegistry.bump(regKey, "keepAliveOk")
                return
            }
            // 服务端在即视为可用：主应用不在内存是常态（root starter 模式），
            // 不必每 30s 重启一次（2026-10-06 修复 restartCount 飙升）。
            if (snap.serverUsable) {
                RuntimeRegistry.bump(regKey, "keepAliveServerOnly")
                return
            }
            Logger.w(
                name,
                "Shizuku 不健康（主=${snap.mainPids} 服务端=${snap.serverPids}），尝试重启",
            )
            RuntimeRegistry.bump(regKey, "keepAliveRestarts")
            if (restartShizuku()) {
                restartCount++
                RuntimeRegistry.put(regKey, "lastRestartMs", System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            Logger.w(name, "Shizuku 保活检查失败: ${t.message}")
        }
    }

    /**
     * 解析 Shizuku 官方 root 启动命令。
     *
     * 对应官方源码 `Shizuku/manager/.../starter/Starter.kt`：
     * ```
     * starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")
     * internalCommand = "$starterFile --apk=${application.applicationInfo.sourceDir}"
     * ```
     * 即 `StartRootViewHolder` -> `StarterActivity(EXTRA_IS_ROOT=true)` 实际执行的命令。
     *
     * 模块运行在 DexContext（无 Android ApplicationInfo），故用 `pm path` 解析
     * `sourceDir`，再以 ABI 目录推导 `nativeLibraryDir`（arm64-v8a -> lib/arm64）。
     *
     * @return 可执行的 shell 命令；null 表示无法解析（未安装 / libshizuku.so 缺失），
     *         调用方应回退旧 starter。
     */
    fun officialStartCommand(): String? {
        return try {
            val out = ShellExecutor.run("pm path ${ShizukuResidue.PACKAGE}", 10_000)
                ?: return null
            val src = out.lineSequence()
                .firstOrNull { it.startsWith("package:") }
                ?.removePrefix("package:")
                ?.trim()
                ?: return null
            if (src.isEmpty() || !src.endsWith(".apk")) return null
            val baseDir = src.substringBeforeLast('/')
            for (abi in ABI_DIRS) {
                val starter = File("$baseDir/lib/$abi/libshizuku.so")
                if (starter.exists()) {
                    return "${quoteArg(starter.path)} --apk=${quoteArg(src)}"
                }
            }
            null
        } catch (t: Throwable) {
            Logger.w(name, "解析官方启动命令失败: ${t.message}")
            null
        }
    }

    /**
     * 重启 Shizuku。
     *
     * 优先级（依次尝试，任一成功即止）：
     *   1. **官方 root 命令**（推荐、稳定、换机通用）：
     *      `<nativeLibraryDir>/libshizuku.so --apk=<sourceDir>`
     *   2. `/data/local/tmp/shizuku_starter`（adb 模式遗留，兜底）
     *   3. `/data/local/shizuku_starter`（旧版遗留，兜底）
     *
     * 成功判据：执行后 [Snapshot.serverPids] 非空（不解析 starter stdout，
     * 避免不同版本输出差异导致误判）。
     */
    fun restartShizuku(): Boolean {
        // ---- 1) 官方 root 命令 ----
        val official = officialStartCommand()
        if (official != null) {
            try {
                Logger.i(name, "执行官方启动命令: $official")
                ShellExecutor.run(official, 20_000)
                sleepSafe(3000)
                if (snapshot().serverPids.isNotEmpty()) {
                    Logger.i(name, "Shizuku 服务端已恢复（官方命令）")
                    return true
                }
                Logger.w(name, "官方命令未拉起服务端，回退旧 starter")
            } catch (t: Throwable) {
                Logger.w(name, "官方命令失败: ${t.message}")
            }
        } else {
            Logger.w(name, "无法解析官方启动命令（可能未安装 Shizuku），回退旧 starter")
        }

        // ---- 2/3) 旧 starter 兜底 ----
        val tmpStarter = File("/data/local/tmp/shizuku_starter")
        try {
            val exported = File("/storage/emulated/0/Android/data/${ShizukuResidue.PACKAGE}/starter")
            if (exported.exists() && exported.length() > 0) {
                if (FileUtils.copyFile(exported, tmpStarter)) {
                    FileUtils.chmod(tmpStarter.path, "700")
                    FileUtils.chown(tmpStarter.path, 2000, 2000)
                }
            }
        } catch (t: Throwable) {
            Logger.w(name, "复制 starter 失败: ${t.message}")
        }
        val candidates = listOf(
            tmpStarter,
            File("/data/local/shizuku_starter"),
        )
        for (c in candidates) {
            if (!c.exists()) continue
            try {
                FileUtils.chmod(c.path, "700")
                Logger.i(name, "执行 starter: ${c.path}")
                ShellExecutor.run("${quoteArg(c.path)} \"\"", 15_000)
                sleepSafe(3000)
                if (snapshot().serverPids.isNotEmpty()) {
                    Logger.i(name, "Shizuku 服务端已恢复（${c.path}）")
                    return true
                }
            } catch (t: Throwable) {
                Logger.w(name, "执行 ${c.path} 失败: ${t.message}")
            }
        }
        return false
    }

    /** shell 单引号转义（路径可能含空格/特殊字符）。 */
    private fun quoteArg(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ==================================================================
    // 防检测
    // ==================================================================

    /**
     * 清理 `/data/local*` 下的 Shizuku 痕迹。
     *
     * 只删 [ShizukuResidue] 白名单内的确切路径；受保护路径（starter、
     * 服务端二进制）需 `shizuku_detect_clean_starter=true` 才删。
     */
    fun cleanResidue(): Int {
        lastDetectMs = System.currentTimeMillis()
        // 防检测开启时，无论是否允许删 starter，都至少清理「纯残留」；
        // 若用户未开防检测但允许删 starter，也只在防检测开启时才动手。
        val allowGuarded = detectOn && allowCleanStarter
        val targets = ShizukuResidue.targets(allowGuarded)
        var removed = 0
        for (path in targets) {
            // 双重校验：即使白名单被改，也只允许删白名单内路径。
            if (!ShizukuResidue.isWhitelisted(path)) continue
            try {
                val f = File(path)
                if (!f.exists()) continue
                val size = f.length()
                val ok = if (f.isDirectory) {
                    FileUtils.deleteRecursive(f)
                    true
                } else {
                    f.delete()
                }
                if (ok) {
                    removed++
                    cleanCount++
                    cleanedBytes += size
                    Logger.i(name, "已清理 Shizuku 痕迹: $path")
                }
            } catch (t: Throwable) {
                Logger.w(name, "清理 $path 失败: ${t.message}")
            }
        }
        if (removed > 0) RuntimeRegistry.bump(regKey, "detectCleaned", removed.toLong())
        return removed
    }

    private fun publish() {
        RuntimeRegistry.put(regKey, "keepaliveEnabled", keepAliveOn)
        RuntimeRegistry.put(regKey, "detectEnabled", detectOn)
        RuntimeRegistry.put(regKey, "restartCount", restartCount)
        RuntimeRegistry.put(regKey, "cleanCount", cleanCount)
        RuntimeRegistry.put(regKey, "cleanedBytes", cleanedBytes)
        RuntimeRegistry.put(regKey, "guardedClean", allowCleanStarter)
    }
}