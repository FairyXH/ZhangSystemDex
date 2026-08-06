package io.github.fairyxh.zhangsystemdex

import android.os.Process
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
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
 * create a thread. powersave_enable overrides every non-special switch to off.
 */
object Main {
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
        PropUtils.detect()
        val sysCtx = SystemContext.get()
        Logger.i("Main", if (sysCtx != null) "system context available, framework APIs preferred" else "system context unavailable, shell fallback active")

        val sw = ctx.config
        fun enabled(key: String): Boolean {
            if (sw.switch("powersave_enable")) return false
            return sw.switch(key)
        }

        val scanner = LSPosedScannerModule(ctx)
        val appManager = AppManagerModule(ctx)
        val power = PowerManagerModule(ctx)
        val storage = StorageIsolationModule(ctx)
        val configGen = ConfigGenModule(ctx, scanner)
        val serviceGuard = ServiceGuardModule(ctx)
        val thermal = ThermalModule(ctx)
        val miui = MiuiTuningModule(ctx)
        val performance = PerformanceModule(ctx)
        val systemTuning = SystemTuningModule(
            ctx, performance, power, configGen, appManager, serviceGuard, storage, thermal, miui,
        )

        val loops = mutableListOf<DaemonLoop>()
        if (enabled("prop_tuning_enable")) loops.add(AntiDetectionModule(ctx))
        if (enabled("system_tuning_enable")) loops.add(systemTuning)
        if (enabled("game_pause_enable")) loops.add(GamePauseModule(ctx))
        if (enabled("accessibility_guard_enable")) loops.add(AccessibilityGuardModule(ctx))
        if (enabled("service_guard_enable") || enabled("extra_features_enable")) loops.add(serviceGuard)
        if (enabled("server_mode_enable")) loops.add(ServerModeModule(ctx))
        if (enabled("doze_enable") || enabled("locked_apps_enable")) loops.add(power)
        if (enabled("memory_clean_enable")) loops.add(MemoryModule(ctx))
        if (enabled("storage_isolation_enable")) loops.add(storage)
        if (enabled("hma_config_enable")) loops.add(configGen)
        if (enabled("network_ipv6_disable_enable")) loops.add(NetworkModule(ctx))

        Logger.i("Main", "starting ${loops.size} module loops: ${loops.joinToString { it.javaClass.simpleName }}")
        loops.forEach { it.start() }

        Runtime.getRuntime().addShutdownHook(Thread {
            Logger.i("Main", "shutdown hook, stopping modules")
            loops.forEach { it.stop() }
        })

        Logger.i("Main", "daemon ready")
        while (true) {
            try {
                Thread.sleep(60000)
            } catch (_: InterruptedException) {
                break
            }
        }
        Logger.i("Main", "daemon exiting")
    }
}
