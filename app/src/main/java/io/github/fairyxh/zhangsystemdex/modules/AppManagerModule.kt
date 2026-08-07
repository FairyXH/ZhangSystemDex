package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import java.io.File

/**
 * App management: disable/uninstall of anti-fraud and quick-app packages with
 * module-layer APK shadowing, AppOps allow-all for whitelisted apps, and the
 * copy_mount module overlay rebuild. Replaces disable_apps.sh, AppOpsChange.sh
 * and copy_mount.sh.
 */
class AppManagerModule(private val ctx: DexContext) {

    fun applyDisableApps() {
        try {
            val conf = File(ctx.config.rootDir, "app_manager/disable_app_list.conf")
            val list = readConf(conf, listOf(
                "com.miui.hybrid", "com.android.updater",
                "com.nearme.instant.platform", "com.oplus.ota",
            ))
            for (pkg in list) disableAndShadow(pkg)

            val onlyConf = File(ctx.config.rootDir, "app_manager/disable_app_list_onlydisable.conf")
            val onlyList = readConf(onlyConf, listOf("com.oplus.safecenter", "com.oplus.securitypermission"))
            for (pkg in onlyList) disableApp(pkg)

            copyMount()
        } catch (t: Throwable) {
            Logger.e("AppManager", "停用应用失败", t)
        }
    }

    fun applyAppOps() {
        val conf = File(ctx.config.rootDir, "doze.conf")
        if (!conf.exists()) return
        val packages = conf.readLines()
            .map { it.trim().removePrefix("+") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val groups = permissionGroups()
        for (pkg in packages) {
            if (!AppListProvider.installed(pkg)) continue
            val ops = ShellExecutor.run("appops get $pkg") ?: continue
            for (line in ops.lineSequence()) {
                val op = line.trim().substringBefore(':').trim()
                if (op.isNotEmpty()) FrameworkOps.appOpsSetAllow(pkg, op)
            }
            for (g in groups) {
                if (g.isNotEmpty()) FrameworkOps.grantPermission(pkg, g)
            }
            Logger.i("AppManager", "AppOps/权限放行完成: $pkg")
        }
    }

    private fun permissionGroups(): List<String> {
        val pm = io.github.fairyxh.zhangsystemdex.core.SystemContext.get()?.packageManager
        if (pm != null) {
            try {
                return pm.getAllPermissionGroups(0).mapNotNull { it.name }.filter { it.isNotEmpty() }
            } catch (t: Throwable) {
                Logger.w("AppManager", "权限组枚举失败，降级 shell: ${t.message}")
            }
        }
        return ShellExecutor.run("pm list permissions-group")
            ?.lineSequence()
            ?.map { it.trim().substringAfter(':') }
            ?.filter { it.isNotEmpty() }
            ?.toList() ?: emptyList()
    }

    private fun disableAndShadow(pkg: String) {
        disableApp(pkg)
        val src = AppListProvider.sourceDir(pkg) ?: return
        val dirName = File(src).parent ?: return
        FileUtils.mkdirs(ctx.modDir + dirName)
        FileUtils.touch(ctx.modDir + src)
        Logger.i("AppManager", "已遮蔽 $pkg -> ${ctx.modDir}$src")
    }

    private fun disableApp(pkg: String) {
        ShellExecutor.run("pm uninstall $pkg")
        val users = File("/data/media").listFiles { f -> f.isDirectory } ?: emptyArray()
        for (u in users) {
            FrameworkOps.setApplicationDisabledUser(pkg, u.name.toIntOrNull() ?: 0)
        }
        FrameworkOps.setApplicationEnabled(pkg, false)
        Logger.i("AppManager", "已停用 $pkg")
    }

    /** Rebuild the module overlay: copy top-level module dirs into system/. */
    fun copyMount() {
        val modDir = File(ctx.modDir)
        val systemDir = File(modDir, "system")
        val excluded = setOf("adbtools", "META-INF", "ZhangSetting", "system", "bin")
        val entries = modDir.listFiles { f -> f.isDirectory } ?: return
        for (entry in entries) {
            if (entry.name in excluded) continue
            val dst = File(systemDir, entry.name)
            try {
                FileUtils.deleteRecursive(dst)
                entry.copyRecursively(dst, overwrite = true)
                Logger.i("AppManager", "copy_mount: ${entry.name} -> system/")
            } catch (t: Throwable) {
                Logger.w("AppManager", "copy_mount ${entry.name} 失败: ${t.message}")
            }
        }
    }

    private fun readConf(f: File, defaults: List<String>): List<String> {
        if (f.exists()) {
            return f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        return defaults
    }
}
