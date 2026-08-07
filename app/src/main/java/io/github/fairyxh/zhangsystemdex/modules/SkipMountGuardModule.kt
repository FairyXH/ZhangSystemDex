package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 模块目录防护：监听模块根目录下的指定残留文件（如 skip_mount），发现立即删除。
 *
 * skip_mount 是 Magisk 安装模板可能留下的残留文件：存在时 Magisk 会跳过本模块
 * system/ 覆盖挂载，导致内置系统应用不出现。本模块保证它一旦出现就被清除。
 *
 * 易维护：需要新增监听文件名时，直接在 [WATCH_FILES] 列表中添加一行即可。
 * 本模块不受 powersave_enable 影响（防护逻辑，与省电无关），也不受游戏暂停影响。
 */
class SkipMountGuardModule(ctx: DexContext) : DaemonLoop(ctx, 10_000L, pauseAware = false) {

    companion object {
        /**
         * 需要自动删除的模块目录文件名列表（易维护：按需增删文件名）。
         * 每个名字对应模块根目录（/data/adb/modules/Zhang）下的一个文件，
         * 检测到即删除并记录日志。
         */
        val WATCH_FILES: List<String> = listOf(
            "skip_mount", // Magisk 残留：存在时跳过本模块 system/ 挂载
        )
    }

    override fun onStart() {
        Logger.i(name, "模块目录防护已启动，监听文件: ${WATCH_FILES.joinToString(", ")}")
        runOnce()
    }

    /** 立即执行一次检查并删除匹配文件，返回删除数量。 */
    fun runOnce(): Int {
        val files = File(ctx.modDir).listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.name !in WATCH_FILES) continue
            FileUtils.rmQuoted(f.path)
            if (!f.exists()) {
                removed++
                Logger.w(name, "检测到残留文件 ${f.name}，已删除（若本次开机已跳过 system/ 挂载，重启后恢复）")
            } else {
                Logger.w(name, "删除 ${f.name} 失败（文件仍存在）")
            }
        }
        return removed
    }

    override fun tick() {
        val removed = runOnce()
        if (removed > 0) {
            Logger.i(name, "本轮已删除 $removed 个残留文件")
        }
    }
}
