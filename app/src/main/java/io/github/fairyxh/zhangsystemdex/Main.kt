package io.github.fairyxh.zhangsystemdex

import android.annotation.SuppressLint
import android.os.Process
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.HiddenApiBypass
import io.github.fairyxh.zhangsystemdex.core.HttpBackend
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import io.github.fairyxh.zhangsystemdex.core.RootUtils
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
import io.github.fairyxh.zhangsystemdex.core.SystemContext
import io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityKeepAliveModule
import io.github.fairyxh.zhangsystemdex.modules.NotificationKeepAliveModule
import io.github.fairyxh.zhangsystemdex.modules.AccelerometerRotationModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.BtOffloadGuardModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.GamePauseModule
import io.github.fairyxh.zhangsystemdex.modules.GameOomProtectModule
import io.github.fairyxh.zhangsystemdex.modules.OomProtectModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.IncidentWatchModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.NetworkModule
import io.github.fairyxh.zhangsystemdex.modules.OnlineRuleModule
import io.github.fairyxh.zhangsystemdex.modules.PerformanceModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServerModeModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
import io.github.fairyxh.zhangsystemdex.modules.ShizukuModule
import io.github.fairyxh.zhangsystemdex.modules.SkipMountGuardModule
import io.github.fairyxh.zhangsystemdex.modules.StorageIsolationModule
import io.github.fairyxh.zhangsystemdex.modules.SystemTuningModule
import io.github.fairyxh.zhangsystemdex.modules.ThermalModule

/**
 * app_process entry: io.github.fairyxh.zhangsystemdex.Main
 * args[0] = Magisk module directory (e.g. /data/adb/modules/Zhang)
 *
 * Only features whose switch is enabled are loaded; disabled features never
 * create a thread. switches.conf is watched every 60s: toggling a feature
 * starts/stops its thread without restarting the daemon. Every enabled thread
 * logs a clear "feature enabled" line on startup.
 */
object Main {
    private class ModuleEntry(
        val name: String,
        val enabled: () -> Boolean,
        /** Human label shown on the overview page. */
        val label: String = name,
        /** One-line description shown on the overview page. */
        val desc: String = "",
        /** Factory that builds the module loop. Kept LAST so call sites can pass it as a trailing lambda. */
        val factory: () -> DaemonLoop,
    )

    @SuppressLint("PrivateApi")
    private fun getSystemContext(): android.content.Context? {

        return try {

            val clazz =
                Class.forName(
                    "android.app.ActivityThread"
                )

            val thread =
                clazz.getMethod(
                    "currentActivityThread"
                ).invoke(null)


            val method =
                clazz.getMethod(
                    "getSystemContext"
                )

            method.invoke(thread)
                    as? android.content.Context


        } catch(e:Throwable){

            Logger.w(
                "DexContext",
                e.toString()
            )

            null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val modDir = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "/data/adb/modules/Zhang"
        val ctx = DexContext(modDir)
        DexContext.current = ctx
        ctx.load()

        Logger.i("Main", "========================================")
        Logger.i("Main", "ZhangSystemDex 启动中")
        Logger.i("Main", "uid=${Process.myUid()} 模块目录=$modDir 配置根=${ctx.config.rootDir} 日志开关=${ctx.config.logEnabled}")
        if (!RootUtils.isRoot()) {
            Logger.e("Main", "非 root 运行，退出", null)
            return
        }
        HiddenApiBypass.enable()
        PropUtils.detect()
        val sysCtx = SystemContext.get()
        Logger.i("Main", if (sysCtx != null) "system context available, framework APIs preferred" else "system context unavailable, shell fallback active")

        // Self-test mode: run every module directly (ignoring switches) with
        // verification, then exit. Triggered by the `selftest` startup arg.
        if (args.contains("selftest")) {
            Logger.i("Main", "自测模式")
            SelfTest.run(ctx)
            Logger.i("Main", "自测完成，退出")
            return
        }

        // Notification permission CLI: run a single action then exit.
        // Usage: app_process ... Main <moddir> notification <action> <pkg...>
        //   actions: grant | revoke | check | probe | clean
        // Designed for scripting (e.g. from a root shell / Magisk script),
        // no interactive input required.
        if (args.contains("notification")) {
            val idx = args.indexOf("notification")
            val action = args.getOrNull(idx + 1) ?: "check"
            val pkgs = args.drop(idx + 2).filter { it.isNotBlank() }
            Logger.i("Main", "通知权限 CLI: action=$action pkgs=$pkgs")
            try {
                val ng = io.github.fairyxh.zhangsystemdex.core.NotificationGrant
                when (action) {
                    "grant" -> {
                        if (pkgs.isEmpty()) {
                            println("用法: notification grant <pkg> [pkg...]")
                        } else {
                            for (p in pkgs) {
                                val r = ng.grant(p)
                                println("grant $p: ok=${r.ok} listeners=${r.listeners} " +
                                    "postNotif=${r.postNotifGranted} doze=${r.dozeWhitelisted} " +
                                    "warnings=${r.warnings}")
                            }
                        }
                    }
                    "revoke" -> {
                        for (p in pkgs) {
                            println("revoke $p: ${ng.revoke(p)}")
                        }
                    }
                    "revoke-post" -> {
                        for (p in pkgs) {
                            println("revoke-post $p: ${ng.revokePostNotif(p)}")
                        }
                    }
                    "check", "probe" -> {
                        if (pkgs.isEmpty()) {
                            ng.listListeners().forEach { println("  $it") }
                        } else {
                            for (p in pkgs) println(ng.inspect(p))
                        }
                    }
                    // list: 只输出已授权的包名，一行一个（便于脚本消费）
                    "list" -> {
                        ng.listPackages().forEach { println(it) }
                    }
                    "summary" -> println(ng.summary())
                    "clean" -> {
                        val n = ng.cleanInvalid { println(it) }
                        println("clean: 移除 $n 条")
                    }
                    "doze-on" -> {
                        for (p in pkgs) println("doze-on $p: ${ng.addDozeWhitelist(p)}")
                    }
                    "doze-off" -> {
                        for (p in pkgs) println("doze-off $p: ${ng.removeDozeWhitelist(p)}")
                    }
                    "rebind" -> {
                        for (p in pkgs) println("rebind $p: ${ng.rebind(p)}")
                    }
                    else -> println(
                        "未知 action: $action\n" +
                            "可选: grant | revoke | revoke-post | check | probe | list | summary | " +
                            "clean | doze-on | doze-off | rebind"
                    )
                }
            } catch (t: Throwable) {
                Logger.e("Main", "通知权限 CLI 失败", t)
                println("错误: ${t}")
            }
            Logger.i("Main", "通知权限 CLI 完成，退出")
            return
        }

        // Accessibility service CLI: run a single action then exit.
        // Usage: app_process ... Main <moddir> accessibility <action> <pkg...>
        //   actions: enable | disable | check | probe | list | bound | summary
        //            | clean | restart
        if (args.contains("accessibility")) {
            val idx = args.indexOf("accessibility")
            val action = args.getOrNull(idx + 1) ?: "check"
            val pkgs = args.drop(idx + 2).filter { it.isNotBlank() }
            Logger.i("Main", "无障碍 CLI: action=$action pkgs=$pkgs")
            try {
                val ag = io.github.fairyxh.zhangsystemdex.core.AccessibilityGrant
                when (action) {
                    "enable", "set" -> {
                        if (pkgs.isEmpty()) {
                            println("用法: accessibility enable <pkg> [pkg...]")
                        } else {
                            for (p in pkgs) {
                                val ok = ag.enable(p)
                                println("enable $p: ok=$ok 实际绑定=${ag.isBound(p)}")
                            }
                        }
                    }
                    "disable", "revoke" -> {
                        for (p in pkgs) println("disable $p: ${ag.disable(p)}")
                    }
                    "list" -> ag.listPackages().forEach { println(it) }
                    "bound" -> print(ag.boundDiff())
                    "summary" -> println(ag.summary())
                    "check", "probe" -> {
                        if (pkgs.isEmpty()) {
                            ag.listComponents().forEach { println("  $it") }
                        } else {
                            for (p in pkgs) print(ag.inspect(p))
                        }
                    }
                    "clean" -> {
                        val n = ag.cleanInvalid { println(it) }
                        println("clean: 移除 $n 条")
                    }
                    "restart" -> {
                        for (p in pkgs) {
                            io.github.fairyxh.zhangsystemdex.core.ShellExecutor.run("am force-stop $p", 15000L)
                            println("restart $p")
                        }
                    }
                    else -> println(
                        "未知 action: $action\n" +
                            "可选: enable | disable | check | probe | list | bound | summary | clean | restart"
                    )
                }
            } catch (t: Throwable) {
                Logger.e("Main", "无障碍 CLI 失败", t)
                println("错误: ${t}")
            }
            Logger.i("Main", "无障碍 CLI 完成，退出")
            return
        }

        // Debug menu mode: run a single feature once by number, then exit.
        if (args.contains("menu")) {
            Logger.i("Main", "调试菜单模式")
            if (!DebugMenu.run(ctx)) {
                Logger.i("Main", "调试操作完成，退出")
                return
            }
            Logger.i("Main", "调试菜单结束后正常启动")
        }

        val sw = ctx.config
        fun enabled(key: String): Boolean {
            if (sw.switch("powersave_enable")) return false
            return sw.switch(key)
        }

        val appManager = AppManagerModule(ctx)

        // Keep module APK package history up to date only at startup.
        appManager.refreshModuleAppOpsPackages()

        // Non-thread support objects shared by module factories.
        val scanner = LSPosedScannerModule(ctx)
        val storage = StorageIsolationModule(ctx)
        val thermal = ThermalModule(ctx)
        val miui = MiuiTuningModule(ctx)
        val performance = PerformanceModule(ctx)
        val power = PowerManagerModule(ctx)
        val configGen = ConfigGenModule(ctx, scanner)
        val serviceGuard = ServiceGuardModule(ctx)

        val entries = listOf(
            ModuleEntry(
                "module_appops_auth", { enabled("module_appops_auth_enable") },
                label = "模块 AppOps 授权",
                desc = "为模块目录 APK 授予运行所需 AppOps",
            ) {
                // 周期放宽到 300s：applyModuleAppOps 已改为「每包只完整处理一次」的
                // 增量模式，正常情况下一轮后即无待处理包；周期加长只是兜底新装包。
                // （旧值 60s 会让未命中缓存的包反复触发数千条 cmd appops IPC，
                //  叠加 ColorOS AppBatteryTracker 锁竞争导致 system_server 软重启。）
                object : DaemonLoop(ctx, 300_000L, pauseAware = false) {
                    override val name: String = "ModuleAppOps"
                    override fun tick() {
                        if (!ctx.config.switch("module_appops_auth_enable")) return
                        appManager.applyModuleAppOps()
                    }
                }
            },
            ModuleEntry("prop_tuning", { enabled("prop_tuning_enable") }, label = "属性调优", desc = "系统属性与反检测参数下发") {
                AntiDetectionModule(ctx)
            },
            ModuleEntry(
                "system_tuning", { enabled("system_tuning_enable") || enabled("heavy_task_enable") },
                label = "系统调优", desc = "周期性性能/电源/服务综合调优",
            ) {
                SystemTuningModule(ctx, performance, power, configGen, appManager, serviceGuard, storage, thermal, miui)
            },
            ModuleEntry("game_pause", { enabled("game_pause_enable") }, label = "游戏暂停", desc = "游戏运行时暂停清理/后台限制") {
                GamePauseModule(ctx)
            },
            ModuleEntry(
                "game_oom_protect", { enabled("game_oom_protect_enable") },
                label = "游戏 OOM 保护", desc = "保活游戏进程，避免被低内存杀手回收",
            ) {
                GameOomProtectModule(ctx)
            },
            // OOM 保护：常驻线程；用户名单受总开关控制，内置应用按
            // `builtin_guard.conf`（强制：含通知/无障碍组件者；其余勾选）。
            ModuleEntry(
                "oom_protect", { true },
                label = "OOM 保护名单",
                desc = "含通知/无障碍组件的内置应用强制保护；其余可选（WebUI 勾选）。用户名单受总开关控制",
            ) {
                OomProtectModule(ctx)
            },
            ModuleEntry("accessibility_guard", { true }, label = "无障碍守护", desc = "常驻守护无障碍服务不被系统关闭") {
                AccessibilityGuardModule(ctx)
            },
            // 通知使用权 / 无障碍服务保活：常驻线程；总开关控制「用户名单」，
            // 内置应用按 `builtin_guard.conf` 的 guard 开关（默认开，可独立关闭）。
            ModuleEntry(
                "notif_keepalive", { true },
                label = "通知使用权保活",
                desc = "内置应用默认保活（可在 WebUI「内置应用」逐项关闭）；用户名单见 WebUI「保活」页",
            ) {
                NotificationKeepAliveModule(ctx)
            },
            ModuleEntry(
                "a11y_keepalive", { true },
                label = "无障碍服务保活",
                desc = "内置应用默认保活（可在 WebUI「内置应用」逐项关闭）；用户名单见 WebUI「保活」页",
            ) {
                AccessibilityKeepAliveModule(ctx)
            },
            ModuleEntry(
                "service_guard", { enabled("service_guard_enable") || enabled("extra_features_enable") },
                label = "服务守护", desc = "守护关键系统服务存活",
            ) {
                ServiceGuardModule(ctx)
            },
            ModuleEntry(
                "shizuku_guard",
                { sw.switch("shizuku_keepalive_enable") || sw.switch("shizuku_detect_enable") },
                label = "Shizuku 守护",
                desc = "Shizuku 保活（含服务端进程）与 /data/local 痕迹防检测清理",
            ) {
                ShizukuModule(ctx)
            },
            ModuleEntry("server_mode", { enabled("server_mode_enable") }, label = "服务器模式", desc = "服务器场景下的调度参数") {
                ServerModeModule(ctx)
            },
            ModuleEntry("power", { true }, label = "电源管理", desc = "电池读数与电源事件订阅") {
                PowerManagerModule(ctx)
            },
            // 电源与后台调度优化子系统：受总闸 powersave_enable 与自身开关双重控制。
            // 关闭时不创建线程/监听，且会还原所有临时调度状态。
            ModuleEntry(
                "power_optimize", { enabled("power_optimize_enable") },
                label = "省电优化", desc = "事件驱动省电子系统（降频/后台限制/息屏策略）",
            ) {
                PowerOptimizer(ctx, ctx.config)
            },
            ModuleEntry("memory_clean", { enabled("memory_clean_enable") }, label = "内存清理", desc = "低内存时回收后台进程") {
                MemoryModule(ctx)
            },
            // 事故哨兵：常驻后台、零 shell 采集 watchdog/crash/重启/内存低点，
            // 供 /api/guard/alerts 读取。始终启用（纯文件读取，开销极低）。
            ModuleEntry(
                "incident_watch", { true },
                label = "事故哨兵", desc = "后台记录 watchdog/崩溃/软重启（零 shell）",
            ) {
                IncidentWatchModule(ctx)
            },
            ModuleEntry(
                "accelerometer_rotation", { enabled("accelerometer_rotation_enable") },
                label = "重力感应旋转", desc = "按重力自动旋转屏幕",
            ) {
                AccelerometerRotationModule(ctx)
            },
            ModuleEntry(
                "storage_isolation", { enabled("storage_isolation_enable") },
                label = "存储隔离", desc = "痕迹清理/垃圾隔离/配置生成",
            ) {
                StorageIsolationModule(ctx)
            },
            ModuleEntry("hma_config", { enabled("hma_config_enable") }, label = "HMA 配置生成", desc = "HideMyAppList 模板列表写入") {
                ConfigGenModule(ctx, scanner)
            },
            ModuleEntry("network_ipv6", { enabled("network_ipv6_disable_enable") }, label = "禁用 IPv6", desc = "在所有网络接口关闭 IPv6") {
                NetworkModule(ctx)
            },
            // 蓝牙音频 offload 循环守护（周期性复位 A2DP/LE 音频硬件 offload 属性）。
            ModuleEntry(
                "bt_offload_guard", { enabled("bt_offload_guard_enable") },
                label = "蓝牙 offload 守护", desc = "复位蓝牙音频硬件 offload 属性",
            ) {
                BtOffloadGuardModule(ctx)
            },
            // 防护类功能：不受 powersave_enable 影响（省电模式不关闭防护）
            ModuleEntry("skip_mount_guard", { sw.switch("skip_mount_guard_enable") }, label = "挂载防护", desc = "删除 skip_mount 等残留文件") {
                SkipMountGuardModule(ctx)
            },
            // 在线规则订阅：定时拉取用户配置的规则直链（多套），失败不影响主清理。
            ModuleEntry(
                "online_rules", { sw.switch("online_rules_enable") },
                label = "在线规则订阅", desc = "定期拉取规则直链（多套），合并进清理规则",
            ) {
                OnlineRuleModule(ctx)
            },
        )

        // Publish every module (with its human label) into the runtime registry,
        // which the WebUI overview page reads through /api/overview.
        entries.forEach { e -> RuntimeRegistry.register(e.name, e.label, e.desc) }
        RuntimeRegistry.daemonStartedMs = System.currentTimeMillis()
        RuntimeRegistry.configRoot = ctx.config.rootDir
        RuntimeRegistry.moduleDir = modDir

        // ---- WebUI 后端（Main.dex 承载）--------------------------------------
        // HTTP 服务是 WebUI 的控制平面：页面加载后立刻需要它来读取/写入所有
        // 配置。若它跟随任何功能开关，用户一旦从 UI 里关掉对应功能就会把
        // 自己的后端关掉（死锁）。因此它必须常驻，且只监听 127.0.0.1，
        // 端口取自 config.conf 的 http_port。
        //
        // 注意：先定义 syncModules 并注册 ctx.config.onSwitchesChanged，
        // 再启动 HTTP 后端——否则用户在页面里切换开关后，后端虽已写盘，
        // 但模块的启停要等主循环 60s（且因 mtime 被提前更新而可能永远不触发）。
        val running = mutableMapOf<String, DaemonLoop>()
        val syncLock = Any()

        fun syncModules() {
            synchronized(syncLock) {
                for (entry in entries) {
                    val on = entry.enabled()
                    val current = running[entry.name]
                    RuntimeRegistry.setEnabled(entry.name, on)
                    if (on && current == null) {
                        val module = entry.factory()
                        module.registryKey = entry.name
                        running[entry.name] = module
                        RuntimeRegistry.setRunning(entry.name, true)
                        Logger.i("Main", "功能已启用: ${module.javaClass.simpleName} (${entry.name})")
                        module.start()
                    } else if (!on && current != null) {
                        Logger.i("Main", "功能已禁用: ${current.javaClass.simpleName} (${entry.name})")
                        current.stop()
                        running.remove(entry.name)
                        RuntimeRegistry.setRunning(entry.name, false)
                    }
                }
                val enabledNames = entries.filter { it.enabled() }.map { it.name }
                Logger.i("Main", "已启用功能 (${enabledNames.size} 个): ${enabledNames.joinToString(", ")}")
            }
        }

        // WebUI 每次写 switches.conf / 调用 /api/reload 都会触发该回调，
        // 使开关的“动作”（启动/停止模块）瞬时生效，无需等待 60s 或重启设备。
        ctx.config.onSwitchesChanged = {
            try {
                Logger.i("Main", "配置变更触发模块重新同步")
                syncModules()
            } catch (t: Throwable) {
                Logger.e("Main", "配置变更同步失败", t)
            }
        }

        val httpBackend = HttpBackend(ctx, sw.httpPort)
        httpBackend.start()
        Logger.i("Main", "WebUI 后端已启动：http://127.0.0.1:${httpBackend.activePort()}（仅回环）")

        syncModules()
        Logger.i("Main", "守护进程就绪，每 60s 监听 switches.conf")
        val bootTuning = ctx.config.getString("tuning_interval_seconds", "")
        val bootHeavy = ctx.config.getString("heavy_interval_cycles", "")
        Logger.i(
            "Main",
            "周期配置: tuning_interval_seconds=${bootTuning.ifBlank { "默认" }}, heavy_interval_cycles=${bootHeavy.ifBlank { "默认" }}（重启后生效）"
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.i("Main", "关机钩子，正在停止模块")
            try {
                httpBackend.stop()
            } catch (_: Throwable) {
            }
            running.values.forEach { it.stop() }
        })

        while (true) {
            try {
                if (ctx.config.reloadSwitchesIfChanged()) {
                    Logger.i("Main", "switches.conf 已变化，重新同步模块")
                    val t = ctx.config.getString("tuning_interval_seconds", "")
                    val h = ctx.config.getString("heavy_interval_cycles", "")
                    if (t != bootTuning || h != bootHeavy) {
                        Logger.w(
                            "Main",
                            "周期参数已修改 (tuning_interval_seconds: ${bootTuning.ifBlank { "默认" }} -> ${t.ifBlank { "默认" }}, " +
                                "heavy_interval_cycles: ${bootHeavy.ifBlank { "默认" }} -> ${h.ifBlank { "默认" }})，重启 daemon 后生效"
                        )
                    }
                    syncModules()
                }
                Thread.sleep(60000)
            } catch (t: Throwable) {
                Logger.e("Main", "监听循环错误", t)
                try {
                    Thread.sleep(60000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Logger.i("Main", "守护进程退出")
    }
}
