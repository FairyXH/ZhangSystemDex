package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import java.io.File

/**
 * Dedicated-server mode (isserver=true): keeps wifi/bluetooth/wake state,
 * performance governor, optional frpc/AutoMusicStart commands and screen
 * wake. Replaces IsServe.sh; external commands are configurable.
 */
class ServerModeModule(ctx: DexContext) : DaemonLoop(ctx, 60_000L, pauseAware = false) {
    private var round = 0

    override fun onStart() {
        if (!ctx.config.switch("server_mode_enable")) {
            Logger.i(name, "server mode disabled by switch, exiting")
            stop()
            return
        }
        Logger.i(name, "server mode active")
    }

    override fun tick() {
        round++
        FileUtilsChmodRc()
        ShellExecutor.run("svc wifi enable")
        ShellExecutor.run("svc bluetooth enable")
        ShellExecutor.run("svc power stayon")
        SettingsUtils.putGlobal("wifi_on", "1")
        SettingsUtils.putGlobal("bluetooth_on", "1")
        ShellExecutor.run("input keyevent 126")
        ShellExecutor.run("dumpsys deviceidle disable")
        ProcessUtils.writeFile("/sys/power/state", "on")

        val frpc = ctx.config.getString("frpc_command", "").trim()
        if (frpc.isNotEmpty()) {
            val running = ShellExecutor.run("ps -A | grep frpc | grep -v grep")
            if (running.isNullOrEmpty()) ShellExecutor.runBackground(frpc)
        }
        val music = ctx.config.getString("automusic_command", "").trim()
        if (music.isNotEmpty()) ShellExecutor.runBackground(music)

        setAllGovernors("performance")

        if (round >= 3) {
            round = 0
            ShellExecutor.run("am start -n me.neversleep.plusplus/.MainActivity --ez power true")
            ShellExecutor.run("input keyevent 224")
            SettingsUtils.putSystem("screen_off_timeout", "2147483647")
        }
    }

    private fun FileUtilsChmodRc() {
        File("/data/Zhang-Server/etc/rc.d/rc.local").setExecutable(true, false)
        File("/data/Zhang-Server/etc/rc.local").setExecutable(true, false)
    }

    private fun setAllGovernors(governor: String) {
        val cpuDir = File("/sys/devices/system/cpu")
        val cores = cpuDir.listFiles { f ->
            f.isDirectory && Regex("^cpu\\d+$").matches(f.name)
        } ?: return
        for (core in cores) {
            ProcessUtils.writeFile(File(core, "cpufreq/scaling_governor").path, governor)
        }
    }
}
