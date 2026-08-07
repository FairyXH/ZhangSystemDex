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
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule
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
        s.print()
        Logger.i("SelfTest", "finished: pass=${s.pass} fail=${s.fail} warn=${s.warn} skip=${s.skip}")
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
}
