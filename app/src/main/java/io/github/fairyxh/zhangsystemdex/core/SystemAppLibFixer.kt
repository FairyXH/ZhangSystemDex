package io.github.fairyxh.zhangsystemdex.core

import java.io.File
import java.util.zip.ZipFile

/**
 * 为「被挂载为系统应用」的 APK 补齐 native lib 目录。
 *
 * ## 问题
 *
 * ZhangProtect 把一批应用挂到模块的 system/app 目录下（伪装成系统应用
 * 以规避检测）。系统扫描这类应用时，nativeLibraryDir 解析为
 *
 *     /system/app/包名/lib
 *
 * 这与 data/app 下的应用不同 —— 系统应用不会自动解压 APK 内的 lib 目录。
 * 若只放了 APK 没放 lib 目录，该应用任何进程加载自己的 .so 都会失败：
 *
 *     dlopen failed: library ".../lib/arm64-v8a/xxx.so" not found
 *     java.lang.UnsatisfiedLinkError
 *
 * 真机实测：Shizuku（moe.shizuku.privileged.api）被挂载后，其服务端
 * 进程 rikka.shizuku.server.ShizukuService 加载 librish.so 失败，
 * 导致服务端永远起不来，Shizuku 一直显示「服务未运行」。同类问题影响
 * 全部 25 个以上被挂载应用。
 *
 * ## 本类做什么
 *
 * 扫描 system/app 与 system/priv-app，对每个含 APK 的目录：
 *   1. 已有 lib 子目录，跳过（幂等）；
 *   2. APK 内无 native 库，跳过（纯 Java 应用不需要）；
 *   3. 否则解压 lib/arm64-v8a 与 lib/armeabi-v7a 下的 so 到对应目录。
 *
 * 只处理 arm64 与 armeabi-v7a 两种 ABI（占绝大多数设备，且省空间）。
 * 纯 JDK 实现（java.util.zip.ZipFile），不依赖外部 unzip。
 */
object SystemAppLibFixer {

    /** 需要处理的 ABI（按优先级）。 */
    val ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a")

    /** 一次修复的统计结果。 */
    data class Result(
        /** 新补齐 lib 的应用数量。 */
        val fixed: Int,
        /** 已有 lib 而跳过。 */
        val skipped: Int,
        /** 无 native lib 或提取失败。 */
        val ignored: Int,
        /** 补齐的应用名列表。 */
        val fixedNames: List<String>,
    ) {
        val changed: Boolean get() = fixed > 0
    }

    /**
     * 扫描并补齐。
     *
     * @param root 模块（或母版）根目录，须含 `system/app` 或 `system/priv-app`
     * @param onLog 可选日志回调
     */
    fun fix(root: File, onLog: ((String) -> Unit)? = null): Result {
        var fixed = 0
        var skipped = 0
        var ignored = 0
        val names = ArrayList<String>()

        for (sub in listOf("system/app", "system/priv-app")) {
            val base = File(root, sub)
            if (!base.isDirectory) continue
            for (appDir in base.listFiles() ?: emptyArray()) {
                if (!appDir.isDirectory) continue
                val apk = appDir.listFiles()?.firstOrNull {
                    it.isFile && it.name.endsWith(".apk", ignoreCase = true)
                } ?: continue

                when (fillOne(appDir, apk, onLog)) {
                    Status.FIXED -> { fixed++; names.add(appDir.name) }
                    Status.SKIPPED -> skipped++
                    Status.IGNORED -> ignored++
                }
            }
        }
        onLog?.invoke("system/app lib 补齐完成：新增 $fixed，已存在 $skipped，无需 $ignored")
        return Result(fixed, skipped, ignored, names)
    }

    private enum class Status { FIXED, SKIPPED, IGNORED }

    /** 处理单个挂载应用。 */
    private fun fillOne(appDir: File, apk: File, onLog: ((String) -> Unit)?): Status {
        val libDir = File(appDir, "lib")
        if (libDir.isDirectory) return Status.SKIPPED

        return try {
            ZipFile(apk).use { zip ->
                // 先探测 APK 内是否存在本机需要的 ABI 的 lib
                val abis = ABIS.filter { abi ->
                    zip.entries().asSequence().any {
                        it.name.startsWith("lib/$abi/") && it.name.endsWith(".so")
                    }
                }
                if (abis.isEmpty()) return Status.IGNORED

                var wrote = 0
                for (abi in abis) {
                    val outDir = File(libDir, abi)
                    outDir.mkdirs()
                    val prefix = "lib/$abi/"
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        if (!entry.name.startsWith(prefix)) continue
                        if (!entry.name.endsWith(".so")) continue
                        val out = File(outDir, entry.name.removePrefix(prefix))
                        try {
                            zip.getInputStream(entry).use { ins ->
                                out.outputStream().use { outs -> ins.copyTo(outs) }
                            }
                            wrote++
                        } catch (_: Throwable) {
                            // 单个 so 失败不影响其它
                        }
                    }
                }
                if (wrote > 0) {
                    onLog?.invoke("已补齐 ${appDir.name}（$wrote 个 .so）")
                    Status.FIXED
                } else {
                    Status.IGNORED
                }
            }
        } catch (t: Throwable) {
            onLog?.invoke("补齐 ${appDir.name} 失败: ${t.message}")
            Status.IGNORED
        }
    }
}