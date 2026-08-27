package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
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
                applyVendorPermissionDatabases(pkg)
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

    /**
     * Select multiple installed third-party module targets, write several OPs
     * reported by each package, then read the same shell OP names back.
     */
    fun testAppOpsWriteRead(): String {
        val history = readPackageHistory(File(ctx.config.rootDir, MODULE_APPOPS_CONF))
        val thirdParty = AppListProvider.thirdPartyPackages().toSet()
        val thirdPartyTargets = history.filter { it in thirdParty && packageExists(it) }
        val targets = if (thirdPartyTargets.isNotEmpty()) {
            thirdPartyTargets.shuffled().take(APPOPS_TEST_PACKAGE_LIMIT)
        } else {
            history.filter { packageExists(it) }.shuffled().take(APPOPS_TEST_PACKAGE_LIMIT)
        }
        if (targets.isEmpty()) return "跳过：appops_packages.conf 中没有当前已安装目标包"

        val frameworkOps = availableOps()
        val frameworkByShellName = frameworkOps.associateBy { shellOpName(it) }
        val details = mutableListOf<String>()
        var passed = 0
        var failed = 0
        var skipped = 0
        Logger.i("AppOpsTest", "开始多包写入/读取测试：目标=${targets.joinToString(",")}，每包最多 $APPOPS_TEST_OP_LIMIT 个 OP")

        for (pkg in targets) {
            val output = ShellExecutor.run("cmd appops get '$pkg'", 15000).orEmpty()
            val shellOps = output.lineSequence()
                .mapNotNull { APP_OP_LINE.find(it)?.groupValues?.getOrNull(1) }
                .distinct()
                .shuffled()
                .take(APPOPS_TEST_OP_LIMIT)
                .toList()
            if (shellOps.isEmpty()) {
                skipped++
                details += "$pkg：跳过（cmd appops get 未返回可测试 OP）"
                Logger.w("AppOpsTest", "跳过 $pkg：cmd appops get 未返回可测试 OP，输出=${output.ifBlank { "<empty>" }}")
                continue
            }

            for (shellOp in shellOps) {
                val frameworkOp = frameworkByShellName[shellOp]
                if (frameworkOp == null) {
                    skipped++
                    details += "$pkg/$shellOp：跳过（当前 Framework 无对应 OP 字符串）"
                    Logger.w("AppOpsTest", "跳过 $pkg/$shellOp：未映射到 Framework OP 字符串")
                    continue
                }
                val before = ShellExecutor.run("cmd appops get '$pkg' '$shellOp'", 10000)?.trim().orEmpty()
                val writeOk = FrameworkOps.appOpsSetAllow(pkg, frameworkOp)
                val after = ShellExecutor.run("cmd appops get '$pkg' '$shellOp'", 10000)?.trim().orEmpty()
                val readAllow = after.lineSequence().any { line ->
                    line.contains(shellOp, ignoreCase = true) &&
                        Regex("\\ballow\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)
                }
                if (writeOk && readAllow) {
                    passed++
                    details += "$pkg/$shellOp：通过"
                    Logger.i("AppOpsTest", "通过：package=$pkg shellOp=$shellOp frameworkOp=$frameworkOp；写入前=$before；写入后=$after")
                } else {
                    failed++
                    details += "$pkg/$shellOp：失败（writeOk=$writeOk, readAllow=$readAllow）"
                    Logger.w("AppOpsTest", "失败：package=$pkg shellOp=$shellOp frameworkOp=$frameworkOp writeOk=$writeOk readAllow=$readAllow；写入前=$before；写入后=$after")
                }
            }
        }
        val conclusion = when {
            failed == 0 && passed > 0 -> "通过"
            passed > 0 -> "部分通过"
            else -> "失败"
        }
        val report = "AppOps 写入/读取测试报告：结论=$conclusion；目标包=${targets.size}；通过=$passed；失败=$failed；跳过=$skipped；明细=${details.joinToString("；")}" 
        Logger.i("AppOpsTest", report)
        return report
    }

    /** Run vendor permission DB write/read tests across every user and /data/data. */
    fun testVendorPermissionDatabaseWriteRead(): String {
        val pkg = readPackageHistory(File(ctx.config.rootDir, MODULE_APPOPS_CONF))
            .firstOrNull { packageExists(it) }
            ?: return "SKIP: appops_packages.conf 中没有当前已安装目标包"
        val packageBefore = File(ctx.config.rootDir, MODULE_APPOPS_CONF).readText()
        val result = testVendorPermissionDatabaseWriteRead(pkg)
        val packageAfter = File(ctx.config.rootDir, MODULE_APPOPS_CONF).readText()
        if (packageBefore != packageAfter) {
            Logger.w("VendorDbTest", "测试警告: AppOps 目标数据库内容发生变化")
        }
        return "package=$pkg dbResult=$result"
    }

    private fun testVendorPermissionDatabaseWriteRead(pkg: String): String {
        val dbs = vendorPermissionDatabases()
        Logger.i("VendorDbTest", "发现数据库数量=${dbs.size}: ${dbs.map { it.path }}")
        if (dbs.isEmpty()) return "SKIP: 未发现 permission.db"
        var passed = 0
        var failed = 0
        var skipped = 0
        for (db in dbs) {
            try {
                val columns = vendorPermissionColumns(db)
                Logger.i("VendorDbTest", "数据库结构: db=${db.path} columns=$columns")
                val controls = columns.filter {
                    it != "_id" && it != "pkg_name" && it != "state" && it !in VENDOR_META_COLUMNS
                }
                if (!columns.contains("pkg_name") || controls.isEmpty()) {
                    skipped++
                    Logger.w("VendorDbTest", "SKIP: 表结构不支持测试: $db columns=$columns")
                    continue
                }
                val quotedPkg = sqlQuote(pkg)
                val existsBefore = SqliteUtils.queryFirst(
                    db.path,
                    "SELECT pkg_name FROM pp_permission WHERE pkg_name='$quotedPkg' LIMIT 1"
                ).isNotEmpty()
                Logger.i("VendorDbTest", "写入前: db=$db package=$pkg exists=$existsBefore")
                if (!existsBefore && !SqliteUtils.exec(
                        db.path,
                        "INSERT INTO pp_permission (pkg_name) VALUES ('$quotedPkg')"
                    )) {
                    failed++
                    Logger.e("VendorDbTest", "FAIL: 插入测试包失败: $db package=$pkg", null)
                    continue
                }
                val assignments = controls.joinToString(",") { "$it=1" }
                val writeSql = "UPDATE pp_permission SET state=1${if (assignments.isNotEmpty()) ",$assignments" else ""} WHERE pkg_name='$quotedPkg'"
                val writeOk = SqliteUtils.exec(db.path, writeSql)
                Logger.i("VendorDbTest", "写入: db=$db package=$pkg writeOk=$writeOk columns=${controls.size + 1}")
                val verified = if (writeOk) {
                    val row = SqliteUtils.queryFirst(
                        db.path,
                        "SELECT pkg_name FROM pp_permission WHERE pkg_name='$quotedPkg' AND state=1 LIMIT 1"
                    )
                    val controlChecks = controls.map { column ->
                        val value = SqliteUtils.queryFirst(
                            db.path,
                            "SELECT $column FROM pp_permission WHERE pkg_name='$quotedPkg' LIMIT 1"
                        ).firstOrNull()
                        column to value
                    }
                    Logger.i("VendorDbTest", "读取: db=$db packageRow=$row controls=$controlChecks")
                    row.isNotEmpty() && controlChecks.all { it.second == "1" }
                } else false
                if (verified) {
                    passed++
                    Logger.i("VendorDbTest", "PASS: 写入后读取成功: $db package=$pkg columns=${controls.size}")
                } else {
                    failed++
                    Logger.w("VendorDbTest", "FAIL: 写入后读取不一致: $db package=$pkg writeOk=$writeOk")
                }
            } catch (t: Throwable) {
                failed++
                Logger.e("VendorDbTest", "FAIL: 数据库测试异常: $db package=$pkg", t)
            }
        }
        val result = if (failed == 0 && passed > 0 && skipped == 0) "PASS" else if (passed > 0) "PARTIAL" else if (skipped > 0 && failed == 0) "SKIP" else "FAIL"
        Logger.i("VendorDbTest", "完成: result=$result dbPass=$passed dbFail=$failed dbSkip=$skipped total=${dbs.size}")
        return "$result dbPass=$passed dbFail=$failed dbSkip=$skipped total=${dbs.size}"
    }

    private fun vendorPermissionDatabases(): List<File> {
        val dbs = linkedSetOf<File>()
        File("/data/user").listFiles()?.forEach { user ->
            if (user.isDirectory) dbs += File(user, "com.oplus.securitypermission/databases/permission.db")
        }
        dbs += File("/data/data/com.oplus.securitypermission/databases/permission.db")
        return dbs.filter { it.isFile }
    }

    private fun vendorPermissionColumns(db: File): List<String> =
        SqliteUtils.queryFirst(db.path, "SELECT name FROM pragma_table_info('pp_permission')")
            .map { it.trim() }
            .filter { it.matches(SQL_IDENTIFIER) }

    private fun applyVendorPermissionDatabases(pkg: String) {

        val dbs = vendorPermissionDatabases()

        var touched = 0
        for (db in dbs) {
            if (!db.isFile) continue
            try {
                val columns = SqliteUtils.queryFirst(
                    db.path,
                    "SELECT name FROM pragma_table_info('pp_permission')"
                ).filter { it.matches(SQL_IDENTIFIER) }
                if (columns.isEmpty() || !columns.contains("pkg_name")) continue
                val setColumns = columns.filter {
                    it != "_id" && it != "pkg_name" && it != "state" && it !in VENDOR_META_COLUMNS
                }
                if (setColumns.isEmpty() && "state" !in columns) continue
                val assignments = (setColumns + "state").distinct()
                    .filter { it in columns || it == "state" }
                    .joinToString(",") { "$it=1" }
                val quotedPkg = sqlQuote(pkg)
                val exists = SqliteUtils.queryFirst(
                    db.path,
                    "SELECT pkg_name FROM pp_permission WHERE pkg_name='$quotedPkg' LIMIT 1"
                ).isNotEmpty()
                if (!exists && !SqliteUtils.exec(
                        db.path,
                        "INSERT INTO pp_permission (pkg_name) VALUES ('$quotedPkg')"
                    )) {
                    Logger.w("AppOps", "厂商权限数据库新增包失败: ${db.path} package=$pkg")
                    continue
                }
                val sql = "UPDATE pp_permission SET $assignments WHERE pkg_name='$quotedPkg'"
                if (SqliteUtils.exec(db.path, sql)) {
                    touched++
                    Logger.i("AppOps", "厂商权限数据库已更新: ${db.path} package=$pkg")
                } else {
                    Logger.w("AppOps", "厂商权限数据库更新失败: ${db.path} package=$pkg")
                }
            } catch (t: Throwable) {
                Logger.w("AppOps", "厂商权限数据库处理异常，继续: ${db.path} (${t.message})")
            }
        }
        if (touched == 0) Logger.w("AppOps", "未找到可更新的厂商权限数据库: package=$pkg")
    }

    private fun sqlQuote(value: String): String = value.replace("'", "''")


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

    private fun shellOpName(frameworkOp: String): String =
        frameworkOp.substringAfterLast(':').uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_")

    private fun currentOpState(pkg: String, op: String): OpState {
        val shellOp = shellOpName(op)
        val out = ShellExecutor.run("cmd appops get '$pkg' '$shellOp'") ?: return OpState.UNKNOWN
        val line = out.lineSequence().firstOrNull { it.contains(shellOp, ignoreCase = true) }
            ?: return OpState.UNKNOWN
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
        const val APPOPS_TEST_PACKAGE_LIMIT = 3
        const val APPOPS_TEST_OP_LIMIT = 3
        val APP_OP_LINE = Regex("^\\s*([A-Z][A-Z0-9_]+):")

        val VENDOR_META_COLUMNS = setOf("accept", "reject", "prompt", "trust")
        val SQL_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    }
}
