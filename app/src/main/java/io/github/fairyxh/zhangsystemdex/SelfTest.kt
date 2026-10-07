package io.github.fairyxh.zhangsystemdex

import android.database.sqlite.SQLiteDatabase
import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.BuiltinApps
import io.github.fairyxh.zhangsystemdex.core.BuiltinConfig
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import io.github.fairyxh.zhangsystemdex.core.SystemContext
import io.github.fairyxh.zhangsystemdex.core.ConfigManager
import io.github.fairyxh.zhangsystemdex.core.OomProtectList
import io.github.fairyxh.zhangsystemdex.core.KeepAliveKind
import io.github.fairyxh.zhangsystemdex.core.KeepAliveList
import io.github.fairyxh.zhangsystemdex.core.ShizukuResidue
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.OomProtectModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
import io.github.fairyxh.zhangsystemdex.modules.ShizukuModule
import io.github.fairyxh.zhangsystemdex.modules.SkipMountGuardModule
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule
import io.github.fairyxh.zhangsystemdex.core.rubbish.CleanRule
import io.github.fairyxh.zhangsystemdex.core.rubbish.MatchMode
import io.github.fairyxh.zhangsystemdex.core.rubbish.OnlineRuleStore
import io.github.fairyxh.zhangsystemdex.core.rubbish.RiskLevel
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishCleaner
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishRuleSet
import io.github.fairyxh.zhangsystemdex.core.rubbish.RuleDoc
import io.github.fairyxh.zhangsystemdex.core.rubbish.RuleDocCodec
import io.github.fairyxh.zhangsystemdex.core.rubbish.RuleGroup
import io.github.fairyxh.zhangsystemdex.core.rubbish.ScanCache
import java.io.File

/**
 * Self-test tool: invokes every class directly (ignoring Main's switch-driven
 * thread loading) and verifies each step with assertions, so debugging does
 * not depend on switches.conf state.
 *
 * Safety: destructive/state-changing paths stay gated by their real switches
 * (storage-isolation cleanup, disable-apps, appops allow-all). Everything else
 * is exercised unconditionally. Steps without a public runOnce entry point are
 * reported as SKIP with the reason.
 *
 * Entry points:
 *  - debug menu option 20
 *  - `app_process ... Main <moddir> selftest` (runs and exits)
 */
object SelfTest {
    enum class Status { PASS, FAIL, WARN, SKIP }

    data class Check(val name: String, val status: Status, val detail: String)

    class Summary {
        val checks = mutableListOf<Check>()
        val pass get() = checks.count { it.status == Status.PASS }
        val fail get() = checks.count { it.status == Status.FAIL }
        val warn get() = checks.count { it.status == Status.WARN }
        val skip get() = checks.count { it.status == Status.SKIP }

        fun add(name: String, status: Status, detail: String) {
            checks.add(Check(name, status, detail))
        }

        fun print() {
            println()
            println("===== ZhangSystemDex SelfTest 汇总 =====")
            println("PASS: $pass   FAIL: $fail   WARN: $warn   SKIP: $skip   (total ${checks.size})")
            Logger.i("SelfTest", "汇总: PASS=$pass FAIL=$fail WARN=$warn SKIP=$skip (total ${checks.size})")
            for (c in checks) {
                val line = "[${c.status.name}] ${c.name}: ${c.detail}"
                println(line)
                when (c.status) {
                    Status.FAIL -> Logger.e("SelfTest", line, null)
                    Status.WARN -> Logger.w("SelfTest", line)
                    Status.PASS, Status.SKIP -> Logger.i("SelfTest", line)
                }
            }
            println("========================================")
        }
    }

    fun run(ctx: DexContext): Summary {
        val s = Summary()
        envChecks(s, ctx)
        toolChecks(s, ctx)
        moduleChecks(s, ctx)
        powerChecks(s, ctx)
        builtinChecks(s, ctx)
        rubbishChecks(s, ctx)
        onlineRuleChecks(s, ctx)
        s.print()
        Logger.i("SelfTest", "自测完成: 通过=${s.pass} 失败=${s.fail} 警告=${s.warn} 跳过=${s.skip}")
        return s
    }

    // ---------- environment ----------

    private fun envChecks(s: Summary, ctx: DexContext) {
        try {
            val root = android.os.Process.myUid() == 0
            s.add("环境.root", if (root) Status.PASS else Status.FAIL, "uid=${android.os.Process.myUid()}")
        } catch (t: Throwable) {
            s.add("环境.root", Status.FAIL, t.message ?: "")
        }
        try {
            val c = SystemContext.getForced()
            s.add("环境.systemContext", if (c != null) Status.PASS else Status.FAIL, "ctx=$c")
        } catch (t: Throwable) {
            s.add("环境.systemContext", Status.FAIL, t.message ?: "")
        }
        try {
            val ok = ctx.config.rootFile.exists() && ctx.config.rootFile.canWrite()
            s.add("环境.configRoot", if (ok) Status.PASS else Status.FAIL, "dir=${ctx.config.rootDir}")
        } catch (t: Throwable) {
            s.add("环境.configRoot", Status.FAIL, t.message ?: "")
        }
    }

    // ---------- core tool verification ----------

    private fun toolChecks(s: Summary, ctx: DexContext) {
        // FileUtils: write/read/chmod/rm via API paths.
        try {
            val tmp = File(ctx.config.cacheDir, "selftest_tmp.txt")
            tmp.writeText("selftest-ok")
            val read = tmp.readText()
            FileUtils.chmod(tmp.path, "0600")
            val modeOk = tmp.canRead()
            FileUtils.rmQuoted(tmp.path)
            val deleted = !tmp.exists()
            s.add(
                "工具.FileUtils 写/读/chmod/删",
                if (read == "selftest-ok" && modeOk && deleted) Status.PASS else Status.FAIL,
                "read=$read mode=$modeOk deleted=$deleted"
            )
        } catch (t: Throwable) {
            s.add("工具.FileUtils 写/读/chmod/删", Status.FAIL, t.message ?: "")
        }

        // ProcessUtils: process scan / screen / memory.
        try {
            val pids = ProcessUtils.pidsOf("init")
            s.add("工具.ProcessUtils.pidsOf(init)", if (pids.isNotEmpty()) Status.PASS else Status.FAIL, "pids=${pids.size}")
        } catch (t: Throwable) {
            s.add("工具.ProcessUtils.pidsOf(init)", Status.FAIL, t.message ?: "")
        }
        try {
            val screen = ProcessUtils.isScreenOn()
            s.add("工具.ProcessUtils.isScreenOn", Status.PASS, "screenOn=$screen")
        } catch (t: Throwable) {
            s.add("工具.ProcessUtils.isScreenOn", Status.FAIL, t.message ?: "")
        }
        try {
            val mem = ProcessUtils.memFreePercent()
            s.add("工具.ProcessUtils.memFreePercent", if (mem in 0..100) Status.PASS else Status.WARN, "free=$mem%")
        } catch (t: Throwable) {
            s.add("工具.ProcessUtils.memFreePercent", Status.FAIL, t.message ?: "")
        }

        // PropUtils: temporary property set/get/delete (SystemProperties API path).
        try {
            val prop = "debug.zhang.selftest"
            PropUtils.set(prop, "1")
            val v = PropUtils.get(prop)
            PropUtils.delete(prop)
            s.add("工具.PropUtils set/get/delete", if (v == "1") Status.PASS else Status.FAIL, "value=$v")
        } catch (t: Throwable) {
            s.add("工具.PropUtils set/get/delete", Status.FAIL, t.message ?: "")
        }

        // SettingsUtils: harmless global key round-trip.
        try {
            SettingsUtils.putGlobal("zhang_selftest", "1")
            val v = SettingsUtils.getGlobal("zhang_selftest")
            SettingsUtils.putGlobal("zhang_selftest", "0")
            s.add("工具.SettingsUtils global 写/读", if (v == "1") Status.PASS else Status.FAIL, "value=$v")
        } catch (t: Throwable) {
            s.add("工具.SettingsUtils global 写/读", Status.FAIL, t.message ?: "")
        }

        // ShellExecutor: last-resort shell still works.
        try {
            val out = ShellExecutor.run("echo selftest-ok")?.trim()
            s.add("工具.ShellExecutor echo", if (out == "selftest-ok") Status.PASS else Status.FAIL, "out=$out")
        } catch (t: Throwable) {
            s.add("工具.ShellExecutor echo", Status.FAIL, t.message ?: "")
        }

        // AppListProvider: PackageManager paths.
        try {
            val all = AppListProvider.allPackages()
            s.add("工具.AppListProvider.allPackages", if (all.size > 10) Status.PASS else Status.WARN, "count=${all.size}")
        } catch (t: Throwable) {
            s.add("工具.AppListProvider.allPackages", Status.FAIL, t.message ?: "")
        }
        try {
            val third = AppListProvider.thirdPartyPackages()
            s.add("工具.AppListProvider.thirdParty", if (third.isNotEmpty()) Status.PASS else Status.WARN, "count=${third.size}")
        } catch (t: Throwable) {
            s.add("工具.AppListProvider.thirdParty", Status.FAIL, t.message ?: "")
        }
        try {
            val src = AppListProvider.sourceDir("com.android.systemui")
            s.add("工具.AppListProvider.sourceDir(systemui)", if (src != null) Status.PASS else Status.FAIL, "src=$src")
        } catch (t: Throwable) {
            s.add("工具.AppListProvider.sourceDir(systemui)", Status.FAIL, t.message ?: "")
        }

        // FrameworkOps: HOME resolution API.
        try {
            val homes = FrameworkOps.homePackages()
            s.add("工具.FrameworkOps.homePackages", if (homes.isNotEmpty()) Status.PASS else Status.WARN, "homes=$homes")
        } catch (t: Throwable) {
            s.add("工具.FrameworkOps.homePackages", Status.FAIL, t.message ?: "")
        }

        // SqliteUtils: framework SQLite or sqlite3 CLI must provide the path.
        try {
            val db = File(ctx.config.cacheDir, "selftest.db")
            db.delete()
            var frameworkOk = false
            try {
                val opened = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE)
                opened.execSQL("CREATE TABLE t (k TEXT, v TEXT)")
                opened.execSQL("INSERT INTO t VALUES ('a','1')")
                opened.close()
                frameworkOk = true
            } catch (t: Throwable) {
                db.delete()
                val cli = if (File("/data/adb/Zhang/cache/sqlite_lib/sqlite3").exists()) {
                    "LD_LIBRARY_PATH=/data/adb/Zhang/cache/sqlite_lib /data/adb/Zhang/cache/sqlite_lib/sqlite3"
                } else {
                    "sqlite3"
                }
                ShellExecutor.run("$cli '${db.path}' \"CREATE TABLE t (k TEXT, v TEXT); INSERT INTO t VALUES ('a','1');\"")
            }
            val rows = SqliteUtils.queryFirst(db.path, "SELECT v FROM t WHERE k='a'")
            val deleted = db.delete()
            when {
                rows == listOf("1") && frameworkOk ->
                    s.add("工具.SqliteUtils 临时库", Status.PASS, "framework SQLite ok, rows=$rows")
                rows == listOf("1") ->
                    s.add("工具.SqliteUtils 临时库", Status.WARN, "framework SQLite 不可用，sqlite3 CLI 兜底 ok, rows=$rows")
                else ->
                    s.add("工具.SqliteUtils 临时库", Status.FAIL, "framework 与 sqlite3 CLI 均不可用, rows=$rows deleted=$deleted")
            }
        } catch (t: Throwable) {
            s.add("工具.SqliteUtils 临时库", Status.FAIL, t.message ?: "")
        }
    }

    // ---------- module direct invocation ----------

    private fun moduleChecks(s: Summary, ctx: DexContext) {
        // AntiDetection: anti-root-detection properties.
        try {
            AntiDetectionModule(ctx).runOnce()
            val t = PropUtils.get("ro.build.type")
            s.add("模块.AntiDetection.runOnce", if (t == "user") Status.PASS else Status.WARN, "ro.build.type=$t")
        } catch (t: Throwable) {
            s.add("模块.AntiDetection.runOnce", Status.FAIL, t.message ?: "")
        }

        // AntiDetection: HMA 残留清理必须走**精确白名单**，绝不做子串模糊匹配。
        // 正例：白名单内路径或其子路径应被判定可清理；
        // 反例：含 hide/hma/applist 字样但非白名单的 /data/system 路径（如 hmac_key、
        //       厂商 applist 目录）必须被拒绝，避免误删系统核心。
        try {
            val positives = listOf(
                "/data/system/hidemyandroid_applist.conf",
                "/data/adb/modules/hidemyapplist",
            )
            val negatives = listOf(
                "/data/system/sensor_service/hmac_key",
                "/data/system/vendor_applist_cache",   // 含 applist
                "/data/system/hide_from_root",         // 含 hide
                "/data/system/xyz_hma_data",           // 含 hma
                "/data/system/shortx_hqSknixfXdvtRJKB",
                "/data/system/thanos_hJOuGhClVqtheRla",
                "/data/system/packages.xml",           // 关键文件
                "/data/system/users/0/package-restrictions.xml",
            )
            val posBad = positives.filterNot { AntiDetectionModule.isWhitelisted(it) }
            val negBad = negatives.filter { AntiDetectionModule.isWhitelisted(it) }
            val ok = posBad.isEmpty() && negBad.isEmpty() && AntiDetectionModule.HMA_RESIDUE_WHITELIST.isNotEmpty()
            s.add(
                "模块.AntiDetection.HMA白名单精确性",
                if (ok) Status.PASS else Status.FAIL,
                "白名单=${AntiDetectionModule.HMA_RESIDUE_WHITELIST.size} 漏放正例=${posBad.size} 误放反例=${negBad}",
            )
        } catch (t: Throwable) {
            s.add("模块.AntiDetection.HMA白名单精确性", Status.FAIL, t.message ?: "")
        }


        // PowerManager: doze whitelist with verification.
        try {
            PowerManagerModule(ctx).applyDozeList()
            val out = ShellExecutor.run("dumpsys deviceidle whitelist") ?: ""
            val has = out.contains("bin.mt.plus")
            s.add("模块.PowerManager.applyDozeList", if (has) Status.PASS else Status.WARN, "whitelisted bin.mt.plus=$has")
        } catch (t: Throwable) {
            s.add("模块.PowerManager.applyDozeList", Status.FAIL, t.message ?: "")
        }

        // PowerManager: locked apps (MIUI/ColorOS).
        try {
            PowerManagerModule(ctx).applyLockedApps()
            s.add("模块.PowerManager.applyLockedApps", Status.PASS, "no exception")
        } catch (t: Throwable) {
            s.add("模块.PowerManager.applyLockedApps", Status.FAIL, t.message ?: "")
        }

        // Accessibility guard.
        try {
            AccessibilityGuardModule(ctx).runOnce()
            s.add("模块.AccessibilityGuard.runOnce", Status.PASS, "no exception")
        } catch (t: Throwable) {
            s.add("模块.AccessibilityGuard.runOnce", Status.FAIL, t.message ?: "")
        }

        // Service guard (shizuku/brevent/health/bluetooth).
        try {
            ServiceGuardModule(ctx).runOnce()
            val bt = SettingsUtils.getGlobal("bluetooth_on")
            s.add("模块.ServiceGuard.runOnce", Status.PASS, "bluetooth_on=$bt")
        } catch (t: Throwable) {
            s.add("模块.ServiceGuard.runOnce", Status.FAIL, t.message ?: "")
        }

        // Memory: drop_caches once.
        try {
            MemoryModule(ctx).runOnce()
            s.add("模块.Memory.runOnce", Status.PASS, "drop_caches ok, free=${ProcessUtils.memFreePercent()}%")
        } catch (t: Throwable) {
            s.add("模块.Memory.runOnce", Status.FAIL, t.message ?: "")
        }

        // ConfigGen: HMA JSON (full scan) with file verification.
        try {
            val scanner = LSPosedScannerModule(ctx)
            ConfigGenModule(ctx, scanner).generateHma(forceScan = true)
            val f = File(ctx.modDir, "ZhangSetting/隐藏应用列表全隐藏.json")
            s.add(
                "模块.ConfigGen.generateHma",
                if (f.exists() && f.length() > 100) Status.PASS else Status.FAIL,
                "file=${f.exists()} size=${f.length()}"
            )
        } catch (t: Throwable) {
            s.add("模块.ConfigGen.generateHma", Status.FAIL, t.message ?: "")
        }

        // ConfigGen: DoNotTryAccessibility XML.
        try {
            ConfigGenModule(ctx, LSPosedScannerModule(ctx)).generateDoNotTryAccessibility()
            val f = File(ctx.modDir, "ZhangSetting/DoNotTryAccessibility规则.xml")
            s.add("模块.ConfigGen.generateDNTA", if (f.exists()) Status.PASS else Status.FAIL, "size=${f.length()}")
        } catch (t: Throwable) {
            s.add("模块.ConfigGen.generateDNTA", Status.FAIL, t.message ?: "")
        }

        // ConfigGen: tricky_store target list.
        try {
            ConfigGenModule(ctx, LSPosedScannerModule(ctx)).updateTargetList("tricky")
            val f = File("/data/adb/tricky_store/target.txt")
            s.add("模块.ConfigGen.tricky", if (f.exists()) Status.PASS else Status.WARN, "size=${f.length()}")
        } catch (t: Throwable) {
            s.add("模块.ConfigGen.tricky", Status.FAIL, t.message ?: "")
        }

        // LSPosed scanner.
        try {
            val list = LSPosedScannerModule(ctx).scan(force = true)
            s.add("模块.LSPosedScanner.scan", Status.PASS, "modules=${list.size}")
        } catch (t: Throwable) {
            s.add("模块.LSPosedScanner.scan", Status.FAIL, t.message ?: "")
        }

        // MIUI tuning (module has its own switch gate; OPPO device skips most work).
        try {
            MiuiTuningModule(ctx).applyAll()
            val f = File(ctx.config.logDir, "miui_tuning_last.txt")
            s.add("模块.MiuiTuning.applyAll", if (f.exists()) Status.PASS else Status.WARN, "last_run=${f.exists()}")
        } catch (t: Throwable) {
            s.add("模块.MiuiTuning.applyAll", Status.FAIL, t.message ?: "")
        }

        // Thermal mask (module overlay files only).
        try {
            ThermalModule(ctx).applyMask()
            s.add("模块.Thermal.applyMask", Status.PASS, "no exception")
        } catch (t: Throwable) {
            s.add("模块.Thermal.applyMask", Status.FAIL, t.message ?: "")
        }

        // SkipMountGuard: residual module-dir files (skip_mount etc.) must be removed.
        try {
            val before = File(ctx.modDir, "skip_mount").exists()
            val removed = SkipMountGuardModule(ctx).runOnce()
            val after = File(ctx.modDir, "skip_mount").exists()
            val ok = !after && removed == (if (before) 1 else 0)
            s.add(
                "模块.SkipMountGuard.runOnce",
                if (ok) Status.PASS else Status.FAIL,
                "before=$before removed=$removed after=$after"
            )
        } catch (t: Throwable) {
            s.add("模块.SkipMountGuard.runOnce", Status.FAIL, t.message ?: "")
        }

        // Storage isolation: SAFETY-GATED (moves/deletes user data).
        val isoOn = ctx.config.switch("storage_isolation_enable")
        if (!isoOn) {
            s.add("模块.StorageIsolation.generateConfig", Status.SKIP, "storage_isolation_enable=false（安全门控）")
            s.add("模块.StorageIsolation.runOnce/清理", Status.SKIP, "storage_isolation_enable=false（安全门控）")
        } else {
            try {
                StorageIsolationModule(ctx).generateConfig(ctx.config.switch("storage_isolate_all_enable"))
                s.add("模块.StorageIsolation.generateConfig", Status.PASS, "no exception")
            } catch (t: Throwable) {
                s.add("模块.StorageIsolation.generateConfig", Status.FAIL, t.message ?: "")
            }
            try {
                StorageIsolationModule(ctx).runOnce()
                s.add("模块.StorageIsolation.runOnce/清理", Status.PASS, "no exception")
            } catch (t: Throwable) {
                s.add("模块.StorageIsolation.runOnce/清理", Status.FAIL, t.message ?: "")
            }
        }

        // AppOps/vendor permission write-read round trips.
        try {
            val result = AppManagerModule(ctx).testAppOpsWriteRead()
            s.add("模块.AppOps.write/read", when {
                result.contains("结论=通过") -> Status.PASS
                result.startsWith("跳过") -> Status.SKIP
                else -> Status.FAIL
            }, result)
        } catch (t: Throwable) {
            s.add("模块.AppOps.write/read", Status.FAIL, t.message ?: "")
        }
        try {
            val result = AppManagerModule(ctx).testVendorPermissionDatabaseWriteRead()
            s.add("模块.VendorPermissionDb.write/read", when {
                result.contains("dbResult=PASS") -> Status.PASS
                result.contains("SKIP") -> Status.SKIP
                else -> Status.WARN
            }, result)
        } catch (t: Throwable) {
            s.add("模块.VendorPermissionDb.write/read", Status.FAIL, t.message ?: "")
        }

        // AppManager appops: gated (grants permissions).
        if (!ctx.config.switch("appops_allow_enable")) {
            s.add("模块.AppManager.applyAppOps", Status.SKIP, "appops_allow_enable=false（会授权权限）")
        } else {
            try {
                AppManagerModule(ctx).applyAppOps()
                s.add("模块.AppManager.applyAppOps", Status.PASS, "no exception")
            } catch (t: Throwable) {
                s.add("模块.AppManager.applyAppOps", Status.FAIL, t.message ?: "")
            }
        }

        // AppManager disable-apps: gated (uninstalls/disables packages).
        if (!ctx.config.switch("disable_apps_enable")) {
            s.add("模块.AppManager.applyDisableApps", Status.SKIP, "disable_apps_enable=false（会停用/卸载应用）")
        } else {
            try {
                AppManagerModule(ctx).applyDisableApps()
                s.add("模块.AppManager.applyDisableApps", Status.PASS, "no exception")
            } catch (t: Throwable) {
                s.add("模块.AppManager.applyDisableApps", Status.FAIL, t.message ?: "")
            }
        }

        // Explicitly skipped steps with reasons.
        s.add("模块.ServerMode.runOnce", Status.SKIP, "会改 governor/常亮/WiFi，请用调试菜单 15 单独验证")
        s.add("模块.NetworkModule", Status.SKIP, "无公开 runOnce（daemon tick 驱动）")
        s.add("模块.Performance.applyMaxCpu", Status.SKIP, "CPU/GPU 满频副作用，需 max_cpu_enable 开启后验证")
        s.add("模块.GamePause/SystemTuning", Status.SKIP, "无公开 runOnce（周期逻辑由 daemon 驱动）")
    }

    // ---------- power & background scheduling subsystem ----------
    //
    // Safety: every check here is read-only or uses the pure/in-memory API.
    // No kernel write, no background restriction, no `dumpsys` poll is
    // performed. The side-effecting orchestrator [PowerOptimizer] is only
    // instantiated (its constructor registers nothing and performs no I/O);
    // its daemon loop is driven by Main, not by SelfTest. This keeps the
    // regression suite non-destructive while still exercising all 6 classes.
    // ---------- 模块内置应用（system/app/）守护与 OOM ----------
    //
    // 用户要求（2026-10-06 修订）：内置应用「内置守护」可按应用独立关闭；
    // OOM 保护：含无障碍/通知组件者**强制**，其余可选（勾选）。
    // 这里只做只读断言（枚举 + 集合关系），不写系统状态。
    private fun builtinChecks(s: Summary, ctx: DexContext) {
        val rootDir = java.io.File(ctx.config.rootDir)
        // 名单真源 = 已安装模块的 system/app（母版仅发布副本）。
        // effectiveModuleDir 返回**模块根**（`.../Zhang`），即 packages() 期望的层级。
        val modDir = BuiltinApps.effectiveModuleDir(rootDir, ctx.modDir)
        val pkgs = try {
            BuiltinApps.packages(modDir)
        } catch (t: Throwable) {
            s.add("内置应用.枚举", Status.FAIL, t.message ?: "")
            return
        }
        s.add(
            "内置应用.枚举(system/app)",
            if (pkgs.isNotEmpty()) Status.PASS else Status.WARN,
            "目录=${BuiltinApps.root(modDir).path} 数量=${pkgs.size}",
        )
        if (pkgs.isEmpty()) {
            s.add("内置应用.Doze守护", Status.SKIP, "无内置应用")
            s.add("内置应用.通知保活", Status.SKIP, "无内置应用")
            s.add("内置应用.无障碍保活", Status.SKIP, "无内置应用")
            s.add("内置应用.OOM保护", Status.SKIP, "无内置应用")
            return
        }

        // 守护集合 = guard 开关为开的内置应用（默认全开）。
        val guardSet = BuiltinConfig.guardPackages(rootDir)
        val guardOff = pkgs.filter { it !in guardSet }
        s.add(
            "内置应用.守护开关",
            Status.PASS,
            "内置=${pkgs.size} 启用守护=${guardSet.size} 已关闭=${guardOff.size}",
        )

        // Doze / 保活名单只能包含「启用守护」的内置应用（guard=0 的必须缺席）。
        try {
            val notif = KeepAliveList.read(rootDir, KeepAliveKind.NOTIFICATION)
            val a11y = KeepAliveList.read(rootDir, KeepAliveKind.ACCESSIBILITY)
            val leakedNotif = guardOff.filter { it in notif }
            val leakedA11y = guardOff.filter { it in a11y }
            val mN = guardSet.filter { it !in notif }
            val mA = guardSet.filter { it !in a11y }
            s.add(
                "内置应用.通知保活",
                if (mN.isEmpty() && leakedNotif.isEmpty()) Status.PASS else Status.FAIL,
                "生效名单=${notif.size} 守护缺失=${mN.size} 关闭却泄漏=${leakedNotif.size}",
            )
            s.add(
                "内置应用.无障碍保活",
                if (mA.isEmpty() && leakedA11y.isEmpty()) Status.PASS else Status.FAIL,
                "生效名单=${a11y.size} 守护缺失=${mA.size} 关闭却泄漏=${leakedA11y.size}",
            )
        } catch (t: Throwable) {
            s.add("内置应用.通知保活", Status.FAIL, t.message ?: "")
            s.add("内置应用.无障碍保活", Status.FAIL, t.message ?: "")
        }

        // OOM 生效集合：必须包含「强制集合」（含通知/无障碍组件者），
        // 不含未勾选且非强制的内置应用。
        try {
            // 载入持久化探测缓存，使「强制」判定与运行时一致（探测为后台异步）。
            BuiltinConfig.loadForcedCache(rootDir)
            val eff = OomProtectList.effectivePackages(rootDir).toHashSet()
            val forced = pkgs.filter { BuiltinConfig.isForcedOom(it) }
            val forcedMissing = forced.filter { it !in eff }
            // 非强制且未勾选者不应在名单中——但**用户显式写入 oom_protect.conf 的**除外。
            val userOom = OomProtectList.read(rootDir).toHashSet()
            val oomChecked = pkgs.filter { BuiltinConfig.isOomChecked(rootDir, it) }.toHashSet()
            val shouldNot = pkgs.filter { it !in forced && it !in oomChecked && it !in userOom }
            val leaked = shouldNot.filter { it in eff }
            s.add(
                "内置应用.OOM保护",
                if (forcedMissing.isEmpty() && leaked.isEmpty()) Status.PASS else Status.FAIL,
                "强制=${forced.size} 勾选=${oomChecked.size} 生效=${pkgs.count { it in eff }} " +
                    "强制缺失=${forcedMissing.size} 未勾选却泄漏=${leaked.size}",
            )
        } catch (t: Throwable) {
            s.add("内置应用.OOM保护", Status.FAIL, t.message ?: "")
        }
    }

    /** 复刻 PowerManagerModule.buildWhiteList 的白名单解析（只读，用于断言）。 */
    private fun parseDozeConf(ctx: DexContext): List<String> {
        val text = try {
            val f = java.io.File(ctx.config.rootDir, "doze.conf")
            if (ctx.config.switch("only_base_enable") || !f.exists()) {
                io.github.fairyxh.zhangsystemdex.core.ConfigManager.DEFAULT_DOZE_CONF
            } else {
                f.readText()
            }
        } catch (_: Throwable) {
            io.github.fairyxh.zhangsystemdex.core.ConfigManager.DEFAULT_DOZE_CONF
        }
        return text.lineSequence()
            .map { it.trim().removePrefix("+") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun powerChecks(s: Summary, ctx: DexContext) {
        // 1) PowerStatistics: counter + snapshot contract.
        try {
            val st = io.github.fairyxh.zhangsystemdex.core.power.PowerStatistics()
            st.noteScreenOff()
            st.notePolicyApplied(1, "test")
            st.notePolicyReverted("test")
            st.noteFailOpen("test")
            val snap = st.snapshot()
            val ok = snap.containsKey("screenOffCount") &&
                snap.containsKey("policyAppliedCount") &&
                snap.containsKey("policyRevertCount") &&
                snap.containsKey("failOpenCount") &&
                st.lastAction.isNotEmpty()
            s.add("电源.PowerStatistics 计数/快照", if (ok) Status.PASS else Status.FAIL, st.summary())
        } catch (t: Throwable) {
            s.add("电源.PowerStatistics 计数/快照", Status.FAIL, t.message ?: "")
        }

        // 2) PowerPolicyEngine: pure decision table over the four levels.
        //    Uses the live config, so this doubles as a config-read check.
        try {
            val eng = io.github.fairyxh.zhangsystemdex.core.power.PowerPolicyEngine(ctx.config)
            val idle = eng.decide(screenOn = true, charging = false, batteryPct = 80)
            val off = eng.decide(screenOn = false, charging = false, batteryPct = 80)
            val low = eng.decide(screenOn = true, charging = false, batteryPct = 5)
            val chg = eng.decide(screenOn = false, charging = true, batteryPct = 80)
            val levelsOk = idle.level == 0 && off.level == 1 && low.level == 2 && chg.level == 3
            // charging must release (charging=true) regardless of screen state
            val releaseOk = chg.charging && !chg.restrictBackground && chg.cpuCapPercent == null
            // low-battery branch reads power_low_battery_cpu_cap from config
            val lowCapOk = low.cpuCapPercent != null && low.cpuCapPercent!! in 10..100
            val ok = levelsOk && releaseOk && lowCapOk
            s.add(
                "电源.PowerPolicyEngine 四级决策",
                if (ok) Status.PASS else Status.FAIL,
                "idle=${idle.level} off=${off.level} low=${low.level} chg=${chg.level} " +
                    "lowCap=${low.cpuCapPercent} release=${chg.charging}"
            )
        } catch (t: Throwable) {
            s.add("电源.PowerPolicyEngine 四级决策", Status.FAIL, t.message ?: "")
        }

        // 3) KernelPowerManager: gate must be closed (no write) before apply.
        try {
            val st = io.github.fairyxh.zhangsystemdex.core.power.PowerStatistics()
            val km = io.github.fairyxh.zhangsystemdex.core.power.KernelPowerManager(st)
            val idleApplied = km.isApplied()
            // Do NOT call applyScreenOffProfile here: it writes sysfs and is a
            // real side effect. Only assert the initial (reverted) invariant.
            s.add(
                "电源.KernelPowerManager 初始未施加",
                if (!idleApplied) Status.PASS else Status.WARN,
                "isApplied=$idleApplied（真实施加由 daemon tick 验证，避免自测写 sysfs）"
            )
        } catch (t: Throwable) {
            s.add("电源.KernelPowerManager 初始未施加", Status.FAIL, t.message ?: "")
        }

        // 4) AppPowerManager: stop list loads and default is empty (no targets).
        try {
            val am = io.github.fairyxh.zhangsystemdex.core.power.AppPowerManager(ctx.config.rootDir)
            val has = am.hasTargets()
            s.add(
                "电源.AppPowerManager 停名单加载",
                Status.PASS,
                "hasTargets=$has（默认空名单，不作用于任何应用）"
            )
        } catch (t: Throwable) {
            s.add("电源.AppPowerManager 停名单加载", Status.FAIL, t.message ?: "")
        }

        // 5) PowerStateMonitor: register + unregister receivers without side effects.
        try {
            val st = io.github.fairyxh.zhangsystemdex.core.power.PowerStatistics()
            val mon = io.github.fairyxh.zhangsystemdex.core.power.PowerStateMonitor(
                stats = st,
                onScreenOff = {},
                onScreenOn = {},
                onChargingChanged = { _, _ -> }
            )
            val started = mon.start()
            val eventDriven = mon.isEventDriven()
            val coarse = mon.batteryLevel
            mon.stop()
            val stopped = !mon.isEventDriven()
            val ok = started && eventDriven && stopped
            s.add(
                "电源.PowerStateMonitor 事件注册/注销",
                if (ok) Status.PASS else Status.WARN,
                "start=$started eventDriven=$eventDriven battery=$coarse stopped=$stopped"
            )
        } catch (t: Throwable) {
            s.add("电源.PowerStateMonitor 事件注册/注销", Status.FAIL, t.message ?: "")
        }

        // 6) PowerOptimizer: construct only (no loop start); verify fail-open snapshot.
        try {
            val opt = io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer(ctx, ctx.config)
            val snap = opt.snapshot()
            val ok = snap.isNotEmpty()
            s.add(
                "电源.PowerOptimizer 快照可读",
                if (ok) Status.PASS else Status.WARN,
                "keys=${snap.keys.joinToString(",")}（未启动 daemon，仅构造与快照）"
            )
        } catch (t: Throwable) {
            s.add("电源.PowerOptimizer 快照可读", Status.FAIL, t.message ?: "")
        }

        // 7) Config consistency: every power key described in the WebUI must
        //    exist in switches.conf. This guards the exact defect fixed in the
        //    migration (3 booleans were described but not emitted).
        try {
            val keys = listOf(
                "power_optimize_enable",
                "power_charging_release",
                "power_low_battery_threshold",
                "power_low_battery_cpu_cap",
                "power_screen_off_cpu_cap",
                "power_screen_off_cpu_cap_percent",
                "power_low_battery_restrict_bg",
                "power_screen_off_restrict_bg"
            )
            val missing = keys.filter { ctx.config.getString(it, "")?.trim().isNullOrEmpty() }
            s.add(
                "电源.配置键齐备（8 项）",
                if (missing.isEmpty()) Status.PASS else Status.FAIL,
                if (missing.isEmpty()) "8/8 已在 switches.conf" else "缺失: $missing"
            )
        } catch (t: Throwable) {
            s.add("电源.配置键齐备（8 项）", Status.FAIL, t.message ?: "")
        }

        // 8) Background stop list file must exist (created by initUserConfigs).
        try {
            val f = File(ctx.config.rootDir, "power_bg_stop_list.conf")
            s.add(
                "电源.停名单文件存在",
                if (f.exists()) Status.PASS else Status.WARN,
                f.path
            )
        } catch (t: Throwable) {
            s.add("电源.停名单文件存在", Status.FAIL, t.message ?: "")
        }
    }

    // ---------- rubbish cleaning subsystem ----------
    //
    // Safety: everything here is read-only or uses the pure audit API. No file
    // is ever deleted: we only assert the guard REJECTS dangerous paths and
    // ACCEPTS legitimate ones. The scanning API is exercised but scan() never
    // deletes by design.
    private fun rubbishChecks(s: Summary, ctx: DexContext) {
        // 1) Rule table integrity: ids unique, switch keys present.
        try {
            val all = RubbishRuleSet.ALL
            val ids = all.map { it.id }
            val dup = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            val noSwitch = all.filter { it.switchKey.isEmpty() }
            s.add(
                "清理.规则表完整性",
                if (dup.isEmpty() && noSwitch.isEmpty() && all.size >= 20) Status.PASS else Status.FAIL,
                "规则数=${all.size} 重复id=$dup 缺开关=${noSwitch.map { it.id }}"
            )
        } catch (t: Throwable) {
            s.add("清理.规则表完整性", Status.FAIL, t.message ?: "")
        }

        // 2) 风险分级：低风险默认开、中高风险默认关。
        //    例外：listOnly（只读扫描、永不删除）规则无风险，默认关是合理选择，
        //    不参与「低风险默认开」的约束。
        try {
            val bad = RubbishRuleSet.ALL.filter { r ->
                !r.listOnly &&
                    ((r.risk == RiskLevel.LOW && !r.defaultOn) || (r.risk != RiskLevel.LOW && r.defaultOn))
            }
            s.add(
                "清理.风险分级默认值",
                if (bad.isEmpty()) Status.PASS else Status.FAIL,
                if (bad.isEmpty()) "低风险全默认开、中高风险全默认关（只读规则豁免）" else "异常: ${bad.map { it.id }}"
            )
        } catch (t: Throwable) {
            s.add("清理.风险分级默认值", Status.FAIL, t.message ?: "")
        }

        // 3) 审查链：危险路径必须被拒绝（这是安全核心，必须全 PASS）。
        try {
            val mustReject = listOf(
                "/", "/data", "/data/media", "/data/user", "/data/data",
                "/data/adb", "/data/system", "/data/system/dropbox",
                "/system", "/vendor", "/sdcard", "/storage/emulated/0",
                // 路径穿越：解析后落到非用户目录 / 关键目录
                "/data/media/0/Download/../../adb",
                "/data/media/adb",
                "/data/user/0/../../../adb",
                // 默认违禁词
                "/data/user/0/com.tencent.mm/MicroMsg/x/EnMicroMsg.db",
                "/data/user/0/com.tencent.mm/shared_prefs/x.xml",
            )
            val failed = mustReject.filter { RubbishGuard.check(it, "selftest") !is RubbishGuard.Verdict.Reject }
            s.add(
                "清理.审查拒绝危险路径",
                if (failed.isEmpty()) Status.PASS else Status.FAIL,
                if (failed.isEmpty()) "${mustReject.size}/${mustReject.size} 全部拒绝"
                else "未拒绝: $failed"
            )
        } catch (t: Throwable) {
            s.add("清理.审查拒绝危险路径", Status.FAIL, t.message ?: "")
        }

        // 4) 审查链：合法清理路径必须被接受。
        try {
            val mustAccept = listOf(
                "/data/user/0/com.tencent.mm/cache/temp",
                "/data/user/0/com.tencent.mm/files/xlog",
                "/data/media/0/Android/data/com.tencent.mm/cache/Cache",
                "/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo",
                "/data/media/0/Download",
            )
            val failed = mustAccept.filter { RubbishGuard.check(it, "selftest") !is RubbishGuard.Verdict.Accept }
            s.add(
                "清理.审查接受合法路径",
                if (failed.isEmpty()) Status.PASS else Status.FAIL,
                if (failed.isEmpty()) "${mustAccept.size}/${mustAccept.size} 全部接受"
                else "被误拒: $failed"
            )
        } catch (t: Throwable) {
            s.add("清理.审查接受合法路径", Status.FAIL, t.message ?: "")
        }

        // 5) 用户自定义违禁词生效。
        try {
            RubbishGuard.loadUserRules(File(ctx.config.rootDir, "rubbish_guard.conf").path)
            val rules = RubbishGuard.userRules()
            val wordOk = rules.denyWords.any { it.contains("EnMicroMsg", ignoreCase = true) }
            s.add(
                "清理.用户审查规则加载",
                if (rules.denyPaths.isNotEmpty() && rules.denyWords.isNotEmpty()) Status.PASS else Status.WARN,
                "denyPath=${rules.denyPaths.size} denyWord=${rules.denyWords.size} 含EnMicroMsg=$wordOk"
            )
        } catch (t: Throwable) {
            s.add("清理.用户审查规则加载", Status.FAIL, t.message ?: "")
        }

        // 6) 只读扫描可用（不删除任何文件）。
        try {
            val c = RubbishCleaner(ctx.config)
            val summary = c.scan(ruleIds = listOf("wx_logs", "qq_logs"))
            s.add(
                "清理.只读扫描",
                Status.PASS,
                "规则=${summary.results.size} 文件=${summary.totalFiles} 字节=${summary.totalBytes}（未删除）"
            )
        } catch (t: Throwable) {
            s.add("清理.只读扫描", Status.FAIL, t.message ?: "")
        }
        // 6b) listOnly（仅列出）规则在 clean() 中必须零删除 —— 回归守卫。
        try {
            val c = RubbishCleaner(ctx.config)
            val listOnlyIds = RubbishRuleSet.ALL.filter { it.listOnly }.map { it.id }
            val summary = c.clean(ruleIds = listOnlyIds)
            val deleted = summary.totalFiles
            s.add(
                "清理.仅列出规则零删除",
                if (listOnlyIds.isNotEmpty() && deleted == 0) Status.PASS else Status.FAIL,
                "listOnly=${listOnlyIds.size} 规则，clean() 删除文件=$deleted（必须为 0）"
            )
        } catch (t: Throwable) {
            s.add("清理.仅列出规则零删除", Status.FAIL, t.message ?: "")
        }

        // 6c) 分片并行扫描器计数正确性 + 缓存确定性 —— 回归守卫。
        //     在临时目录造一棵已知结构的小树，验证：
        //       - 冷扫（无缓存）计数 == 手工计算值；
        //       - 暖扫（有缓存 + 目录剪枝）计数与冷扫完全一致；
        //       - 再次暖扫仍一致（确定性）。
        try {
            // 注意：测试树必须放在【非禁止路径】下，否则 RubbishGuard 会整体跳过。
            // /data/adb 在 FORBIDDEN_PREFIX 中，故改用外部存储真实路径 /data/media/0。
            val base = File("/data/media/0/.zsd_selftest/scan_case")
            // 清理旧目录
            if (base.exists()) base.deleteRecursively()
            base.mkdirs()
            // 结构：
            //   base/cache/a.tmp          (垃圾：.tmp)
            //   base/cache/deep/b.log     (垃圾：.log)
            //   base/cache/deep/keep.dat  (非垃圾)
            //   base/empty_dir/           (空目录 → 垃圾)
            //   base/plain/readme.txt     (非垃圾)
            val cache = File(base, "cache"); cache.mkdirs()
            File(cache, "a.tmp").writeText("x")
            val deep = File(cache, "deep"); deep.mkdirs()
            File(deep, "b.log").writeText("yy")
            File(deep, "keep.dat").writeText("zzz")
            File(base, "empty_dir").mkdirs()
            val plain = File(base, "plain"); plain.mkdirs()
            File(plain, "readme.txt").writeText("hello")
            // 手工期望：垃圾 = a.tmp + b.log + empty_dir = 3 个（字节 1+2+0=3）
            val expectFiles = 3
            val expectBytes = 3L

            // 两次扫描必须得到完全相同的计数（缓存 + 剪枝不得改变结果）。
            val cleaner = RubbishCleaner(ctx.config)
            cleaner.forceSingleThreadForTest()
            val rule = CleanRule(
                id = "selftest_junk", name = "selftest", group = RuleGroup.DEEP,
                risk = RiskLevel.LOW, defaultOn = true, mode = MatchMode.JUNK_SCAN,
                roots = listOf(base.absolutePath), maxDepth = 12, switchKey = "",
            )
            val idx1 = ScanCache.Index()
            val r1 = cleaner.scanJunkForTest(rule, listOf(base), idx1)
            // 第二次带上第一次的索引（模拟暖扫复用分片聚合缓存）
            val idx2 = ScanCache.Index()
            idx2.shardAggs.putAll(idx1.shardAggs)
            idx2.entries.putAll(idx1.entries)
            val r2 = cleaner.scanJunkForTest(rule, listOf(base), idx2)

            // 第三次再带第二次的索引（验证确定性：连续暖扫必须仍一致）
            val idx3 = ScanCache.Index()
            idx3.shardAggs.putAll(idx2.shardAggs)
            idx3.entries.putAll(idx2.entries)
            val r3 = cleaner.scanJunkForTest(rule, listOf(base), idx3)

            val ok = r1.first == expectFiles && r1.second == expectBytes &&
                r2.first == expectFiles && r2.second == expectBytes &&
                r3.first == expectFiles && r3.second == expectBytes
            val dbg = "分片聚合(idx1)=${idx1.shardAggs.size} idx2=${idx2.shardAggs.size} idx3=${idx3.shardAggs.size}"
            s.add(
                "清理.并行扫描计数一致",
                if (ok) Status.PASS else Status.FAIL,
                "冷扫=${r1.first}文件/${r1.second}字节 暖扫=${r2.first}文件/${r2.second}字节 " +
                    "再暖=${r3.first}文件/${r3.second}字节 期望=${expectFiles}文件/${expectBytes}字节 | $dbg"
            )
            base.deleteRecursively()
        } catch (t: Throwable) {
            s.add("清理.并行扫描计数一致", Status.FAIL, t.message ?: "")
        }

        // 7) 配置键齐备（低风险规则 + 总开关）。
        try {
            val keys = listOf(
                "rubbish_clean_enable", "rubbish_clean_screen_off_only",
                "rubbish_rule_app_cache", "rubbish_rule_temp_files",
                "rubbish_rule_wx_logs", "rubbish_rule_wx_temp",
                "rubbish_rule_qq_logs", "rubbish_rule_qq_cache",
                "rubbish_rule_wx_chat_media", "rubbish_rule_qq_file_recv",
            )
            val missing = keys.filter { ctx.config.getString(it, "").isNullOrEmpty() }
            s.add(
                "清理.配置键齐备",
                if (missing.isEmpty()) Status.PASS else Status.FAIL,
                if (missing.isEmpty()) "${keys.size}/${keys.size} 已在 switches.conf" else "缺失: $missing"
            )
        } catch (t: Throwable) {
            s.add("清理.配置键齐备", Status.FAIL, t.message ?: "")
        }

        // 8) 审计日志目录可用。
        try {
            val p = RubbishGuard.auditLog().filePath()
            s.add("清理.审计日志就绪", Status.PASS, p)
        } catch (t: Throwable) {
            s.add("清理.审计日志就绪", Status.FAIL, t.message ?: "")
        }

        // 9) 审查配置文件存在（用户可编辑）。
        try {
            val f = File(ctx.config.rootDir, "rubbish_guard.conf")
            s.add(
                "清理.审查配置文件存在",
                if (f.exists()) Status.PASS else Status.WARN,
                f.path
            )
        } catch (t: Throwable) {
            s.add("清理.审查配置文件存在", Status.FAIL, t.message ?: "")
        }

        // 10) 真正的清理执行：安全门控（需显式开启总开关）。
        if (!ctx.config.switch("rubbish_clean_enable")) {
            s.add("清理.执行清理", Status.SKIP, "rubbish_clean_enable=false（安全门控，避免自测真删）")
        } else {
            s.add("清理.执行清理", Status.WARN, "总开关已开，自测不主动执行删除；请用调试菜单 28 单独验证")
        }

        // ===== OOM 保护名单 =====
        // 11) 安全钳制：请求 -1000 必须被钳到 SAFE_FLOOR(-500)，不得过高。
        try {
            val clamped = OomProtectModule.clampOom(-1000)   // 越界下钳 -> -500
            val clamped2 = OomProtectModule.clampOom(-500)   // 合法区间内 -> 原样 -500
            val clamped3 = OomProtectModule.clampOom(5000)   // 越界上钳 -> 1000
            val ok = clamped == OomProtectModule.SAFE_FLOOR &&
                clamped2 == -500 &&
                clamped3 == 1000
            s.add(
                "OOM.安全钳制",
                if (ok) Status.PASS else Status.FAIL,
                "clamp(-1000)=$clamped clamp(-500)=$clamped2 clamp(5000)=$clamped3 " +
                    "安全上限=${OomProtectModule.SAFE_FLOOR}"
            )
        } catch (t: Throwable) {
            s.add("OOM.安全钳制", Status.FAIL, t.message ?: "")
        }

        // 11b) 系统核心进程保护：system_server / init / zygote 必须被识别为「不可动」。
        try {
            var allProtected = true
            val samples = listOf("init", "system_server", "zygote64")
            for (nm in samples) {
                val hit = ProcessUtils.pidsOf(nm).any { OomProtectModule.isProtectedSystemProcess(it) }
                if (!hit) allProtected = false
            }
            s.add(
                "OOM.系统进程保护",
                if (allProtected) Status.PASS else Status.FAIL,
                "已识别 init/system_server/zygote64 为系统核心进程（绝不改动 oom_score_adj）"
            )
        } catch (t: Throwable) {
            s.add("OOM.系统进程保护", Status.FAIL, t.message ?: "")
        }

        // 12) 名单归一化：去注释/空行/重复/非法包名，保留合法项。
        try {
            val text = """
                # comment
                com.ai.assistance.operit
                com.ai.assistance.operit   # dup
                com.tencent.mm
                not-a-package
                /system/bin/sh
                123.bad
            """.trimIndent()
            val got = OomProtectList.normalize(text)
            val expect = listOf("com.ai.assistance.operit", "com.tencent.mm")
            s.add(
                "OOM.名单归一化",
                if (got == expect) Status.PASS else Status.FAIL,
                "得到=$got 期望=$expect"
            )
        } catch (t: Throwable) {
            s.add("OOM.名单归一化", Status.FAIL, t.message ?: "")
        }

        // 13) 名单文件读写往返（写到临时路径，不污染真实配置）。
        try {
            val tmpDir = File("/data/media/0/.zsd_selftest/oom")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            val f = OomProtectList.file(tmpDir)
            f.writeText(OomProtectList.render(listOf("com.ai.assistance.operit", "com.tencent.mm")), Charsets.UTF_8)
            val back = OomProtectList.read(tmpDir)
            val ok = back == listOf("com.ai.assistance.operit", "com.tencent.mm")
            s.add("OOM.名单读写往返", if (ok) Status.PASS else Status.FAIL, "读回=$back")
            tmpDir.deleteRecursively()
        } catch (t: Throwable) {
            s.add("OOM.名单读写往返", Status.FAIL, t.message ?: "")
        }

        // 14) 默认内置包名正确（用户指定：Operit / 滤盒 / Scene / GKD）。
        try {
            val got = OomProtectList.normalize(OomProtectList.DEFAULT_CONTENT)
            val missing = OomProtectList.DEFAULT_PACKAGES.filter { it !in got }
            s.add(
                "OOM.默认内置包名",
                if (missing.isEmpty()) Status.PASS else Status.FAIL,
                "默认内容=$got 缺失=$missing"
            )
        } catch (t: Throwable) {
            s.add("OOM.默认内置包名", Status.FAIL, t.message ?: "")
        }
        // 14b) 保活名单自动并入 OOM 保护名单（用户要求）。
        try {
            val tmpDir = File("/data/media/0/.zsd_selftest/oommerge")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            OomProtectList.file(tmpDir).writeText("com.tencent.mm\n", Charsets.UTF_8)
            KeepAliveList.write(tmpDir, KeepAliveKind.NOTIFICATION, listOf("com.catchingnow.np"))
            KeepAliveList.write(tmpDir, KeepAliveKind.ACCESSIBILITY, listOf("li.songe.gkd"))
            val eff = OomProtectList.effectivePackages(tmpDir).toHashSet()
            // 用户名单 ∪ 用户保活名单 必须并入。
            val mustHave = listOf("com.tencent.mm", "com.catchingnow.np", "li.songe.gkd")
            val missing = mustHave.filter { it !in eff }
            // 关键：**未勾选且非强制**的内置应用不得因为「守护开关」被拖入 OOM 保护。
            val builtinAll = BuiltinConfig.allPackages(tmpDir)
            BuiltinConfig.loadForcedCache(tmpDir)
            val leakBuiltin = builtinAll.filter {
                it in eff && !BuiltinConfig.isForcedOom(it) && !BuiltinConfig.isOomChecked(tmpDir, it)
            }
            val ok = missing.isEmpty() && leakBuiltin.isEmpty()
            s.add(
                "OOM.并入保活名单",
                if (ok) Status.PASS else Status.FAIL,
                "生效名单=${eff.size}，缺失=$missing，内置未勾选却泄漏=${leakBuiltin.size}"
            )
            tmpDir.deleteRecursively()
        } catch (t: Throwable) {
            s.add("OOM.并入保活名单", Status.FAIL, t.message ?: "")
        }
        // 14c) Shizuku 加入 OOM 默认名单（用户要求）。
        try {
            val has = OomProtectList.normalize(OomProtectList.DEFAULT_CONTENT)
                .contains(ShizukuResidue.PACKAGE)
            s.add(
                "Shizuku.计入 OOM 默认",
                if (has) Status.PASS else Status.FAIL,
                "默认含 ${ShizukuResidue.PACKAGE}=$has"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.计入 OOM 默认", Status.FAIL, t.message ?: "")
        }
        // 14d) Shizuku 进程分类（主进程 vs 服务端）。
        try {
            // uid 10335 = u0_a335（主应用）；99910335 = u999_a335（服务端，root 模式）
            val snap = ShizukuModule.classify(listOf(19928 to 10335, 3185 to 99910335))
            val ok = snap.mainPids == listOf(19928) && snap.serverPids == listOf(3185) && snap.healthy
            s.add(
                "Shizuku.进程分类",
                if (ok) Status.PASS else Status.FAIL,
                "主=${snap.mainPids} 服务端=${snap.serverPids} healthy=${snap.healthy}"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.进程分类", Status.FAIL, t.message ?: "")
        }
        // 14e) Shizuku 不健康判定：仅主/仅服务端 → 不健康；root 服务端 → 健康。
        try {
            val onlyMain = ShizukuModule.classify(listOf(19928 to 10335))
            val onlyServer = ShizukuModule.classify(listOf(3185 to 99910335))
            // root 模式服务端 uid=0；adb 模式服务端 uid=2000
            val rootServer = ShizukuModule.classify(listOf(19928 to 10335, 100 to 0))
            val shellServer = ShizukuModule.classify(listOf(19928 to 10335, 300 to 2000))
            val ok = !onlyMain.healthy && !onlyServer.healthy &&
                rootServer.healthy && shellServer.healthy
            s.add(
                "Shizuku.不健康判定",
                if (ok) Status.PASS else Status.FAIL,
                "onlyMain=${onlyMain.healthy} onlyServer=${onlyServer.healthy} " +
                    "rootServer=${rootServer.healthy} shellServer=${shellServer.healthy}"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.不健康判定", Status.FAIL, t.message ?: "")
        }
        // 14f) Shizuku 防检测白名单：精确匹配（不做子串误伤）。
        try {
            val ok = ShizukuResidue.isWhitelisted("/data/local/shizuku_starter") &&
                ShizukuResidue.isWhitelisted("/data/local/tmp/shizuku_starter") &&
                ShizukuResidue.isWhitelisted("/data/local/tmp/shizuku") &&
                !ShizukuResidue.isWhitelisted("/data/local/tmp/my_shizuku_notes.txt") &&
                !ShizukuResidue.isWhitelisted("/data/local/tmp/other") &&
                // 2026-10-06：官方 start.sh 使用的 starter 已改为受保护（guarded）
                ShizukuResidue.isGuarded("/data/local/shizuku_starter") &&
                ShizukuResidue.isGuarded("/data/local/tmp/shizuku_starter") &&
                !ShizukuResidue.isGuarded("/data/local/tmp/rikka.shizuku")
            s.add(
                "Shizuku.防检测白名单",
                if (ok) Status.PASS else Status.FAIL,
                "精确匹配正常"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.防检测白名单", Status.FAIL, t.message ?: "")
        }
        // 14g) Shizuku 防检测目标计算：allowGuarded 控制是否含受保护路径。
        try {
            val exists: (String) -> Boolean = { it in setOf(
                "/data/local/shizuku_starter", "/data/local/tmp/shizuku_starter"
            ) }
            val safe = ShizukuResidue.targets(false, exists)
            val full = ShizukuResidue.targets(true, exists)
            // 2026-10-06：starter 已移入 GUARDED_CLEAN → safe 不再包含任何路径；
            // 解锁后 full 同时包含两个 starter。
            val ok = safe.isEmpty() &&
                full.contains("/data/local/shizuku_starter") &&
                full.contains("/data/local/tmp/shizuku_starter") && full.size == 2
            s.add(
                "Shizuku.防检测目标",
                if (ok) Status.PASS else Status.FAIL,
                "safe=$safe full=$full"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.防检测目标", Status.FAIL, t.message ?: "")
        }
        // 14h) Shizuku 官方 root 启动：进程识别口径与 ABI 目录候选。
        try {
            val ok = ShizukuModule.SERVER_PROC == "shizuku_server" &&
                ShizukuModule.PROC_PATTERNS.contains(ShizukuResidue.PACKAGE) &&
                ShizukuModule.PROC_PATTERNS.contains("shizuku_server") &&
                ShizukuModule.ABI_DIRS.first() == "arm64" &&
                ShizukuModule.ABI_DIRS.contains("arm") &&
                // classify 能把 shizuku_server（root，uid=0）归为服务端
                ShizukuModule.classify(listOf(100 to 0)).serverPids == listOf(100)
            s.add(
                "Shizuku.官方启动识别",
                if (ok) Status.PASS else Status.FAIL,
                "SERVER_PROC=${ShizukuModule.SERVER_PROC} ABI=${ShizukuModule.ABI_DIRS}"
            )
        } catch (t: Throwable) {
            s.add("Shizuku.官方启动识别", Status.FAIL, t.message ?: "")
        }
        // 14i) procfs 读取：cmdline 必须能读到 arg0（st_size=0 陷阱回归测试）。
        try {
            val selfCmd = ProcessUtils.readProcText(File("/proc/self/cmdline"))
            val arg0 = selfCmd?.substringBefore('\u0000')?.trim().orEmpty()
            // /proc/self/cmdline 的 arg0 应为本进程可执行名（app_process*）；
            // 旧实现用 readBytes()（按 length() 预分配）恒得空串。
            val ok = arg0.isNotEmpty()
            s.add(
                "ProcessUtils.procfs 流式读取",
                if (ok) Status.PASS else Status.FAIL,
                "self arg0=\"$arg0\""
            )
        } catch (t: Throwable) {
            s.add("ProcessUtils.procfs 流式读取", Status.FAIL, t.message ?: "")
        }
        // 15) 配置键齐备（oom_protect_enable）。
        try {
            val keys = listOf("oom_protect_enable")
            val missing = keys.filter { k ->
                ConfigManager.SWITCH_DESCRIPTIONS[k] == null
            }
            s.add(
                "OOM.配置键齐备",
                if (missing.isEmpty()) Status.PASS else Status.FAIL,
                if (missing.isEmpty()) "oom_protect_enable 已登记" else "缺失: $missing"
            )
        } catch (t: Throwable) {
            s.add("OOM.配置键齐备", Status.FAIL, t.message ?: "")
        }
    }

    // ---------- 在线规则 / 规则编辑器 ----------

    private fun onlineRuleChecks(s: Summary, ctx: DexContext) {
        val root = File(ctx.config.rootDir)

        // 1) 规则 schema 往返：解析 → 序列化 → 再解析，分组数一致。
        try {
            val json = """
                {
                  "version": 1,
                  "name": "自测规则",
                  "groups": [
                    {
                      "name": "自测缓存",
                      "mode": "DIR_CONTENT",
                      "risk": "LOW",
                      "roots": ["/data/media/<u>/Android/data/com.example/cache"]
                    }
                  ]
                }
            """.trimIndent()
            val p1 = RuleDocCodec.parse(json, strict = true)
            if (p1 is RuleDoc.Result.Err) {
                s.add("在线规则.schema往返", Status.FAIL, p1.message)
            } else {
                val doc = (p1 as RuleDoc.Result.Ok).doc
                val re = RuleDocCodec.parse(RuleDocCodec.encode(doc), strict = true)
                val ok = re is RuleDoc.Result.Ok && (re as RuleDoc.Result.Ok).doc.groups.size == doc.groups.size
                s.add("在线规则.schema往返", if (ok) Status.PASS else Status.FAIL, "groups=${doc.groups.size}")
            }
        } catch (t: Throwable) {
            s.add("在线规则.schema往返", Status.FAIL, t.message ?: "")
        }

        // 2) 校验红线：相对路径 / .. / 根目录 必须被拒绝。
        try {
            val bad = listOf(
                "relative/path",
                "/data/media/../system",
                "/",
            )
            val allRejected = bad.all { RuleDocCodec.validatePath(it) != null }
            s.add(
                "在线规则.校验红线（拒绝非法路径）",
                if (allRejected) Status.PASS else Status.FAIL,
                "cases=${bad.size}"
            )
        } catch (t: Throwable) {
            s.add("在线规则.校验红线（拒绝非法路径）", Status.FAIL, t.message ?: "")
        }

        // 3) GLOB 模式缺 pattern 必须被拒绝。
        try {
            val json = """{"version":1,"groups":[{"name":"g","mode":"GLOB","roots":["/data/media/<u>/x"]}]}"""
            val r = RuleDocCodec.parse(json, strict = true)
            s.add(
                "在线规则.GLOB 需 pattern",
                if (r is RuleDoc.Result.Err) Status.PASS else Status.FAIL,
                if (r is RuleDoc.Result.Err) "已拒绝" else "未拒绝"
            )
        } catch (t: Throwable) {
            s.add("在线规则.GLOB 需 pattern", Status.FAIL, t.message ?: "")
        }

        // 4) 存储读写：新增源 → 列表可见 → 删除 → 列表消失（不联网）。
        try {
            val dir = OnlineRuleStore.dir(root)
            val existedBefore = dir.exists()
            val id = OnlineRuleStore.addSource(root, "自测源", "https://example.invalid/rules.json", 24, false)
            val listed = id != null && OnlineRuleStore.listSources(root).any { it.id == id }
            val removed = id != null && OnlineRuleStore.removeSource(root, id)
            val gone = id == null || OnlineRuleStore.listSources(root).none { it.id == id }
            val ok = listed && removed && gone
            s.add(
                "在线规则.源增删（不联网）",
                if (ok) Status.PASS else Status.FAIL,
                "id=$id listed=$listed removed=$removed gone=$gone"
            )
            // 清理：若本测试首次创建了目录且已空，则移除之。
            if (!existedBefore && dir.exists() && dir.listFiles()?.isEmpty() == true) dir.delete()
        } catch (t: Throwable) {
            s.add("在线规则.源增删（不联网）", Status.FAIL, t.message ?: "")
        }

        // 5) URL 校验：非 http(s) 必须拒绝。
        try {
            val ok = !OnlineRuleStore.isHttpUrl("ftp://x/y") &&
                !OnlineRuleStore.isHttpUrl("not-a-url") &&
                OnlineRuleStore.isHttpUrl("https://a.b/c.json")
            s.add("在线规则.URL 校验", if (ok) Status.PASS else Status.FAIL, "http/https only")
        } catch (t: Throwable) {
            s.add("在线规则.URL 校验", Status.FAIL, t.message ?: "")
        }

        // 6) 规则编辑器：导出 → 校验（内建 + 在线 + 用户合并结果可往返）。
        try {
            val cleaner = RubbishCleaner(ctx.config)
            cleaner.forceSingleThreadForTest()
            val groups = cleaner.exportGroups()
            val doc = RuleDoc.Doc(name = "自测导出", groups = groups)
            val re = RuleDocCodec.parse(RuleDocCodec.encode(doc), strict = true)
            val ok = re is RuleDoc.Result.Ok && (re as RuleDoc.Result.Ok).doc.groups.size == groups.size
            s.add(
                "规则编辑器.导出往返",
                if (ok && groups.isNotEmpty()) Status.PASS else if (groups.isEmpty()) Status.WARN else Status.FAIL,
                "groups=${groups.size}"
            )
        } catch (t: Throwable) {
            s.add("规则编辑器.导出往返", Status.FAIL, t.message ?: "")
        }

        // 7) 配置键齐备（online_rules_enable）。
        try {
            val missing = listOf(io.github.fairyxh.zhangsystemdex.modules.OnlineRuleModule.SWITCH_KEY)
                .filter { ConfigManager.SWITCH_DESCRIPTIONS[it] == null }
            s.add(
                "在线规则.配置键齐备",
                if (missing.isEmpty()) Status.PASS else Status.FAIL,
                if (missing.isEmpty()) "online_rules_enable 已登记" else "缺失: $missing"
            )
        } catch (t: Throwable) {
            s.add("在线规则.配置键齐备", Status.FAIL, t.message ?: "")
        }
    }
}
