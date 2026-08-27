package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import io.github.fairyxh.zhangsystemdex.core.SystemContext
import java.io.File
import java.nio.file.Files
import java.util.Locale

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

    /** Discover APK packages under the active Magisk module and retain history. */
    fun refreshModuleAppOpsPackages() {
        val conf = File(ctx.config.rootDir, MODULE_APPOPS_CONF)
        val packages = readPackageHistory(conf).toMutableSet()
        var apkCount = 0
        var packageCount = 0
        try {
            File(ctx.modDir).walkTopDown()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .forEach { apk ->
                    apkCount++
                    val pkg = parseApkPackage(apk) ?: return@forEach
                    if (packages.add(pkg)) packageCount++
                }
            writePackageHistory(conf, packages)
            Logger.i(
                "AppOps",
                "模块 APK 扫描完成: APK=$apkCount，新增包=$packageCount，历史目标=${packages.size}"
            )
        } catch (t: Throwable) {
            Logger.e("AppOps", "模块 APK 扫描失败（保留已有数据库）", t)
        }
    }

    fun applyModuleAppOps() {
        val conf = File(ctx.config.rootDir, MODULE_APPOPS_CONF)
        val packages = readPackageHistory(conf)
        Logger.i("AppOps", "开始处理模块 AppOps: package=${packages.size}")
        var processed = 0
        var missing = 0
        var opSuccess = 0
        var opFailure = 0
        val ops = availableOps()
        for (pkg in packages) {
            try {
                if (!packageExists(pkg)) {
                    missing++
                    Logger.w("AppOps", "包不存在，跳过: $pkg")
                    continue
                }
                processed++
                for (op in ops) {
                    val state = currentOpState(pkg, op)
                    if (state == OpState.ALLOW) continue
                    val result = FrameworkOps.appOpsSetAllow(pkg, op)
                    if (result) {
                        opSuccess++
                    } else {
                        opFailure++
                        Logger.w("AppOps", "OP 授权失败: package=$pkg op=$op")
                    }
                }
                Logger.i("AppOps", "包处理完成: $pkg")
            } catch (t: Throwable) {
                Logger.w("AppOps", "包处理异常，继续下一个: $pkg (${t.message})")
            }
        }
        Logger.i(
            "AppOps",
            "本周期处理完成: package总数=${packages.size}，处理成功=$processed，不存在=$missing，OP成功=$opSuccess，OP失败=$opFailure"
        )
    }

    private enum class OpState { ALLOW, OTHER, UNKNOWN }

    private fun packageExists(pkg: String): Boolean {
        val out = ShellExecutor.runWithCode("pm path '$pkg'", 10000)
        return out.first == 0 && out.second?.lineSequence()?.any { it.trim().startsWith("package:") } == true
    }

    private fun availableOps(): List<String> {
        val aom = SystemContext.get()?.getSystemService(android.content.Context.APP_OPS_SERVICE)
        if (aom != null) {
            try {
                val clazz = Class.forName("android.app.AppOpsManager")
                val method = clazz.methods.firstOrNull {
                    it.name == "getOpStrs" && it.parameterTypes.isEmpty()
                }
                val values = method?.invoke(aom) as? Array<*>
                val result = values?.mapNotNull { it as? String }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?: emptyList()
                if (result.isNotEmpty()) return result
            } catch (t: Throwable) {
                Logger.w("AppOps", "Framework OP 动态枚举失败，使用 cmd appops help: ${t.message}")
            }
        }
        val help = ShellExecutor.run("cmd appops help") ?: return emptyList()
        return help.lineSequence()
            .flatMap { line -> Regex("(?:[a-z][a-z0-9_]*)(?:_[a-z0-9_]+)*").findAll(line) }
            .map { it.value.lowercase(Locale.US) }
            .filter { it != "appops" && it.length > 2 }
            .distinct()
            .toList()
    }

    private fun currentOpState(pkg: String, op: String): OpState {
        val out = ShellExecutor.run("cmd appops get '$pkg' '$op'") ?: return OpState.UNKNOWN
        val line = out.lineSequence().firstOrNull { it.contains(op, ignoreCase = true) } ?: return OpState.UNKNOWN
        return when {
            Regex("\\ballow\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> OpState.ALLOW
            Regex("\\b(default|deny|ignore|foreground|errored)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> OpState.OTHER
            else -> OpState.UNKNOWN
        }
    }

    private fun parseApkPackage(apk: File): String? {
        AppListProvider.packageNameFromApk(apk.path)?.let { return it }
        val aapt = File(ctx.modDir, "aapt")
        if (!aapt.isFile) return null
        val escaped = apk.path.replace("'", "'\\\"'\\\"'")
        val output = ShellExecutor.run("'${aapt.path}' dump badging '$escaped'", 15000) ?: return null
        return Regex("package: name='([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)'")
            .find(output)?.groupValues?.getOrNull(1)
            ?.takeIf { it.matches(PACKAGE_PATTERN) }
    }

    private fun readPackageHistory(conf: File): List<String> {
        if (!conf.exists()) return emptyList()
        return conf.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.matches(PACKAGE_PATTERN) }
            .distinct()
    }

    private fun writePackageHistory(conf: File, packages: Set<String>) {
        val tmp = File(conf.parentFile, "${conf.name}.tmp")
        val text = buildString {
            append("# 模块 APK AppOps 历史授权目标；仅新增，不因单次扫描缺失而删除\n")
            packages.toSortedSet().forEach { append(it).append('\n') }
        }
        tmp.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(conf)) {
            tmp.delete()
            throw IllegalStateException("替换 ${conf.path} 失败")
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

    /**
     * Rebuild the module overlay into system/. Safety rules:
     * - only real directories are considered (a symlink such as
     *   product -> ./system/product must never be treated as a source, or the
     *   target tree would be deleted then re-copied from an empty link);
     * - existing system/ content is merged, never deleted;
     * - product/system_ext/sqlite_lib are Magisk-native overlays/resources and
     *   are not duplicated into system/.
     */
    fun copyMount() {
        val modDir = File(ctx.modDir)
        val systemDir = File(modDir, "system")
        val excluded = setOf(
            "adbtools", "META-INF", "ZhangSetting", "system", "bin", "sqlite_lib",
        )
        val entries = modDir.listFiles { f ->
            f.isDirectory && !Files.isSymbolicLink(f.toPath())
        } ?: return
        for (entry in entries) {
            if (entry.name in excluded) continue
            val dst = File(systemDir, entry.name)
            try {
                dst.mkdirs()
                entry.copyRecursively(dst, overwrite = true)
                Logger.i("AppManager", "copy_mount 合并: ${entry.name} -> system/")
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

    companion object {
        const val MODULE_APPOPS_CONF = "appops_packages.conf"
        val PACKAGE_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")
    }
}
