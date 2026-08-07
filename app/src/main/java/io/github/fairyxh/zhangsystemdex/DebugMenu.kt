package io.github.fairyxh.zhangsystemdex

import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServerModeModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule
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
        println("17. 退出")
        print("请选择数字: ")
        val line = try {
            BufferedReader(InputStreamReader(System.`in`)).readLine()?.trim() ?: ""
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "read stdin failed", t)
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
                        Logger.w("DebugMenu", "storage_isolation_enable=false, blocked")
                    } else {
                        StorageIsolationModule(ctx)
                            .generateConfig(allApps = ctx.config.switch("storage_isolate_all_enable"))
                    }
                }
                10 -> {
                    if (!ctx.config.switch("storage_isolation_enable")) {
                        Logger.w("DebugMenu", "storage_isolation_enable=false, blocked")
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
                Logger.i("DebugMenu", "=== DB: $db ===")
                val tables = SqliteUtils.queryFirst(db, "SELECT name FROM sqlite_master WHERE type='table'")
                Logger.i("DebugMenu", "tables: $tables")
                for (t in tables) {
                    val cols = SqliteUtils.queryFirst(db, "SELECT name FROM pragma_table_info('$t')")
                    Logger.i("DebugMenu", "  table [$t] columns: $cols")
                }
            }
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "lsposed db diagnose failed", t)
        }
    }

    /** Print ActivityThread reflection facts so SystemContext failures can be diagnosed. */
    private fun diagnoseSystemContext() {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val names = at.declaredMethods.map { it.name }
                .filter { it.contains("SystemContext") || it.contains("systemMain") || it == "currentActivityThread" }
            Logger.i("DebugMenu", "ActivityThread methods: $names (total ${at.declaredMethods.size})")
            // Path 1: existing ActivityThread instance.
            try {
                val current = at.getDeclaredMethod("currentActivityThread")
                current.isAccessible = true
                val instance = current.invoke(null)
                Logger.i("DebugMenu", "currentActivityThread: $instance")
                if (instance != null) {
                    val gsc = at.getDeclaredMethod("getSystemContext")
                    gsc.isAccessible = true
                    Logger.i("DebugMenu", "getSystemContext ok: ${gsc.invoke(instance)}")
                }
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "currentActivityThread path failed", t)
            }
            // Path 2: fresh instance + createSystemContext (usually hidden-API filtered).
            try {
                val create = at.getDeclaredMethod("createSystemContext")
                create.isAccessible = true
                val instance = at.getDeclaredConstructor().newInstance()
                val created = create.invoke(instance)
                Logger.i("DebugMenu", "createSystemContext ok: $created")
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "createSystemContext failed", t)
            }
            // Path 3: systemMain needs a main-thread Looper; then getSystemContext.
            try {
                if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
                val sm = at.getDeclaredMethod("systemMain")
                sm.isAccessible = true
                val instance = sm.invoke(null)
                Logger.i("DebugMenu", "systemMain ok: $instance")
                val gsc = at.getDeclaredMethod("getSystemContext")
                gsc.isAccessible = true
                val created = gsc.invoke(instance)
                Logger.i("DebugMenu", "getSystemContext ok: $created")
            } catch (t: Throwable) {
                Logger.e("DebugMenu", "systemMain path failed", t)
            }
            // Final: what the production path resolves to right now.
            val ctx = io.github.fairyxh.zhangsystemdex.core.SystemContext.getForced()
            Logger.i("DebugMenu", "SystemContext.getForced() => $ctx")
        } catch (t: Throwable) {
            Logger.e("DebugMenu", "diagnose failed", t)
        }
    }
}
