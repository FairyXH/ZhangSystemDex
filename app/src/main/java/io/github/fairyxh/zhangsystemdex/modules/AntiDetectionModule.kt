package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import java.io.File

/**
 * Anti-root-detection properties and HideMyApplist residue cleanup.
 * Replaces the resetprop block of service.sh and the periodic pihook/HMA
 * sweeps of systemchange.sh.
 */
class AntiDetectionModule(ctx: DexContext) : DaemonLoop(ctx, 300_000L) {
    private data class PropRule(val name: String, val expected: String)
    private data class ContainsRule(val name: String, val contains: String, val newValue: String)

    private val checkRules = listOf(
        PropRule("ro.boot.vbmeta.device_state", "locked"),
        PropRule("ro.boot.verifiedbootstate", "green"),
        PropRule("ro.boot.flash.locked", "1"),
        PropRule("ro.boot.veritymode", "enforcing"),
        PropRule("ro.boot.warranty_bit", "0"),
        PropRule("ro.warranty_bit", "0"),
        PropRule("ro.debuggable", "0"),
        PropRule("ro.force.debuggable", "0"),
        PropRule("ro.secure", "1"),
        PropRule("ro.adb.secure", "1"),
        PropRule("ro.build.type", "user"),
        PropRule("ro.build.tags", "release-keys"),
        PropRule("ro.vendor.boot.warranty_bit", "0"),
        PropRule("ro.vendor.warranty_bit", "0"),
        PropRule("vendor.boot.vbmeta.device_state", "locked"),
        PropRule("vendor.boot.verifiedbootstate", "green"),
        PropRule("sys.oem_unlock_allowed", "0"),
        PropRule("ro.secureboot.lockstate", "locked"),
        PropRule("ro.boot.realmebootstate", "green"),
        PropRule("ro.boot.realme.lockstate", "1"),
    )

    private val containsRules = listOf(
        ContainsRule("ro.bootmode", "recovery", "unknown"),
        ContainsRule("ro.boot.bootmode", "recovery", "unknown"),
        ContainsRule("vendor.boot.bootmode", "recovery", "unknown"),
    )

    override fun onStart() {
        runOnce()
    }

    fun runOnce() {
        Logger.i(name, "正在应用防检测属性规则")
        applyRules()
        PropUtils.delete("persist.sys.vold_app_data_isolation_enabled")
        PropUtils.delete("persist.zygote.app_data_isolation")
    }

    override fun tick() {
        applyRules()
        PropUtils.deleteMatching("pihook|pixelprops")
        PropUtils.delete("persist.sys.vold_app_data_isolation_enabled")
        PropUtils.delete("persist.zygote.app_data_isolation")
        removeHmaResidue()
    }

    private fun applyRules() {
        for (rule in checkRules) {
            try {
                PropUtils.check(rule.name, rule.expected)
            } catch (t: Throwable) {
                Logger.w(name, "规则 ${rule.name} 失败: ${t.message}")
            }
        }
        for (rule in containsRules) {
            try {
                PropUtils.containsReplace(rule.name, rule.contains, rule.newValue)
            } catch (t: Throwable) {
                Logger.w(name, "包含规则 ${rule.name} 失败: ${t.message}")
            }
        }
    }

    private fun removeHmaResidue() {
        val base = File("/data/system")
        val dirs = base.listFiles { f ->
            f.isDirectory && (
                f.name.contains("hide", ignoreCase = true) ||
                    f.name.contains("hma", ignoreCase = true) ||
                    f.name.contains("applist", ignoreCase = true)
                )
        } ?: return
        for (dir in dirs) {
            Logger.i(name, "正在清理 HMA 残留: ${dir.path}")
            FileUtils.deleteRecursive(dir)
        }
    }
}
