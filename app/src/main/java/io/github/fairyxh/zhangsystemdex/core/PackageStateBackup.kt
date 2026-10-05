package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * 包状态数据库（`packages.xml` / `packages.list` 等）的滚动备份。
 *
 * ## 背景（2026-10-05 开机卡死事故）
 *
 * 任何会改写包状态的操作（`pm uninstall` / `disable` / `enable`）都会让
 * PackageManagerService 重写 `/data/system/packages.xml`。若在写入中途掉电/重启，
 * 该文件可能损坏，**导致系统开机卡死在锁屏**（PMS 无法解析包数据库）。
 *
 * 本工具在**首次**即将改写包状态前，把关键文件复制到
 * `<rootDir>/backup/`，保留最近一份，作为应急恢复的兜底。
 *
 * 只做只读复制，绝不修改系统文件；失败静默（备份不应阻断主流程）。
 */
object PackageStateBackup {

    /** 需要备份的包状态/权限数据库（存在才复制）。 */
    private val TARGETS = listOf(
        "/data/system/packages.xml",
        "/data/system/packages.list",
        "/data/system/packages-backup.xml",
        "/data/system/runtime-permissions.xml",
    )

    @Volatile
    private var doneThisBoot = false

    private val lock = Any()

    /**
     * 本次开机（进程生命周期）只备份一次，避免周期任务反复复制大文件。
     * [rootDir] 为模块配置根（如 /data/adb/Zhang）。
     */
    fun snapshotOnce(rootDir: String) {
        if (doneThisBoot) return
        synchronized(lock) {
            if (doneThisBoot) return
            doneThisBoot = true
            try {
                val destDir = File(rootDir, "backup")
                destDir.mkdirs()
                var copied = 0
                for (path in TARGETS) {
                    val src = File(path)
                    if (!src.isFile) continue
                    val dst = File(destDir, src.name)
                    try {
                        src.copyTo(dst, overwrite = true)
                        copied++
                    } catch (_: Throwable) {
                    }
                }
                if (copied > 0) {
                    Logger.i("PackageStateBackup", "包状态已备份 $copied 个文件 -> ${destDir.path}")
                }
            } catch (t: Throwable) {
                Logger.w("PackageStateBackup", "包状态备份失败（忽略）: ${t.message}")
            }
        }
    }
}