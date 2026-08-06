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

        val loops: List<DaemonLoop> = listOf(
            AntiDetectionModule(ctx),
            systemTuning,
            GamePauseModule(ctx),
            AccessibilityGuardModule(ctx),
            serviceGuard,
            ServerModeModule(ctx),
            power,
            MemoryModule(ctx),
            storage,
            NetworkModule(ctx),
        )
        Logger.i("Main", "starting ${loops.size} module loops")
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
