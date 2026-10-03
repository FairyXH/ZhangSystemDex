package io.github.fairyxh.zhangsystemdex

import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServerModeModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
import io.github.fairyxh.zhangsystemdex.modules.SkipMountGuardModule
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule
import io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer
import io.github.fairyxh.zhangsystemdex.core.power.PowerPolicyEngine
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishCleaner
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishRuleSet
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Interactive debug menu. Triggered by the `menu` startup argument (the debug
 * launcher passes it by default). Lets the user run a single feature once by
 * number, then exits. Returns true when the caller should continue with a
 * normal start (choice 0).
 */
object DebugMenu {
    fun run(ctx: DexContext): Boolean {
        val scanner = LSPosedScannerModule(ctx)
        println()
        println("===== ZhangSystemDex 调试菜单 =====")
        println("0. 正常启动（全部已启用功能）")
        println("1. 防检测属性应用（prop_tuning）")
        println("2. HideMyAppList 配置生成（含 Xposed 扫描）")
        println("3. Xposed 模块扫描")
        println("4. Doze 白名单应用")
        println("5. 多任务 Lock 应用")
        println("6. 无障碍守护检查")
        println("7. 服务守护动作（Shizuku/Brevent/蓝牙/健康）")
        println("8. 内存清理（drop_caches）")
        println("9. 存储隔离配置生成")
        println("10. 存储隔离痕迹/垃圾清理")
        println("11. 温控遮蔽生成")
        println("12. MIUI 调优（joyose/powerkeeper）")
        println("13. target 列表更新（tricky/hmspush）")
        println("14. DoNotTryAccessibility 生成")
        println("15. 服务器模式动作")
        println("16. 游戏列表刷新")
        println("18. LSPosed 数据库诊断")
        println("19. SystemContext 诊断")
        println("20. 自测工具（无视开关全量自测 + 验证）")
        println("21. 模块目录防护检查（删除 skip_mount 等残留）")
        println("22. AppOps 写入/读取测试")
        println("23. 厂商权限数据库写入/读取测试")
        println("24. 电源优化：立即评估一次（真实施加/回滚，输出统计）")
        println("25. 电源优化：查看状态快照（只读，不施加）")
        println("26. 电源优化：查看决策表（纯计算，不产生副作用）")
        println("27. 垃圾清理：只读扫描（统计各类可清理空间）")
        println("28. 垃圾清理：按当前开关执行一次清理（真删）")
        println("29. 垃圾审查：自检路径审查（验证 RubbishGuard 拒绝逻辑）")
        println("17. 退出")
        print("请选择数字: ")
        val line = try {
            BufferedReader(InputStreamReader(System.`in`)).readLine()?.trim() ?: ""
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "读取输入失败", t)
            ""
        }
        val choice = line.toIntOrNull() ?: -1
        println()
        try {
            when (choice) {
                0 -> return true
                1 -> AntiDetectionModule(ctx).runOnce()
                2 -> ConfigGenModule(ctx, scanner).generateHma(forceScan = true)
                3 -> {
                    val list = scanner.scan(force = true)
                    Logger.i("DebugMenu", "扫描到 ${list.size} 个模块: ${list.map { it.packageName }}")
                }
                4 -> PowerManagerModule(ctx).applyDozeList()
                5 -> PowerManagerModule(ctx).applyLockedApps()
                6 -> AccessibilityGuardModule(ctx).runOnce()
                7 -> ServiceGuardModule(ctx).runOnce()
                8 -> MemoryModule(ctx).runOnce()
                9 -> {
                    if (!ctx.config.switch("storage_isolation_enable")) {
                        Logger.w("DebugMenu", "storage_isolation_enable=false，已拦截")
                    } else {
                        StorageIsolationModule(ctx)
                            .generateConfig(allApps = ctx.config.switch("storage_isolate_all_enable"))
                    }
                }
                10 -> {
                    if (!ctx.config.switch("storage_isolation_enable")) {
                        Logger.w("DebugMenu", "storage_isolation_enable=false，已拦截")
                    } else {
                        StorageIsolationModule(ctx).runOnce()
                    }
                }
                11 -> ThermalModule(ctx).applyMask()
                12 -> MiuiTuningModule(ctx).applyAll()
                13 -> {
                    val cg = ConfigGenModule(ctx, scanner)
                    cg.updateTargetList("tricky")
                    cg.updateTargetList("hmspush")
                }
                14 -> ConfigGenModule(ctx, scanner).generateDoNotTryAccessibility()
                15 -> ServerModeModule(ctx).runOnce()
                16 -> {
                    val games = GameListProvider.refresh(ctx.config.switch("read_game_list_enable"))
                    Logger.i("DebugMenu", "游戏列表 ${games.size} 个: $games")
                }
                18 -> diagnoseLsposedDb()
                19 -> diagnoseSystemContext()
                20 -> SelfTest.run(ctx)
                22 -> {
                    val result = AppManagerModule(ctx).testAppOpsWriteRead()
                    Logger.i("DebugMenu", "AppOps 写入/读取测试结果: $result")
                }
                23 -> {
                    val result = AppManagerModule(ctx).testVendorPermissionDatabaseWriteRead()
                    Logger.i("DebugMenu", "厂商权限数据库写入/读取测试结果: $result")
                }
                24 -> {
                    // Real evaluation: honours power_optimize_enable, applies or
                    // reverts exactly like the daemon path. Reversible.
                    val opt = PowerOptimizer(ctx, ctx.config)
                    Logger.i("DebugMenu", "电源优化立即评估完成: ${opt.evaluateNow()}")
                }
                25 -> {
                    // Read-only snapshot; never starts the event loop.
                    val opt = PowerOptimizer(ctx, ctx.config)
                    for ((k, v) in opt.snapshot()) {
                        Logger.i("DebugMenu", "  电源快照 $k = $v")
                    }
                }
                26 -> {
                    // Pure decision table, no side effects at all.
                    val engine = PowerPolicyEngine(ctx.config)
                    val cases = listOf(
                        Triple(true, false, 80),
                        Triple(false, false, 80),
                        Triple(false, false, 10),
                        Triple(false, true, 50),
                    )
                    for ((on, charging, pct) in cases) {
                        val d = engine.decide(on, charging, pct)
                        Logger.i(
                            "DebugMenu",
                            "  决策 screenOn=$on charging=$charging battery=$pct% -> " +
                                "level=${d.level} cpuCap=${d.cpuCapPercent} restrictBg=${d.restrictBackground} reason=${d.reason}"
                        )
                    }
                }
                21 -> {
                    val removed = SkipMountGuardModule(ctx).runOnce()
                    Logger.i("DebugMenu", "模块目录防护检查完成，删除残留文件 $removed 个")
                }
                27 -> {
                    val cleaner = RubbishCleaner(ctx.config)
                    Logger.i("DebugMenu", "垃圾清理规则数: ${RubbishRuleSet.ALL.size}")
                    val s = cleaner.scan()
                    Logger.i("DebugMenu", "垃圾扫描: 文件=${s.totalFiles} 字节=${s.totalBytes}")
                    s.results.forEach { r ->
                        Logger.i(
                            "DebugMenu",
                            "  [${r.risk}] ${r.ruleId} ${r.name}: ${r.files} 文件 / ${r.bytes} B" +
                                (if (r.skipped) "（跳过: ${r.skipReason}）" else "")
                        )
                    }
                }
                28 -> {
                    if (!ctx.config.switch("rubbish_clean_enable")) {
                        Logger.w("DebugMenu", "rubbish_clean_enable=false，已拦截（请在 WebUI 或 switches.conf 开启）")
                    } else {
                        val s = RubbishCleaner(ctx.config).clean()
                        Logger.i("DebugMenu", "垃圾清理执行: 文件=${s.totalFiles} 字节=${s.totalBytes}")
                        s.results.forEach { r ->
                            Logger.i("DebugMenu", "  ${r.ruleId}: 删除 ${r.files} 文件 / ${r.bytes} B，拒绝 ${r.rejected.size}")
                        }
                    }
                }
                29 -> {
                    Logger.i("DebugMenu", "垃圾审查自检:")
                    val cases = listOf(
                        "/" to false, "/data" to false, "/data/media" to false,
                        "/data/adb" to false, "/data/system/dropbox" to false,
                        "/data/media/0/../.." to false,
                        "/data/user/0/com.tencent.mm/cache/temp" to true,
                        "/data/media/0/Android/data/com.tencent.mm/cache/Cache" to true,
                        "/data/media/0/Download/../../adb" to false,
                    )
                    for ((p, expect) in cases) {
                        val v = RubbishGuard.check(p, "selftest")
                        val accepted = v is RubbishGuard.Verdict.Accept
                        val ok = accepted == expect
                        val reason = if (v is RubbishGuard.Verdict.Reject) v.reason else "放行"
                        Logger.i("DebugMenu", "  ${if (ok) "PASS" else "FAIL"} $p -> ${if (accepted) "接受" else "拒绝"}（$reason）")
                    }
                }
                else -> Logger.w("DebugMenu", "未识别输入: $line")
            }
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "执行失败", t)
        }
        println("===== 调试动作执行完毕 =====")
        return false
    }

    /** Print LSPosed database tables/columns so schema mismatches can be fixed. */
    private fun diagnoseLsposedDb() {
        try {
            val dirs = listOf(
                java.io.File("/data/adb/lspd"),
                java.io.File("/data/user_de/0/org.lsposed.manager/databases"),
                java.io.File("/data/user/0/org.lsposed.manager/databases"),
            )
            val dbs = LinkedHashSet<String>()
            for (dir in dirs) {
                if (!dir.exists()) continue
                dir.walkTopDown().forEach { f ->
                    if (f.isFile && f.name.endsWith(".db")) dbs.add(f.path)
                }
            }
            for (db in dbs) {
                Logger.i("DebugMenu", "=== 数据库: $db ===")
                val tables = SqliteUtils.queryFirst(db, "SELECT name FROM sqlite_master WHERE type='table'")
                Logger.i("DebugMenu", "表: $tables")
                for (t in tables) {
                    val cols = SqliteUtils.queryFirst(db, "SELECT name FROM pragma_table_info('$t')")
                    Logger.i("DebugMenu", "  表 [$t] 列: $cols")
                }
            }
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "LSPosed 数据库诊断失败", t)
        }
    }

    /** Print ActivityThread reflection facts so SystemContext failures can be diagnosed. */
    private fun diagnoseSystemContext() {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val names = at.declaredMethods.map { it.name }
                .filter { it.contains("SystemContext") || it.contains("systemMain") || it == "currentActivityThread" }
            Logger.i("DebugMenu", "ActivityThread 方法: $names（共 ${at.declaredMethods.size} 个）")
            // Path 1: existing ActivityThread instance.
            try {
                val current = at.getDeclaredMethod("currentActivityThread")
                current.isAccessible = true
                val instance = current.invoke(null)
                Logger.i("DebugMenu", "currentActivityThread 实例: $instance")
                if (instance != null) {
                    val gsc = at.getDeclaredMethod("getSystemContext")
                    gsc.isAccessible = true
                    Logger.i("DebugMenu", "getSystemContext 成功: ${gsc.invoke(instance)}")
                }
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "currentActivityThread 路径失败", t)
            }
            // Path 2: fresh instance + createSystemContext (usually hidden-API filtered).
            try {
                val create = at.getDeclaredMethod("createSystemContext")
                create.isAccessible = true
                val instance = at.getDeclaredConstructor().newInstance()
                val created = create.invoke(instance)
                Logger.i("DebugMenu", "createSystemContext 成功: $created")
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "createSystemContext 失败", t)
            }
            // Path 3: systemMain needs a main-thread Looper; then getSystemContext.
            try {
                if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
                val sm = at.getDeclaredMethod("systemMain")
                sm.isAccessible = true
                val instance = sm.invoke(null)
                Logger.i("DebugMenu", "systemMain 成功: $instance")
                val gsc = at.getDeclaredMethod("getSystemContext")
                gsc.isAccessible = true
                val created = gsc.invoke(instance)
                Logger.i("DebugMenu", "getSystemContext 成功: $created")
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "systemMain 路径失败", t)
            }
            // Final: what the production path resolves to right now.
            val ctx = io.github.fairyxh.zhangsystemdex.core.SystemContext.getForced()
            Logger.i("DebugMenu", "SystemContext.getForced() => $ctx")
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "诊断失败", t)
        }
    }
}
