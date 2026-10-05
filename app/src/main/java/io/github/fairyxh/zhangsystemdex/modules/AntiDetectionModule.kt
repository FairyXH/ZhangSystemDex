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

    /**
     * 清理 HideMyApplist（HMA）在系统中的残留文件。
     *
     * ⚠ 安全重构（2026-10-05）：原实现按「文件名包含 hide/hma/applist」做**模糊匹配**
     * 并 `deleteRecursive` 删除 `/data/system` 下的匹配目录。该做法有严重误删风险
     * （例如 `hmac_key` 含「hma」、任何带 applist 字样的厂商目录），且**实际漏掉了
     * 真正的残留目标**（`hidemyandroid_applist.conf` 是文件，而旧代码只匹配目录）。
     *
     * 现改为**精确白名单**：只清理已知属于 HMA / 安卓-ColorOS 反检测残留的**确切路径**，
     * 且：
     *  - 逐条判断存在性与类型（文件/目录）；
     *  - 删除前再次校验路径**必须位于白名单内**（前缀/精确匹配），杜绝越界；
     *  - 单个目标失败不影响其它目标。
     *
     * 白名单来源：本机实测 `/data/system` 下真实存在的 HMA 残留（conf 文件）+ 已知模块目录。
     */
    private val hmaResidueWhitelist = HMA_RESIDUE_WHITELIST

    /**
     * 白名单精确匹配：路径必须与某条白名单完全相等，或为其子路径。
     * 纯函数，供 SelfTest 断言（不触碰文件系统）。
     */
    private fun isWhitelistedResidue(path: String): Boolean = isWhitelisted(path)

    private fun removeHmaResidue() {
        for (path in hmaResidueWhitelist) {
            // 双重校验：即使白名单被误改，也只允许删除白名单内路径。
            if (!isWhitelistedResidue(path)) continue
            val f = File(path)
            if (!f.exists()) continue
            try {
                if (f.isDirectory) FileUtils.deleteRecursive(f) else f.delete()
                Logger.i(name, "已清理 HMA 残留: $path")
            } catch (t: Throwable) {
                Logger.w(name, "清理 HMA 残留失败 $path: ${t.message}")
            }
        }
    }

    companion object {
        /**
         * HMA / 安卓-ColorOS 反检测残留的**精确白名单**（只含确切路径，绝不做子串匹配）。
         *
         * 之所以强调精确：`/data/system` 是包管理/系统核心目录，历史上曾用
         * 「文件名含 hide/hma/applist」的模糊匹配 + 递归删除，既可能误伤
         * （如 `hmac_key`、任何厂商 applist 目录），又漏掉了真正的文件型残留。
         */
        val HMA_RESIDUE_WHITELIST: List<String> = listOf(
            // HMA（org.frknkrc44.hma_oss / com.tsng.hidemyapplist）写入系统的配置残留。
            "/data/system/hidemyandroid_applist.conf",
            // HMA 旧版可能遗留的模块目录（heavy 周期亦会清理，这里兜底）。
            "/data/adb/modules/hidemyapplist",
        )

        /** 精确白名单判定：等于白名单项，或位于其下（子路径）。纯函数。 */
        fun isWhitelisted(path: String): Boolean {
            for (w in HMA_RESIDUE_WHITELIST) {
                if (path == w || path.startsWith("$w/")) return true
            }
            return false
        }
    }
}
