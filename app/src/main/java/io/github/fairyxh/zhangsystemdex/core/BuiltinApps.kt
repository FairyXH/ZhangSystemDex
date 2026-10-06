package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * 模块自身携带的「内置应用」清单（`<模块目录>/system/app/`）。
 *
 * ## 为什么需要它
 *
 * 用户需求（2026-10-06）：模块内置（随模块挂载为系统应用）的 app 必须
 * **默认保活**，且**不听从任何配置开关**：
 *   - Doze 白名单（电池优化白名单）；
 *   - 多任务 Lock（MIUI `locked_apps` / ColorOS launcher 锁定文件）；
 *   - 通知使用权保活、无障碍服务保活；
 *   - OOM 保护（oom_score_adj 提升）。
 *
 * 这些应用是模块功能的一部分（Shizuku、Scene、GKD、Dhizuku、通知滤盒、
 * Operit AI 等），一旦被系统杀掉/加入 Doze/从最近任务滑掉，模块能力即失效，
 * 因此对它们而言「保活」不是可选项而是前提。
 *
 * ## 包名来源
 *
 * 模块打包规范为 `<模块目录>/system/app/<包名>/<包名>.apk`（见 `pack.sh`）。
 * 因此**父目录名即包名**，无需解析 APK manifest（更稳、无 aapt/framework 依赖）。
 * 兼容大小写扩展名（历史上有 `ac.no.screenshot.Apk`）。
 *
 * 结果带 mtime 缓存：5s/10s/60s 的守护循环反复调用不会反复扫盘。
 */
object BuiltinApps {

    /** 模块内挂载到系统分区的应用目录（`/data/adb/modules/<id>/system/app`）。 */
    fun root(modDir: String): File = File(modDir, "system/app")

    @Volatile
    private var cached: List<String> = emptyList()

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedDir: String = ""

    /**
     * 枚举内置应用包名（已按包名规则校验，去重，按字母序）。
     *
     * 目录不存在或不可读时返回空列表 —— 调用方无需做存在性判断，
     * 空列表即「无内置应用」，所有保活逻辑退化为原行为。
     */
    fun packages(modDir: String): List<String> {
        val dir = root(modDir)
        val mtime = if (dir.exists()) dir.lastModified() else 0L
        if (cachedDir == dir.path && mtime == cachedMtime) return cached
        val out = LinkedHashSet<String>()
        try {
            val subDirs = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (sub in subDirs) {
                if (!OomProtectList.isValidPackage(sub.name)) continue
                if (!hasApk(sub)) continue
                out.add(sub.name)
            }
        } catch (t: Throwable) {
            Logger.w("BuiltinApps", "枚举内置应用失败: ${t.message}")
        }
        cached = out.toList().sorted()
        cachedMtime = mtime
        cachedDir = dir.path
        if (cached.isNotEmpty()) {
            Logger.i("BuiltinApps", "内置应用 ${cached.size} 个: ${cached.joinToString(", ")}")
        }
        return cached
    }

    /** 缓存版本（目录不存在时同样返回空）。 */
    fun packagesCached(modDir: String): List<String> = packages(modDir)

    /**
     * 由配置根推导内置应用清单。
     *
     * 运行目录固定为 `/data/adb/Zhang`，模块目录为 `/data/adb/modules/Zhang`。
     * 为避免各模块构造时都要传入 modDir，这里提供从 rootDir 的反推，
     * 并在失败时回退到约定的模块路径。
     */
    fun packagesFromRoot(rootDir: File): List<String> {
        val candidates = listOf(
            File(rootDir.parentFile ?: File("/data/adb"), "modules/Zhang"),
            File("/data/adb/modules/Zhang"),
        )
        for (c in candidates) {
            val list = packages(c.path)
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** 目录内是否存在 APK 文件（大小写兼容）。 */
    private fun hasApk(dir: File): Boolean = try {
        dir.listFiles()?.any { f ->
            f.isFile && f.name.endsWith(".apk", ignoreCase = true)
        } == true
    } catch (_: Throwable) {
        false
    }

    /** 该内置应用对应的 APK 文件（供 UI 展示）；不存在返回 null。 */
    fun apkOf(modDir: String, pkg: String): File? = try {
        File(root(modDir), pkg).listFiles()?.firstOrNull { f ->
            f.isFile && f.name.endsWith(".apk", ignoreCase = true)
        }
    } catch (_: Throwable) {
        null
    }

    /** 清空缓存（调试/自测用）。 */
    fun invalidate() {
        cachedMtime = -1L
        cachedDir = ""
        cached = emptyList()
    }
}
