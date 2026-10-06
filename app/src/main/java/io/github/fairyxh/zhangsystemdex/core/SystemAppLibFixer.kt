package io.github.fairyxh.zhangsystemdex.core

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 修复「被挂载为系统应用」的 APK 内 native 库被压缩导致的 dlopen 失败。
 *
 * ## 真正的根因
 *
 * ZhangProtect 把一批应用挂到模块的 system/app 下（`extractNativeLibs=false`
 * 的系统应用语义）。此时系统**不再**把 APK 内的 lib 解压到 data/app，
 * 应用若通过 `APK!/lib/<abi>` 路径加载 .so，dlopen 会去 **mmap APK 内的条目**。
 *
 * 而 dlopen 只能直接映射 **Stored（未压缩）** 的 zip 条目；若条目是
 * Deflate 压缩的，就会失败：
 *
 *     nativeloader: Load /data/app/.../base.apk!/lib/arm64-v8a/librish.so
 *     dlopen failed: library ".../librish.so" not found
 *     java.lang.UnsatisfiedLinkError
 *
 * 真机实测：Shizuku（moe.shizuku.privileged.api）以标准方案启动时，starter
 * 用 `pm path` 拿到 base.apk，并传 `-Dshizuku.library.path=<apk>!/lib/arm64-v8a`，
 * 于是服务端进程 rikka.shizuku.server.ShizukuService 加载 librish.so 失败，
 * 服务端永远起不来。原 APK 内 12 个 so 全部是 `Defl:N`（压缩率 50~60%）。
 *
 * 对比真系统应用（如 SystemUI）：其 APK 内 so 全部为 `Stored`（0% 压缩），
 * 所以能正常 dlopen。这正是 Android 官方 `extractNativeLibs=false` 的打包要求。
 *
 * ## 本类做什么
 *
 * 扫描 system/app 与 system/priv-app，对每个 APK：
 *   1. 检查所有 `lib/` 下的 .so 条目的压缩方式；
 *   2. 全部已是 Stored 的跳过（幂等）；
 *   3. 否则把该 APK **重打包**：so 条目改为 Stored，其余条目原样搬运。
 *
 * ## 为什么不是「补 lib/ 目录」
 *
 * 早期方案试图在 `<dir>/lib/<abi>/` 放 .so 副本。实测证明该方向错误：
 * Shizuku 的服务端走 APK 内路径加载，与 `/system/app/<pkg>/lib` 无关。
 * 补 lib 目录既不解决 Shizuku，白白增加几十 MB 体积，还会让产物结构混乱。
 *
 * ## 实现要点
 *
 * - 纯 JDK（ZipFile/ZipOutputStream），不依赖外部 unzip/zip。
 * - 未压缩条目用 STORED 写入，CRC 与 size 预先算好，保证 zip 合法。
 * - 重打包先写同目录临时文件，全部成功后再原子替换；失败则回滚保留原文件。
 * - 临时文件与备份文件后缀见 [TMP_SUFFIX] / [BAK_SUFFIX]，均在同目录。
 */
object SystemAppLibFixer {

    /** 需要保持未压缩的条目前缀。 */
    private const val LIB_PREFIX = "lib/"

    /** 临时文件后缀（重打包过程中）。 */
    const val TMP_SUFFIX = ".stored.tmp"

    /** 备份文件后缀（重打包成功后保留，便于排查）。 */
    const val BAK_SUFFIX = ".pre-stored.bak"

    /** 一次修复的统计结果。 */
    data class Result(
        /** 被重打包（修复）的应用数量。 */
        val fixed: Int,
        /** APK 内 so 已是 Stored，跳过。 */
        val skipped: Int,
        /** APK 内没有 native 库，或读取/重打包失败。 */
        val ignored: Int,
        /** 修复的应用名列表。 */
        val fixedNames: List<String>,
    ) {
        val changed: Boolean get() = fixed > 0
    }

    /**
     * 扫描并修复。
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
                for (apk in appDir.listFiles() ?: emptyArray()) {
                    if (!apk.isFile) continue
                    if (!apk.name.endsWith(".apk", ignoreCase = true)) continue
                    if (apk.name.endsWith(TMP_SUFFIX) || apk.name.endsWith(BAK_SUFFIX)) continue

                    when (repackOne(apk, onLog)) {
                        Status.FIXED -> { fixed++; names.add(appDir.name) }
                        Status.SKIPPED -> skipped++
                        Status.IGNORED -> ignored++
                    }
                }
            }
        }
        onLog?.invoke("native lib Stored 修复完成：重打包 $fixed，已是 Stored $skipped，无需/失败 $ignored")
        return Result(fixed, skipped, ignored, names)
    }

    private enum class Status { FIXED, SKIPPED, IGNORED }

    /**
     * 检查单个 APK，必要时重打包为 so 未压缩。
     *
     * 需要重打包当且仅当：APK 内存在 `lib/` 下的 .so，且其中至少一个不是 Stored。
     */
    private fun repackOne(apk: File, onLog: ((String) -> Unit)?): Status {
        return try {
            var soCount = 0
            var compressedSo = 0
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val isSo = e.name.startsWith(LIB_PREFIX) && e.name.endsWith(".so")
                    if (isSo) {
                        soCount++
                        if (e.method != ZipEntry.STORED) compressedSo++
                    }
                }
            }
            if (soCount == 0) return Status.IGNORED
            if (compressedSo == 0) return Status.SKIPPED

            val tmp = File(apk.parentFile, apk.name + TMP_SUFFIX)
            if (tmp.exists()) tmp.delete()

            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                ZipOutputStream(FileOutputStream(tmp).buffered()).use { out ->
                    while (entries.hasMoreElements()) {
                        val src = entries.nextElement()
                        if (src.isDirectory) continue
                        val isSo = src.name.startsWith(LIB_PREFIX) && src.name.endsWith(".so")
                        if (isSo) {
                            // 未压缩写入：预先写死 size / crc，让 ZipOutputStream 用 STORED
                            val data = zip.getInputStream(src).use { it.readBytes() }
                            val ne = ZipEntry(src.name)
                            ne.method = ZipEntry.STORED
                            ne.size = data.size.toLong()
                            ne.compressedSize = data.size.toLong()
                            val crc = CRC32()
                            crc.update(data)
                            ne.crc = crc.value
                            out.putNextEntry(ne)
                            out.write(data)
                            out.closeEntry()
                        } else {
                            val ne = ZipEntry(src.name)
                            if (src.method == ZipEntry.STORED) {
                                ne.method = ZipEntry.STORED
                                ne.size = src.size
                                ne.compressedSize = src.size
                                ne.crc = src.crc
                            }
                            out.putNextEntry(ne)
                            zip.getInputStream(src).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
            }

            // 校验产物可读，再替换
            ZipFile(tmp).use { z ->
                if (!z.entries().hasMoreElements()) throw IllegalStateException("repacked apk empty")
            }

            val bak = File(apk.parentFile, apk.name + BAK_SUFFIX)
            if (bak.exists()) bak.delete()
            if (!apk.renameTo(bak)) {
                tmp.delete()
                throw IllegalStateException("backup rename failed")
            }
            if (!tmp.renameTo(apk)) {
                bak.renameTo(apk) // 回滚
                tmp.delete()
                throw IllegalStateException("replace rename failed")
            }
            onLog?.invoke("已重打包 ${apk.name}（so 改为 Stored）")
            Status.FIXED
        } catch (t: Throwable) {
            onLog?.invoke("重打包 ${apk.name} 失败: ${t.message}")
            runCatching { File(apk.parentFile, apk.name + TMP_SUFFIX).delete() }
            Status.IGNORED
        }
    }
}
