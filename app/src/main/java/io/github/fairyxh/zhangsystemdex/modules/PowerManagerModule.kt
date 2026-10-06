package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.BuiltinApps
import io.github.fairyxh.zhangsystemdex.core.BuiltinConfig
import io.github.fairyxh.zhangsystemdex.core.ConfigManager
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Power management merged from DozeListChange.sh, LockedAppsAdd.sh and
 * nightsleep.sh. White-list maintenance and per-vendor locked-app writing are
 * exposed to SystemTuningModule; a nightly Doze loop runs here.
 */
class PowerManagerModule(ctx: DexContext) : DaemonLoop(ctx, 60_000L) {

    private var awake = false
    private val requiredDozePackages = setOf("com.remoteenv.collector","org.localsend.localsend_app")

    override fun onStart() {
        Logger.i(name, "模块启动")
        // 重操作（Doze 白名单 / 多任务 Lock 写 settings）**延后到开机稳定后**在后台执行，
        // 避免与系统开机高峰争抢 system_server 的 Binder 线程池
        //（2026-10-06：曾观察到开机后短时间内大量 Binder 往返与 Watchdog 相关）。
        // 内置应用（system/app/）的 Doze 白名单与多任务 Lock **始终**生效，
        // 不听从 doze_enable / locked_apps_enable（用户要求）。
        val t = Thread({
            try { Thread.sleep(STARTUP_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
            try {
                if (ctx.config.switch("doze_enable")) {
                    applyDozeList()
                } else {
                    applyRequiredDozePackages()
                }
                if (ctx.config.switch("locked_apps_enable")) {
                    applyLockedApps()
                } else {
                    applyBuiltinLockedApps()
                }
            } catch (t2: Throwable) {
                Logger.w(name, "启动期 Doze/Lock 应用失败: ${t2.message}")
            }
        }, "power-startup-apply")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    companion object {
        /** 开机后延迟多久再执行 Doze/Lock 重操作（毫秒）。 */
        private const val STARTUP_DELAY_MS = 20_000L
    }

    /** 内置应用（system/app/）中**启用内置守护**的部分，来源见 [BuiltinConfig]。 */
    private fun builtinPackages(): List<String> = try {
        BuiltinConfig.guardPackages(File(ctx.config.rootDir))
    } catch (t: Throwable) {
        Logger.w(name, "枚举内置应用失败: ${t.message}")
        emptyList()
    }

    /**
     * 必要 Doze 白名单 + **启用内置守护的内置应用**（可按应用独立关闭）。
     * 在 `doze_enable=false` 时调用。
     */
    private fun applyRequiredDozePackages() {
        val pkgs = LinkedHashSet(requiredDozePackages)
        pkgs.addAll(builtinPackages())
        for (pkg in pkgs) {
            FrameworkOps.addPowerSaveWhitelist(pkg)
        }
        Logger.i(name, "必要 + 内置守护应用 Doze 白名单已应用: ${pkgs.size} 个包")
    }

    /**
     * 仅把「启用内置守护」的内置应用写入多任务 Lock（`locked_apps_enable=false` 时调用），
     * 保证「内置应用默认多任务锁定」在详情关闭时仍生效。
     */
    private fun applyBuiltinLockedApps() {
        val pkgs = builtinPackages()
        if (pkgs.isEmpty()) return
        writeLockedApps(pkgs)
        Logger.i(name, "内置守护应用多任务 Lock 已应用: ${pkgs.size} 个包（开关关闭仍生效）")
    }

    override fun tick() {
        if (ctx.config.switch("doze_enable")) {
            nightlyDoze()
        }
    }

    /** White-list maintenance, replacing DozeListChange.sh. */
    fun applyDozeList(xposedModules: List<String> = emptyList()) {
        try {
            // 内置应用 **无条件** 加入（不听从 only_base_enable / doze.conf 配置）。
            val white = LinkedHashSet(buildWhiteList(xposedModules))
            white.addAll(builtinPackages())
            val out = ShellExecutor.run("dumpsys deviceidle whitelist") ?: return
            // 当前已是 user 级白名单的包集合（一次 Binder 读取代替代 N 次写）。
            val currentUser = LinkedHashSet<String>()
            for (line in out.lineSequence()) {
                val idx = line.lastIndexOf(',')
                if (idx < 0) continue
                val entry = line.substring(0, idx).trim()
                val pkg = line.substring(idx + 1).trim()
                if (entry == "user" && pkg.isNotEmpty()) currentUser.add(pkg)
            }
            // 只移除「多余且非目标」的 user 白名单项。
            for (pkg in currentUser) {
                if (!white.contains(pkg)) FrameworkOps.removePowerSaveWhitelist(pkg)
            }
            // **只对差异项**写白名单（避免每次启动 N 次无用 Binder 往返）。
            // 2026-10-06：原实现无条件对全部包调用，在系统 Binder 紧张时会加剧
            // system_server 的 Binder 线程池压力（曾参与触发 Watchdog 软重启）。
            var added = 0
            for (pkg in white) {
                if (pkg in currentUser) continue
                FrameworkOps.addPowerSaveWhitelist(pkg)
                added++
                // 限速：每项之间让出 60ms，避免瞬间打满 system_server Binder 线程池。
                if (added % 8 == 0) try { Thread.sleep(60) } catch (_: InterruptedException) { return }
            }
            Logger.i(name, "Doze 白名单已更新: 目标 ${white.size} 个包，本次新增 $added 个")
        } catch (t: Throwable) {
            Logger.w(name, "Doze 白名单失败: ${t.message}")
        }
    }

    private fun buildWhiteList(xposedModules: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        result.addAll(requiredDozePackages)
        if (ctx.config.switch("only_base_enable")) {
            result.addAll(parseWhiteList(ConfigManager.DEFAULT_DOZE_CONF))
        } else {
            val f = File(ctx.config.rootDir, "doze.conf")
            if (f.exists()) {
                result.addAll(parseWhiteList(f.readText()))
            } else {
                result.addAll(parseWhiteList(ConfigManager.DEFAULT_DOZE_CONF))
            }
        }
        if (ctx.config.switch("read_game_list_enable")) {
            result.addAll(GameListProvider.refresh(true))
        }
        result.addAll(xposedModules)
        return result.toList()
    }

    private fun parseWhiteList(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().removePrefix("+") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /** Locked-app writing for MIUI/ColorOS, replacing LockedAppsAdd.sh. */
    fun applyLockedApps() {
        // 内置应用 **无条件** 加入多任务锁定（不听从 locked_apps_enable 与 doze.conf）。
        val packages = LinkedHashSet(buildWhiteList(emptyList()))
        packages.addAll(builtinPackages())
        writeLockedApps(packages.toList())
    }

    /** 实际写入 MIUI / ColorOS 的多任务锁定数据。 */
    private fun writeLockedApps(packages: List<String>) {
        try {
            // MIUI locked_apps JSON.
            val arr = JSONArray()
            for (pkg in packages) arr.put(pkg)
            val root = JSONObject().put("u", -100).put("pkgs", arr)
            val locked = JSONArray().put(root)
            SettingsUtils.putSystem("locked_apps", locked.toString())
            Logger.i(name, "已写入 MIUI 锁定应用（${arr.length()} 个）")

            // ColorOS launcher lock file.
            val launcherFile = File("/data/user_de/0/com.android.launcher/files/oplus/recenttask/app_lock_data_file_name")
            if (launcherFile.parentFile?.exists() == true) {
                val cArr = JSONArray()
                val mediaDirs = File("/data/media").listFiles { f -> f.isDirectory } ?: emptyArray()
                for (pkg in packages) {
                    for (user in mediaDirs) {
                        cArr.put(JSONObject()
                            .put("features", 0)
                            .put("packageNameUserId", "$pkg#${user.name}")
                            .put("scenes", 2))
                    }
                }
                launcherFile.writeText(cArr.toString())
                Logger.i(name, "已写入 ColorOS 锁定应用（${cArr.length()} 个）")
            }
        } catch (t: Throwable) {
            Logger.w(name, "锁定应用失败: ${t.message}")
        }
    }

    private fun nightlyDoze() {
        try {
            val screenOn = ProcessUtils.isScreenOn()
            if (screenOn) {
                awakeIdle()
                return
            }
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour >= 23 || hour < 8) {
                forceIdle()
            } else {
                awakeIdle()
            }
        } catch (t: Throwable) {
            Logger.w(name, "夜间 Doze 失败: ${t.message}")
        }
    }

    private fun forceIdle() {
        cleanUserWhitelist()
        ShellExecutor.run("dumpsys deviceidle force-idle deep")
        awake = true
    }

    private fun awakeIdle() {
        if (!awake) {
            ShellExecutor.run("dumpsys deviceidle disable all")
            ShellExecutor.run("dumpsys deviceidle enable light")
            ShellExecutor.run("dumpsys deviceidle enable deep")
            FrameworkOps.addPowerSaveWhitelist("com.tencent.mobileqq")
            FrameworkOps.addPowerSaveWhitelist("com.tencent.mm")
            FrameworkOps.addPowerSaveWhitelist("com.alibaba.android.rimet")
            ShellExecutor.run("dumpsys deviceidle motion")
            awake = true
        }
    }

    private fun cleanUserWhitelist() {
        val out = ShellExecutor.run("dumpsys deviceidle whitelist | grep user") ?: return
        for (line in out.lineSequence()) {
            val pkg = line.substringAfterLast(',').trim()
            if (pkg.isNotEmpty()) FrameworkOps.removePowerSaveWhitelist(pkg)
        }
    }
}
