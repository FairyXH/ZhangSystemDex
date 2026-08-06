package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File

/**
 * Optional IPv6 disable, previously the orphaned DisabledIpv6.sh. Disabled by
 * default; enable through config.json `network_ipv6_disable`.
 */
class NetworkModule(ctx: DexContext) : DaemonLoop(ctx, 600_000L, pauseAware = false) {
    override fun onStart() {
        if (!ctx.config.getBool("network_ipv6_disable", false)) {
            Logger.i(name, "disabled by config, exiting")
            stop()
            return
        }
        Logger.i(name, "module started")
    }

    override fun tick() {
        val conf = File("/proc/sys/net/ipv6/conf")
        val dirs = conf.listFiles() ?: return
        var changed = 0
        for (dir in dirs) {
            if (ProcessUtils.writeFile(File(dir, "disable_ipv6").path, "1")) changed++
            ProcessUtils.writeFile(File(dir, "accept_ra_defrtr").path, "0")
        }
        Logger.i(name, "disabled ipv6 on $changed interfaces")
    }
}
