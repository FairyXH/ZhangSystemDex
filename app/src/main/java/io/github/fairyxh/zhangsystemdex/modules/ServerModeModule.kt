package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
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
        runOnce()
    }

    /** Run the server-mode actions once (for the debug menu). */
    fun runOnce() {
        round++
        FileUtilsChmodRc()
        FrameworkOps.wifiEnabled(true)
        FrameworkOps.bluetoothEnabled(true)
        SettingsUtils.putGlobal("stay_on_while_plugged_in", "7")
        SettingsUtils.putGlobal("wifi_on", "1")
        SettingsUtils.putGlobal("bluetooth_on", "1")
        FrameworkOps.mediaPlay()
        ShellExecutor.run("dumpsys deviceidle disable")
        ProcessUtils.writeFile("/sys/power/state", "on")

        val frpc = ctx.config.getString("frpc_command", "").trim()
        if (frpc.isNotEmpty()) {
            if (ProcessUtils.pidsOf("frpc").isEmpty()) ShellExecutor.runBackground(frpc)
        }
        val music = ctx.config.getString("automusic_command", "").trim()
        if (music.isNotEmpty()) ShellExecutor.runBackground(music)

        setAllGovernors("performance")

        if (round >= 3) {
            round = 0
            FrameworkOps.startActivity("me.neversleep.plusplus/.MainActivity", mapOf("power" to true))
            FrameworkOps.wakeUp()
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
