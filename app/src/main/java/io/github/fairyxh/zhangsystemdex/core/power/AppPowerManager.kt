package io.github.fairyxh.zhangsystemdex.core.power

import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * Application-level background policy. On the screen-off / low-battery levels
 * this may restrict *some* background processes, but only those that are
 * explicitly allowed by configuration and never the ones the user whitelisted.
 *
 * Safety rules (long-task brief):
 *  - the protected set (doze whitelist, home launcher, system, the module
 *    itself) is never touched;
 *  - anything we act on is per-action configurable and reversible;
 *  - forceStop is only attempted for packages the config opted in via
 *    `power_bg_stop_list.conf`; an empty list means "do nothing";
 *  - never touch a foreground app (checked via focused package).
 */
class AppPowerManager(private val rootDir: String) {

    @Volatile
    private var lastFocused: String? = null

    /** Packages that must NEVER be restricted. */
    private val protectedPrefixes = listOf(
        "com.android.systemui",
        "com.android.settings",
        "android",
        "com.google.android.gms",
        "com.miui.home",
        "com.oplus.launcher",
        "com.android.launcher",
        "io.github.fairyxh.zhangsystemdex"
    )

    private val stopListFile: File get() = File(rootDir, "power_bg_stop_list.conf")

    private fun loadStopList(): List<String> {
        val f = stopListFile
        if (!f.exists()) {
            try {
                f.writeText("# 息屏/低电量时允许停止的后台应用（每行一个包名，# 为注释）\n# 留空表示不停止任何应用（默认、最保守）\n")
            } catch (_: Throwable) {
            }
            return emptyList()
        }
        return try {
            f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() && it.contains('.') }
        } catch (t: Throwable) {
            Logger.w("AppPowerManager", "读取停止列表失败: ${t.message}")
            emptyList()
        }
    }

    private fun isProtected(pkg: String, whitelist: Set<String>): Boolean {
        if (pkg in whitelist) return true
        if (pkg == lastFocused) return true
        if (!pkg.contains('.')) return true
        return protectedPrefixes.any { pkg == it || pkg.startsWith("$it.") }
    }

    /**
     * Restrict opted-in background apps. `whitelist` is the live doze
     * whitelist (packages the user wants kept alive); `foreground` is the
     * currently focused package.
     *
     * @return number of apps that were actually stopped.
     */
    fun restrictBackground(whitelist: Set<String>, foreground: String?): Int {
        lastFocused = foreground
        val targets = loadStopList()
        if (targets.isEmpty()) return 0
        var stopped = 0
        for (pkg in targets) {
            if (isProtected(pkg, whitelist)) continue
            if (pkg == foreground) continue
            try {
                FrameworkOps.forceStop(pkg)
                stopped++
            } catch (t: Throwable) {
                Logger.w("AppPowerManager", "停止 $pkg 失败: ${t.message}")
            }
        }
        if (stopped > 0) {
            Logger.i("AppPowerManager", "已限制后台应用 $stopped 个（白名单 ${whitelist.size} 个）")
        }
        return stopped
    }

    fun hasTargets(): Boolean = loadStopList().isNotEmpty()
}