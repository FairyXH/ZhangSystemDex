package io.github.fairyxh.zhangsystemdex

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

    @JvmStatic
    fun main(args: Array<String>) {
        val modDir = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "/data/adb/modules/Zhang"
        val ctx = DexContext(modDir)
        DexContext.current = ctx
        ctx.load()

        Logger.i("Main", "========================================")
        Logger.i("Main", "ZhangSystemDex starting")
        Logger.i("Main", "uid=${Process.myUid()} moddir=$modDir root=${ctx.config.rootDir} logEnabled=${ctx.config.logEnabled}")
        if (!RootUtils.isRoot()) {
            Logger.e("Main", "not running as root, exiting")
            return
        }
        HiddenApiBypass.enable()
        PropUtils.detect()
        val sysCtx = SystemContext.get()
        Logger.i("Main", if (sysCtx != null) "system context available, framework APIs preferred" else "system context unavailable, shell fallback active")

        // Debug menu mode: run a single feature once by number, then exit.
        if (args.contains("menu")) {
            Logger.i("Main", "debug menu mode")
            if (!DebugMenu.run(ctx)) {
                Logger.i("Main", "debug action finished, exiting")
                return
            }
            Logger.i("Main", "normal start after debug menu")
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
            ModuleEntry("system_tuning", { enabled("system_tuning_enable") }) {
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
        Logger.i("Main", "daemon ready, watching switches.conf every 60s")

        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.i("Main", "shutdown hook, stopping modules")
            running.values.forEach { it.stop() }
        })

        while (true) {
            try {
                if (ctx.config.reloadSwitchesIfChanged()) {
                    Logger.i("Main", "switches.conf 已变化，重新同步模块")
                    syncModules()
                }
                Thread.sleep(60000)
            } catch (t: Throwable) {
                Logger.e("Main", "watch loop error", t)
                try {
                    Thread.sleep(60000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Logger.i("Main", "daemon exiting")
    }
}
