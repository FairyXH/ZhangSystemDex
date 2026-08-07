package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
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

    override fun onStart() {
        Logger.i(name, "模块启动")
        if (ctx.config.switch("doze_enable")) {
            applyDozeList()
        }
        if (ctx.config.switch("locked_apps_enable")) {
            applyLockedApps()
        }
    }

    override fun tick() {
        if (ctx.config.switch("doze_enable")) {
            nightlyDoze()
        }
    }

    /** White-list maintenance, replacing DozeListChange.sh. */
    fun applyDozeList(xposedModules: List<String> = emptyList()) {
        try {
            val white = buildWhiteList(xposedModules)
            val out = ShellExecutor.run("dumpsys deviceidle whitelist") ?: return
            val current = out.lineSequence()
                .mapNotNull { line ->
                    val idx = line.lastIndexOf(',')
                    if (idx >= 0) line.substring(idx + 1).trim().takeIf { it.isNotEmpty() } else null
                }
                .filter { it.startsWith("user,") || true }
                .toList()
            for (entry in current) {
                if (entry.startsWith("user,")) {
                    val pkg = entry.substringAfter(',')
                    if (pkg.isNotEmpty() && !white.contains(pkg)) {
                        FrameworkOps.removePowerSaveWhitelist(pkg)
                    }
                }
            }
            for (pkg in white) {
                FrameworkOps.addPowerSaveWhitelist(pkg)
            }
            Logger.i(name, "Doze 白名单已更新: ${white.size} 个包")
        } catch (t: Throwable) {
            Logger.w(name, "Doze 白名单失败: ${t.message}")
        }
    }

    private fun buildWhiteList(xposedModules: List<String>): List<String> {
        val result = LinkedHashSet<String>()
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
        try {
            val packages = buildWhiteList(emptyList())
            // MIUI locked_apps JSON.
            val arr = JSONArray()
            for (pkg in packages) arr.put(pkg)
            val root = JSONObject().put("u", -100).put("pkgs", arr)
            val locked = JSONArray().put(root)
            SettingsUtils.putSystem("locked_apps", locked.toString())

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
