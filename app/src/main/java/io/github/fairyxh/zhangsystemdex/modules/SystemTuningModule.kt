package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
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
    configuredIntervalMs(ctx),
) {
    private var cycle = 0
    private var runOnceDone = false

    private val taskInterval: Int
        get() {
            val n = ctx.config.getString("heavy_interval_cycles", "").toIntOrNull()
            val base = if (ctx.config.switch("server_mode_enable")) 24 else 6
            return (n ?: base).coerceIn(1, 1000)
        }

    companion object {
        private fun configuredIntervalMs(ctx: DexContext): Long {
            val sec = ctx.config.getString("tuning_interval_seconds", "").toLongOrNull()
            val base = if (ctx.config.switch("server_mode_enable")) 300L else 600L
            return (sec ?: base).coerceIn(30L, 86400L) * 1000L
        }
    }

    override fun onStart() {
        Logger.i(
            name,
            "模块启动，周期=${intervalMs}ms，常规任务=${ctx.config.switch("system_tuning_enable")}，高占用=${ctx.config.switch("heavy_task_enable")}"
        )
        Logger.i(
            name,
            "周期配置: tuning_interval_seconds=${ctx.config.getString("tuning_interval_seconds", "").ifBlank { "默认" }} -> 生效 ${intervalMs / 1000}s, " +
                "heavy_interval_cycles=${ctx.config.getString("heavy_interval_cycles", "").ifBlank { "默认" }} -> 生效 ${taskInterval} 周期"
        )
        // 首次启动立即执行一次高占用任务（若开启且熄屏）；亮屏则输出跳过提示，下一周期立即重试。
        maybeRunHeavy()
        if (!ctx.config.switch("system_tuning_enable")) return
        if (ctx.config.switch("dexopt_everything_enable")) {
            Logger.i(name, "开始执行 dex2oat everything 编译")
            ShellExecutor.runBackground("cmd package compile -m everything -a")
        }
        if (ctx.config.switch("appops_allow_enable")) {
            appManager.applyAppOps()
        }
        if (ctx.config.switch("selinux_disable_enable")) {
            ShellExecutor.run("setenforce 0")
            ProcessUtils.writeFile("/sys/fs/selinux/enforce", "0")
            Logger.i(name, "已关闭 SELinux")
        }
    }

    override fun tick() {
        cycle++
        if (ctx.config.switch("system_tuning_enable")) {
            regularTick()
        }
        if (ctx.config.switch("heavy_task_enable")) {
            if (cycle >= taskInterval) {
                if (ProcessUtils.isScreenOn()) {
                    // Keep the cycle counter: retry on the very next cycle instead
                    // of waiting another full taskInterval.
                    Logger.i(name, "高占用任务到期但未息屏，跳过执行，下一周期立即重试 (cycle=$cycle, interval=${intervalMs}ms)")
                } else {
                    cycle = 0
                    heavyTick()
                }
            }
        } else {
            cycle = 0
        }
    }

    /** 启动时立即检查高占用：熄屏执行，亮屏输出跳过提示（保持周期计数，下一周期重试）。 */
    private fun maybeRunHeavy() {
        if (!ctx.config.switch("heavy_task_enable")) return
        if (ProcessUtils.isScreenOn()) {
            Logger.i(name, "启动时高占用任务就绪但未息屏，跳过执行，下一周期立即重试 (cycle=$cycle)")
        } else {
            cycle = 0
            heavyTick()
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
        Logger.i(name, "常规周期任务执行完毕")
    }

    private fun heavyTick() {
        Logger.i(name, "高占用维护开始")
        val done = ArrayList<String>()
        try {
            antiErrorDialogs()
            done += "防错误弹窗"
            if (ctx.config.switch("boost_process_enable")) {
                lowProc("logd")
                done += "logd 进程优先级降低"
            }
            fakeBattery()
            done += "假电池锁定"
            if (ctx.config.switch("doze_enable") && !ctx.config.switch("server_mode_enable")) {
                power.applyDozeList()
                done += "Doze 白名单"
            }
            if (ctx.config.switch("miui_tuning_enable")) {
                miui.applyAll()
                done += "MIUI 调优"
            }
            restartSoter()
            done += "Soter 服务重启"
            if (ctx.config.switch("thermal_mask_enable")) {
                thermal.applyMask()
                done += "温控遮蔽"
            }
            if (ctx.config.switch("dnt_accessibility_enable")) {
                configGen.generateDoNotTryAccessibility()
                done += "DNTA 规则"
            }
            FileUtils.deleteRecursive(File("/data/adb/modules/hidemyapplist"))
            done += "HMA 模块目录清理"
            if (ctx.config.switch("target_list_enable")) {
                configGen.updateTargetList("tricky")
                configGen.updateTargetList("hmspush")
                done += "tricky/hmspush 目标列表"
            }
            if (ctx.config.switch("disable_apps_enable")) {
                appManager.applyDisableApps()
                done += "应用停用/遮蔽"
            }
            if (ctx.config.switch("service_guard_enable")) {
                serviceGuard.restartShizukuBrevent()
                done += "服务守护重启"
            }
            if (ctx.config.switch("storage_isolation_enable")) {
                storage.cleanCleanedRubbish()
                done += "存储隔离清理"
            }
            val released = syncZhangSetting()
            done += if (released < 0) "ZhangSetting 释放(模块无该目录，跳过)" else "ZhangSetting 释放($released 个文件)"
            if (ctx.config.switch("run_once_enable") && !runOnceDone) {
                runOnceDone = true
                done += "run_once 停止"
                Logger.i(name, "run_once=true，首轮高占用执行完成后停止")
                stop()
            }
        } catch (t: Throwable) {
            Logger.e(name, "高占用任务执行失败", t)
            done += "整体异常(${t.message})"
        }
        Logger.i(name, "高占用维护完成: ${done.joinToString("、")}")
    }

    private fun antiErrorDialogs() {
        SettingsUtils.putGlobal("phone_name_verify_switch", "false")
        SettingsUtils.putGlobal("activity_manager_constants", "max_cached_processes=3")
        ShellExecutor.run("device_config put activity_manager_native_boot use_freezer true")
        ServiceManagerUtils.surfaceFlingerTransact(1008, 1)
        SettingsUtils.putGlobal("cached_apps_freezer", "enabled")
        ShellExecutor.run("device_config set_sync_disabled_for_tests persistent")
        ShellExecutor.run("device_config put activity_manager max_phantom_processes 2147483647")
        Logger.i(name, "防错误弹窗规则已应用")
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
            FileUtils.chown(path, 0, 0)
            FileUtils.chmod(path, "0666")
            ProcessUtils.writeFile(path, value)
            FileUtils.chmod(path, "0444")
        }
        Logger.i(name, "假电池值已锁定")
    }

    private fun lowProc(pattern: String) {
        for (pid in ProcessUtils.pidsOf(pattern)) {
            ShellExecutor.run("chrt -i -p $pid 1")
            ProcessUtils.renice(pid, 19)
        }
    }

    private fun boostServices() {
        boostHome("com.miui.home")
        for (pkg in FrameworkOps.homePackages()) {
            boostHome(pkg)
        }
        for (pkg in AppListProvider.inputMethods()) {
            boostHome(pkg)
        }
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
        if (count > 0) Logger.i(name, "已删除 $count 个 base.odex")
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
        FrameworkOps.ctlService("stop", "vendor.soter")
        sleepSafe(3000)
        ShellExecutor.run("pm clear com.tencent.soter.soterserver")
        FrameworkOps.ctlService("start", "vendor.soter")
        sleepSafe(5000)
    }

    private fun syncZhangSetting(): Int {
        return FileUtils.copyDirRecursive(
            File(ctx.modDir, "ZhangSetting"),
            File("/data/media/0/Download/ZhangSetting")
        )
    }
}
