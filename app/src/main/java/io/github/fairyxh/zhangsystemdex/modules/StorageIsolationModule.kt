package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Storage-isolation companion: trace cleanup, cleaned-rubbish quarantine and
 * configuration.json generation. Replaces RedirectstorageDataRM.sh,
 * CleanRubbishFile.sh and storage-isolation_config.sh. Configuration JSON is
 * built with org.json, never string concatenation.
 *
 * SAFETY: every entry point is gated by `storage_isolation_enable`. When the
 * switch is false nothing may run, including the debug menu paths and the
 * `/data/media` quarantine logic that moves user directories. Callers (Main,
 * SystemTuningModule, DebugMenu) must also gate their own calls.
 */
class StorageIsolationModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L, pauseAware = false) {
    private val isolationEnabled: Boolean
        get() = ctx.config.switch("storage_isolation_enable")

    private val templateId = "0a40d890-54d2-11ec-52e3-1f3b7540673f"
    private val customDirs = listOf(
        "Android/data", "Android/obb", "Android/media", "Download",
        "Documents", "DCIM", "Pictures", "Movies", "Music",
    )
    private var configTick = 0

    override fun onStart() {
        if (!isolationEnabled) {
            Logger.w(name, "storage_isolation_enable=false，模块禁止运行")
            stop()
            return
        }
        Logger.i(name, "模块启动（storage_isolation_enable=true）")
        removeRedirectStorageTrace()
    }

    override fun tick() {
        if (!isolationEnabled) {
            Logger.w(name, "storage_isolation_enable=false，周期任务被拦截")
            return
        }
        removeRedirectStorageTrace()
        quarantineRubbish()
        configTick++
        if (configTick >= 20) {
            configTick = 0
            if (ctx.config.switch("storage_isolate_all_enable")) generateConfig(allApps = true)
        }
    }

    private fun removeRedirectStorageTrace() {
        if (!isolationEnabled) {
            Logger.w(name, "清理重定向痕迹被拦截（开关关闭）")
            return
        }
        FileUtils.deleteRecursive(File("/data/media/0/Android/data/moe.shizuku.redirectstorage"))
    }

    private fun quarantineRubbish() {
        if (!isolationEnabled) {
            Logger.w(name, "隔离垃圾被拦截（开关关闭）")
            return
        }
        try {
            val cleanedDir = File(ctx.config.rootDir, "CleanedRubbish")
            cleanedDir.mkdirs()
            // Safe part: quarantine module-directory leftovers only.
            val modDir = File(ctx.modDir)
            for (suffix in listOf("bak", "out")) {
                modDir.listFiles { f -> f.name.endsWith(".$suffix") }?.forEach { f ->
                    FileUtils.mvQuoted(f.path, File(cleanedDir, f.name).path)
                }
            }
            val special = setOf("disable", "remove")
            for (f in modDir.listFiles() ?: return) {
                if (f.name in special) {
                    FileUtils.mvQuoted(f.path, File(cleanedDir, f.name).path)
                }
            }
            // HIGH RISK: moves non-standard user directories under /data/media/*.
            // Gated by isolationEnabled; keep the log line for every move.
            val mediaDirs = File("/data/media").listFiles { f -> f.isDirectory } ?: return
            val standard = setOf("Android", "Download", "Documents", "DCIM", "Pictures", "Movies", "Music")
            for (userDir in mediaDirs) {
                for (f in userDir.listFiles() ?: continue) {
                    if (f.name in standard) continue
                    FileUtils.chattr(f.path, "-AacDdijsStu")
                    val dest = File(cleanedDir, f.name)
                    FileUtils.mvQuoted(f.path, dest.path)
                    Logger.w(name, "已隔离 ${f.path} -> ${dest.path}")
                }
            }
        } catch (t: Throwable) {
            Logger.w(name, "隔离失败: ${t.message}")
        }
    }

    fun cleanCleanedRubbish() {
        if (!isolationEnabled) {
            Logger.w(name, "清理隔离区被拦截（开关关闭）")
            return
        }
        try {
            val cleanedDir = File(ctx.config.rootDir, "CleanedRubbish")
            val keep = setOf("Android", "DCIM", "Download")
            for (f in cleanedDir.listFiles() ?: return) {
                if (f.name !in keep) {
                    Logger.w(name, "正在删除 ${f.path}")
                    FileUtils.deleteRecursive(f)
                }
            }
        } catch (t: Throwable) {
            Logger.w(name, "清理垃圾失败: ${t.message}")
        }
    }

    /** Run trace cleanup and rubbish quarantine once (for the debug menu). */
    fun runOnce() {
        if (!isolationEnabled) {
            Logger.w(name, "runOnce 被拦截（storage_isolation_enable=false）")
            return
        }
        Logger.i(name, "存储隔离单次执行（storage_isolation_enable=true）")
        removeRedirectStorageTrace()
        quarantineRubbish()
    }

    fun generateConfig(allApps: Boolean) {
        if (!isolationEnabled) {
            Logger.w(name, "生成配置被拦截（开关关闭）")
            return
        }
        try {
            val targetDir = File("/data/adb/storage-isolation")
            if (!targetDir.exists()) {
                Logger.i(name, "未安装 storage-isolation，跳过配置生成")
                return
            }
            val excluded = setOf(
                "com.android.providers.media.module", "shared-android.media",
                "moe.shizuku.redirectstorage", "com.ktls.fileinfo",
            )
            val packages = if (allApps) {
                AppListProvider.allPackages()
            } else {
                AppListProvider.thirdPartyPackages()
            }.filter { it !in excluded }

            val users = File("/data/user").listFiles { f -> f.isDirectory }?.map { it.name } ?: listOf("0")
            val obj = JSONObject()
            obj.put("kill_media_storage_on_start", false)
            obj.put("observers", JSONArray())
            obj.put("simple_mounts", JSONArray())
            obj.put("version", 21)
            val enhanced = JSONObject()
            enhanced.put("rename_fix", true)
            enhanced.put("app_interaction_fix", true)
            enhanced.put("disable_export_notification", true)
            enhanced.put("package_override", JSONArray())
            obj.put("enhanced_mode", enhanced)
            obj.put("file_monitor", false)
            obj.put("new_app_notification", false)

            val packageArr = JSONArray()
            if (ctx.config.switch("storage_isolate_media_enable")) {
                for (u in users) {
                    for (pkg in listOf("shared-android.media", "com.android.providers.media.module")) {
                        packageArr.put(mountEntry(u, pkg, flags = 3))
                    }
                }
            }
            for (u in users) {
                for (pkg in packages) {
                    packageArr.put(mountEntry(u, pkg, flags = 1))
                }
            }
            obj.put("packages", packageArr)
            obj.put("default_target", "Android/data/%s/sdcard")
            obj.put("misc", JSONObject().put("has_mnt_user_android", "false"))

            val templates = JSONArray()
            templates.put(JSONObject()
                .put("title", "Zhang-Protect")
                .put("id", templateId)
                .put("list", JSONArray(customDirs)))
            obj.put("mount_dirs_templates", templates)

            val targetFile = File(targetDir, "configuration.json")
            targetFile.writeText(obj.toString(2))
            Logger.i(name, "configuration.json 已写入（${packageArr.length()} 条）")
            val daemon = File(targetDir, "bin/daemon")
            if (daemon.exists()) ShellExecutor.run("${daemon.path}")
        } catch (t: Throwable) {
            Logger.w(name, "生成配置失败: ${t.message}")
        }
    }

    private fun mountEntry(userId: String, pkg: String, flags: Int): JSONObject {
        val mount = JSONObject()
        mount.put("template_ids", JSONArray().put(templateId))
        mount.put("flags", flags)
        mount.put("custom_dirs", JSONArray(customDirs))
        return JSONObject()
            .put("mount_dirs", mount)
            .put("user_id", userId.toIntOrNull() ?: 0)
            .put("package_name", pkg)
            .put("enabled", true)
    }
}
