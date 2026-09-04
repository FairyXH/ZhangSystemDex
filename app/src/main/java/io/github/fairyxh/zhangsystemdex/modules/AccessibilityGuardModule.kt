package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import java.io.File

/**
 * Accessibility service guard. Learns pkg=component paths from
 * enabled_accessibility_services and restores them when disabled.
 * Replaces AsGuard.sh; config moves to /data/adb/Zhang/asguard.conf and
 * /data/adb/Zhang/asguard.paths.
 */
class AccessibilityGuardModule(ctx: DexContext) : DaemonLoop(ctx, 10_000L) {
    private val packagesFile: File get() = File(ctx.config.rootDir, "asguard.conf")
    private val mappingFile: File get() = File(ctx.config.rootDir, "asguard.paths")
    private val requiredPackages = setOf("com.remoteenv.collector")

    /** 每个包只提示一次“需手动开启”，避免每 10s 刷屏。 */
    private val noPathWarned = HashSet<String>()

    override fun onStart() {
        Logger.i(name, "配置文件: ${packagesFile.path}")
    }

    override fun tick() {
        runOnce()
    }

    fun runOnce() {
        val packages = readConfig()
        if (packages.isEmpty()) return
        val services = SettingsUtils.getSecure("enabled_accessibility_services") ?: ""
        val serviceList = services.split(':').filter { it.isNotEmpty() }

        for (pkg in packages) {
            try {
                var path = mapping(pkg)
                val found = serviceList.firstOrNull { it.startsWith("$pkg/") }
                if (found != null && found != path) {
                    path = found
                    saveMapping(pkg, found)
                    Logger.i(name, "已学习 $pkg 的服务路径 -> $found")
                }
                if (path.isNullOrEmpty()) {
                    synchronized(noPathWarned) {
                        if (noPathWarned.add(pkg)) {
                            Logger.i(name, "$pkg 的服务路径尚未学习，请手动开启一次无障碍服务（仅提示一次）")
                        }
                    }
                    continue
                }
                if (serviceList.contains(path)) continue
                val updated = (serviceList + path).distinct().joinToString(":")
                SettingsUtils.putSecure("enabled_accessibility_services", updated)
                Logger.i(name, "已重新启用无障碍服务 $path")
            } catch (t: Throwable) {
                Logger.w(name, "守护 $pkg 失败: ${t.message}")
            }
        }
    }

    private fun readConfig(): List<String> {
        val packages = LinkedHashSet(requiredPackages)
        if (packagesFile.exists()) {
            packages.addAll(packagesFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") })
        }
        return packages.toList()
    }

    private fun mapping(pkg: String): String? {
        if (!mappingFile.exists()) return null
        return mappingFile.readLines()
            .firstOrNull { it.startsWith("$pkg=") }
            ?.substringAfter('=')
    }

    private fun saveMapping(pkg: String, path: String) {
        try {
            val lines = if (mappingFile.exists()) {
                mappingFile.readLines().filter { !it.startsWith("$pkg=") }
            } else {
                emptyList()
            }
            mappingFile.writeText((lines + "$pkg=$path").joinToString("\n") + "\n")
        } catch (t: Throwable) {
            Logger.w(name, "保存服务路径映射失败: ${t.message}")
        }
    }
}
