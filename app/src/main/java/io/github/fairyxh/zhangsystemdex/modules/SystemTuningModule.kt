package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import io.github.fairyxh.zhangsystemdex.core.ServiceManagerUtils
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import java.io.File

/**
 * The main tuning loop, replacing systemchange.sh. Loaded only when
 * `system_tuning_enable` is on. Runs the regular per-cycle work and every Nth
 * cycle (screen-off only) the heavy maintenance tasks. Every sub-feature is
 * gated by its own switch; disabled sub-features are skipped.
 */
class SystemTuningModule(
    ctx: DexContext,
    private val performance: PerformanceModule,
    private val power: PowerManagerModule,
    private val configGen: ConfigGenModule,
    private val appManager: AppManagerModule,
    private val serviceGuard: ServiceGuardModule,
    private val storage: StorageIsolationModule,
    private val thermal: ThermalModule,
    private val miui: MiuiTuningModule,
) : DaemonLoop(
    ctx,
    if (ctx.config.switch("server_mode_enable")) 300_000L else 600_000L,
) {
    private var cycle = 0
    private var runOnceDone = false

    private val taskInterval: Int
        get() = if (ctx.config.switch("server_mode_enable")) 24 else 6

    override fun onStart() {
        Logger.i(
            name,
            "module started, regular=${ctx.config.switch("system_tuning_enable")}, heavy=${ctx.config.switch("heavy_task_enable")}"
        )
        if (!ctx.config.switch("system_tuning_enable")) return
        if (ctx.config.switch("dexopt_everything_enable")) {
            Logger.i(name, "dex2oat everything compile started")
            ShellExecutor.runBackground("cmd package compile -m everything -a")
        }
        if (ctx.config.switch("appops_allow_enable")) {
            appManager.applyAppOps()
        }
        if (ctx.config.switch("selinux_disable_enable")) {
            ShellExecutor.run("setenforce 0")
            ProcessUtils.writeFile("/sys/fs/selinux/enforce", "0")
            Logger.i(name, "selinux disabled")
        }
    }

    override fun tick() {
        cycle++
        if (ctx.config.switch("system_tuning_enable")) {
            regularTick()
        }
        if (ctx.config.switch("heavy_task_enable")) {
            if (cycle >= taskInterval) {
                cycle = 0
                if (ProcessUtils.isScreenOn()) {
                    Logger.i(name, "screen on, heavy task skipped")
                } else {
                    heavyTick()
                }
            }
        } else {
            cycle = 0
        }
    }

    private fun regularTick() {
        FileUtils.chmod("/proc/fs/ext4", "000")
        SettingsUtils.putGlobal("thermal_warning_threshold", "48000")
        SettingsUtils.putSecure("miui_thermal_limit", "48")
        SettingsUtils.putSystem("perf_profile", "4")
        SettingsUtils.putGlobal("game_thermal_optimized", "0")
        SettingsUtils.putGlobal("render_thread_thermal_throttle", "0")
        SettingsUtils.putGlobal("gpu_render_thread_thermal_limit", "95")
        PropUtils.set("debug.game.video.support", "true")
        PropUtils.set("debug.game.video.speed", "true")

        ProcessUtils.writeFile("/dev/stune/foreground/schedtune.prefer_idle", "1")
        ProcessUtils.writeFile("/dev/stune/background/schedtune.prefer_idle", "1")
        ProcessUtils.writeFile("/dev/stune/rt/schedtune.prefer_idle", "1")
        ProcessUtils.writeFile("/dev/stune/rt/schedtune.boost", "20")
        ProcessUtils.writeFile("/dev/stune/top-app/schedtune.boost", "20")
        ProcessUtils.writeFile("/dev/stune/schedtune.prefer_idle", "1")
        ProcessUtils.writeFile("/dev/stune/top-app/schedtune.prefer_idle", "1")

        if (ctx.config.switch("boost_process_enable")) {
            lowProc("logd")
            boostServices()
        }
        if (ctx.config.switch("max_cpu_enable")) {
            performance.applyMaxCpu()
        }
        PropUtils.deleteMatching("pihook|pixelprops")
        deleteBaseOdex()
        FileUtils.deleteRecursive(File("/data/media/0/Download/com.sy.fuck_miui_thermal"))

        SettingsUtils.putGlobal("phone_name_verify_switch", "false")
        SettingsUtils.putGlobal("activity_manager_constants", "max_cached_processes=3")
        ShellExecutor.run("device_config put activity_manager_native_boot use_freezer true")
        ServiceManagerUtils.surfaceFlingerTransact(1008, 1)
        SettingsUtils.putGlobal("cached_apps_freezer", "enabled")
        ShellExecutor.run("device_config set_sync_disabled_for_tests persistent")
        ShellExecutor.run("device_config put activity_manager max_phantom_processes 2147483647")

        FileUtils.touch("/data/adb/shamiko/whitelist")
        FileUtils.touch("/data/adb/modules/wjw_hiderootauxiliarymod/TrickyStoreListDTGX_Task")
        ShellExecutor.run("pm uninstall --user 0 com.oplus.appdetail")

        FileUtils.chattr("/data/data/cn.gov.pbc.dcep/envc.push", "-i")
        ProcessUtils.writeFile("/data/data/cn.gov.pbc.dcep/envc.push", "r=0")
        FileUtils.chattr("/data/data/cn.gov.pbc.dcep/envc.push", "+i")

        FileUtils.rmQuoted("/data/local/tmp/shizuku")
        FileUtils.rmQuoted("/data/local/tmp/shizuku_starter")
        cleanTencentTmfs()

        if (ctx.config.switch("locked_apps_enable")) {
            power.applyLockedApps()
        }
        FileUtils.mkdirs("/data/media/0/Download/Files")
        FileUtils.mkdirs("/data/media/0/Download/Important")
        FileUtils.mkdirs("/data/media/0/Download/Music")
        Logger.i(name, "regular tick finished")
    }

    private fun heavyTick() {
        Logger.i(name, "heavy maintenance started")
        try {
            if (ctx.config.switch("boost_process_enable")) {
                lowProc("logd")
            }
            fakeBattery()
            if (ctx.config.switch("doze_enable") && !ctx.config.switch("server_mode_enable")) {
                power.applyDozeList()
            }
            if (ctx.config.switch("miui_tuning_enable")) {
                miui.applyAll()
            }
            restartSoter()
            if (ctx.config.switch("thermal_mask_enable")) {
                thermal.applyMask()
            }
            if (ctx.config.switch("dnt_accessibility_enable")) {
                configGen.generateDoNotTryAccessibility()
            }
            FileUtils.deleteRecursive(File("/data/adb/modules/hidemyapplist"))
            if (ctx.config.switch("target_list_enable")) {
                configGen.updateTargetList("tricky")
                configGen.updateTargetList("hmspush")
            }
            if (ctx.config.switch("disable_apps_enable")) {
                appManager.applyDisableApps()
            }
            if (ctx.config.switch("service_guard_enable")) {
                serviceGuard.restartShizukuBrevent()
            }
            if (ctx.config.switch("storage_isolation_enable")) {
                storage.cleanCleanedRubbish()
            }
            syncZhangSetting()
            if (ctx.config.switch("run_once_enable") && !runOnceDone) {
                runOnceDone = true
                Logger.i(name, "run_once=true, stopping after first heavy tick")
                stop()
            }
        } catch (t: Throwable) {
            Logger.e(name, "heavy tick failed", t)
        }
        Logger.i(name, "heavy maintenance finished")
    }

    private fun fakeBattery() {
        val rules = listOf(
            "/sys/class/qcom-battery/fake_temp" to "25",
            "/sys/class/qcom-battery/fake_soh" to "100",
            "/sys/class/qcom-battery/fake_cycle" to "10",
        )
        for ((path, value) in rules) {
            val f = File(path)
            if (!f.exists()) continue
            ShellExecutor.run("chown root:root '$path'")
            FileUtils.chmod(path, "0666")
            ProcessUtils.writeFile(path, value)
            FileUtils.chmod(path, "0444")
        }
        Logger.i(name, "battery fake values locked")
    }

    private fun lowProc(pattern: String) {
        for (pid in ProcessUtils.pidsOf(pattern)) {
            ShellExecutor.run("chrt -i -p $pid 1")
            ProcessUtils.renice(pid, 19)
        }
    }

    private fun boostServices() {
        boostHome("com.miui.home")
        val homePackages = ShellExecutor.run(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep /"
        )?.lineSequence()?.toList() ?: emptyList()
        for (line in homePackages) {
            boostHome(line.trim().substringBefore('/'))
        }
        val imes = ShellExecutor.run("ime list -s")?.lineSequence()?.toList() ?: emptyList()
        for (l in imes) boostHome(l.trim().substringBefore('/'))
        boostUi("com.android.systemui")
        boostUi("surfaceflinger")
        boostUi("netd")
        boostUi("hostapd")
    }

    private fun boostHome(pkg: String) {
        for (pid in ProcessUtils.pidsOf(pkg)) {
            ProcessUtils.renice(pid, -20)
            ShellExecutor.run("chrt -f -p 85 $pid")
            ProcessUtils.appendCgroup(pid, "/dev/cpuset/top-app/cgroup.procs")
            ProcessUtils.appendCgroup(pid, "/dev/stune/top-app/cgroup.procs")
        }
    }

    private fun boostUi(pkg: String) {
        for (pid in ProcessUtils.pidsOf(pkg)) {
            ProcessUtils.renice(pid, -20)
            ShellExecutor.run("chrt -f -p 90 $pid")
            ProcessUtils.appendCgroup(pid, "/dev/cpuset/top-app/cgroup.procs")
            ProcessUtils.appendCgroup(pid, "/dev/stune/top-app/cgroup.procs")
        }
    }

    private fun deleteBaseOdex() {
        var count = 0
        val appDir = File("/data/app")
        if (!appDir.exists()) return
        appDir.walkTopDown().forEach { f ->
            if (f.name == "base.odex") {
                f.delete()
                count++
            }
        }
        if (count > 0) Logger.i(name, "deleted $count base.odex")
    }

    private fun cleanTencentTmfs() {
        val media = File("/data/media/0")
        for (entry in media.listFiles() ?: return) {
            if (!entry.isDirectory) continue
            val tmfs = File(entry, ".tmfs")
            if (!tmfs.exists()) continue
            FileUtils.chattr(tmfs.path, "-R -i")
            FileUtils.deleteRecursive(tmfs)
            FileUtils.touch(tmfs.path)
            FileUtils.chattr(tmfs.path, "+i")
        }
    }

    private fun restartSoter() {
        ShellExecutor.run("stop vendor.soter")
        sleepSafe(3000)
        ShellExecutor.run("pm clear com.tencent.soter.soterserver")
        ShellExecutor.run("start vendor.soter")
        sleepSafe(5000)
    }

    private fun syncZhangSetting() {
        val src = File(ctx.modDir, "ZhangSetting")
        val dst = File("/data/media/0/Download/ZhangSetting")
        if (!src.exists()) return
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isFile) FileUtils.copyFile(f, File(dst, f.name))
        }
        FileUtils.copyFile(File(ctx.modDir, "maxcpu.sh"), File(dst, "maxcpu.sh"))
        FileUtils.copyFile(File(ctx.modDir, "设置CPU最高频率.sh"), File(dst, "设置CPU最高频率.sh"))
    }
}
