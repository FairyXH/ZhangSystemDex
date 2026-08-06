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

    override fun onStart() {
        Logger.i(name, "config: ${packagesFile.path}")
    }

    override fun tick() {
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
                    Logger.i(name, "learned path $pkg -> $found")
                }
                if (path.isNullOrEmpty()) {
                    Logger.i(name, "no path known yet for $pkg, enable the accessibility service once manually")
                    continue
                }
                if (serviceList.contains(path)) continue
                val updated = (serviceList + path).distinct().joinToString(":")
                SettingsUtils.putSecure("enabled_accessibility_services", updated)
                Logger.i(name, "re-enabled accessibility service $path")
            } catch (t: Throwable) {
                Logger.w(name, "guard $pkg failed: ${t.message}")
            }
        }
    }

    private fun readConfig(): List<String> {
        if (!packagesFile.exists()) return emptyList()
        return packagesFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
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
            Logger.w(name, "save mapping failed: ${t.message}")
        }
    }
}
