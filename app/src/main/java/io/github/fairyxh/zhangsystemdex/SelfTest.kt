package io.github.fairyxh.zhangsystemdex

import android.database.sqlite.SQLiteDatabase
import io.github.fairyxh.zhangsystemdex.core.AppListProvider
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
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
import io.github.fairyxh.zhangsystemdex.modules.SkipMountGuardModule
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule
import io.github.fairyxh.zhangsystemdex.core.rubbish.RiskLevel
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishCleaner
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishRuleSet
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
        rubbishChecks(s, ctx)
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
        try {
            val bad = RubbishRuleSet.ALL.filter { r ->
                (r.risk == RiskLevel.LOW && !r.defaultOn) || (r.risk != RiskLevel.LOW && r.defaultOn)
            }
            s.add(
                "清理.风险分级默认值",
                if (bad.isEmpty()) Status.PASS else Status.FAIL,
                if (bad.isEmpty()) "低风险全默认开、中高风险全默认关" else "异常: ${bad.map { it.id }}"
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
                "/data/media/0/../..",
                "/data/media/0/Download/../../adb",
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
    }
}
