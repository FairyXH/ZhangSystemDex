package io.github.fairyxh.zhangsystemdex

import android.annotation.SuppressLint
import android.os.Process
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.HiddenApiBypass
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import io.github.fairyxh.zhangsystemdex.core.RootUtils
import io.github.fairyxh.zhangsystemdex.core.SystemContext
import io.github.fairyxh.zhangsystemdex.modules.AccessibilityGuardModule
import io.github.fairyxh.zhangsystemdex.modules.AntiDetectionModule
import io.github.fairyxh.zhangsystemdex.modules.AppManagerModule
import io.github.fairyxh.zhangsystemdex.modules.ConfigGenModule
import io.github.fairyxh.zhangsystemdex.modules.GamePauseModule
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

        // Non-thread support objects shared by module factories.
        val scanner = LSPosedScannerModule(ctx)
        val appManager = AppManagerModule(ctx)
        val storage = StorageIsolationModule(ctx)
        val thermal = ThermalModule(ctx)
        val miui = MiuiTuningModule(ctx)
        val performance = PerformanceModule(ctx)
        val power = PowerManagerModule(ctx)
        val configGen = ConfigGenModule(ctx, scanner)
        val serviceGuard = ServiceGuardModule(ctx)

        val entries = listOf(
            ModuleEntry("prop_tuning", { enabled("prop_tuning_enable") }) { AntiDetectionModule(ctx) },
            ModuleEntry("system_tuning", { enabled("system_tuning_enable") || enabled("heavy_task_enable") }) {
                SystemTuningModule(ctx, performance, power, configGen, appManager, serviceGuard, storage, thermal, miui)
            },
            ModuleEntry("game_pause", { enabled("game_pause_enable") }) { GamePauseModule(ctx) },
            ModuleEntry("accessibility_guard", { enabled("accessibility_guard_enable") }) { AccessibilityGuardModule(ctx) },
            ModuleEntry("service_guard", { enabled("service_guard_enable") || enabled("extra_features_enable") }) {
                ServiceGuardModule(ctx)
            },
            ModuleEntry("server_mode", { enabled("server_mode_enable") }) { ServerModeModule(ctx) },
            ModuleEntry("power", { enabled("doze_enable") || enabled("locked_apps_enable") }) {
                PowerManagerModule(ctx)
            },
            ModuleEntry("memory_clean", { enabled("memory_clean_enable") }) { MemoryModule(ctx) },
            ModuleEntry("storage_isolation", { enabled("storage_isolation_enable") }) {
                StorageIsolationModule(ctx)
            },
            ModuleEntry("hma_config", { enabled("hma_config_enable") }) { ConfigGenModule(ctx, scanner) },
            ModuleEntry("network_ipv6", { enabled("network_ipv6_disable_enable") }) { NetworkModule(ctx) },
            // 防护类功能：不受 powersave_enable 影响（省电模式不关闭防护）
            ModuleEntry("skip_mount_guard", { sw.switch("skip_mount_guard_enable") }) { SkipMountGuardModule(ctx) },
        )

        val running = mutableMapOf<String, DaemonLoop>()

        fun syncModules() {
            for (entry in entries) {
                val on = entry.enabled()
                val current = running[entry.name]
                if (on && current == null) {
                    val module = entry.factory()
                    running[entry.name] = module
                    Logger.i("Main", "功能已启用: ${module.javaClass.simpleName} (${entry.name})")
                    module.start()
                } else if (!on && current != null) {
                    Logger.i("Main", "功能已禁用: ${current.javaClass.simpleName} (${entry.name})")
                    current.stop()
                    running.remove(entry.name)
                }
            }
            val enabledNames = entries.filter { it.enabled() }.map { it.name }
            Logger.i("Main", "已启用功能 (${enabledNames.size} 个): ${enabledNames.joinToString(", ")}")
        }

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
