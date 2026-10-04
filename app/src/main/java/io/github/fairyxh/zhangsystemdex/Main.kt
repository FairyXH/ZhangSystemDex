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
import io.github.fairyxh.zhangsystemdex.modules.AccelerometerRotationModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.BtOffloadGuardModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.GamePauseModule
import io.github.fairyxh.zhangsystemdex.modules.GameOomProtectModule
import io.github.fairyxh.zhangsystemdex.modules.LSPosedScannerModule
import io.github.fairyxh.zhangsystemdex.modules.MemoryModule
import io.github.fairyxh.zhangsystemdex.modules.MiuiTuningModule
import io.github.fairyxh.zhangsystemdex.modules.NetworkModule
import io.github.fairyxh.zhangsystemdex.modules.PerformanceModule
import io.github.fairyxh.zhangsystemdex.modules.PowerManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ServerModeModule
import io.github.fairyxh.zhangsystemdex.modules.ServiceGuardModule
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
                object : DaemonLoop(ctx, 60000L, pauseAware = false) {
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
            ModuleEntry("accessibility_guard", { true }, label = "无障碍守护", desc = "常驻守护无障碍服务不被系统关闭") {
                AccessibilityGuardModule(ctx)
            },
            ModuleEntry(
                "service_guard", { enabled("service_guard_enable") || enabled("extra_features_enable") },
                label = "服务守护", desc = "守护关键系统服务存活",
            ) {
                ServiceGuardModule(ctx)
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
