package io.github.fairyxh.zhangsystemdex.core.power

import io.github.fairyxh.zhangsystemdex.core.ConfigManager
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrator for the "power & background scheduling optimization" subsystem.
 *
 * This is the only class that performs side effects; the decision itself lives
 * in the pure [PowerPolicyEngine]. The flow is strictly event driven:
 *
 *   screen off / battery changed (framework broadcast, [PowerStateMonitor])
 *        -> [PowerPolicyEngine.decide] -> apply or revert via
 *           [KernelPowerManager] + [AppPowerManager]
 *
 * The inherited daemon tick exists *only* as a coarse safety net (event
 * receivers unavailable) and to notice the subsystem switch being turned off
 * from the WebUI. It never performs a sub-second `dumpsys` poll — a hard red
 * line of the long-task brief.
 *
 * Fail-open contract: any failure inside a policy application is caught, the
 * system is left at its defaults, and a counter is bumped for the WebUI. The
 * subsystem never throws into the daemon loop.
 *
 * Everything is reversible: turning the switch off (or charging) calls
 * [revert] which restores every kernel value the manager changed.
 */
class PowerOptimizer(
    ctx: DexContext,
    private val config: ConfigManager,
) : DaemonLoop(ctx, 60_000L, pauseAware = false) {

    override val name: String = "PowerOptimizer"

    private val stats = PowerStatistics()
    private val policy = PowerPolicyEngine(config)
    private val kernel = KernelPowerManager(stats)
    private val apps = AppPowerManager(config.rootDir)

    /** Last applied decision level, so we only act on transitions. */
    @Volatile
    private var appliedLevel = 0

    private val eventDriven = AtomicBoolean(false)

    private val monitor = PowerStateMonitor(
        stats = stats,
        onScreenOff = { onStateEvent("screen-off") },
        onScreenOn = { onStateEvent("screen-on") },
        onChargingChanged = { _, _ -> onStateEvent("battery") }
    )

    override fun onStart() {
        // Register this instance for WebUI observability before doing anything
        // else, so a status request that races startup still sees a live object.
        attach(this)
        // Proactively do nothing on start beyond re-evaluating the current
        // state; the receivers are registered here so we react to real events.
        eventDriven.set(monitor.start())
        if (!eventDriven.get()) {
            Logger.w(name, "事件监听不可用，退化为低频兜底评估（仍非高频轮询）")
        }
        // One evaluation at startup to converge to the correct level.
        safeEvaluate("startup")
        Logger.i(name, "电源子系统已启动（事件驱动=${eventDriven.get()}，统计=${stats.summary()}）")
    }

    override fun onStop() {
        // Drop the observability handle first so the WebUI stops reading a
        // subsystem that is about to revert its kernel state.
        detach(this)
        try {
            monitor.stop()
        } catch (_: Throwable) {
        }
        // Restore everything we changed; never leave the device in a capped state.
        revert("子系统关闭")
        Logger.i(name, "电源子系统已停止")
    }

    override fun tick() {
        // Coarse safety net only. Uses the monitor's own refresh (no dumpsys
        // polling); this runs at the daemon cadence (60s), not sub-second.
        if (!eventDriven.get()) {
            try {
                monitor.refreshCoarse()
            } catch (_: Throwable) {
            }
            safeEvaluate("coarse-tick")
        }
    }

    private fun onStateEvent(cause: String) {
        safeEvaluate(cause)
    }

    /** Evaluate current state and apply/revert as needed. Never throws. */
    private fun safeEvaluate(cause: String) {
        try {
            if (!config.switch("power_optimize_enable")) {
                // Master subsystem switch off: revert and stay idle.
                if (appliedLevel != 0) revert("开关关闭")
                return
            }
            val decision = policy.decide(
                screenOn = monitor.screenOn,
                charging = monitor.charging,
                batteryPct = monitor.batteryLevel
            )
            policy.logDecision(decision, appliedLevel)

            if (decision.level == appliedLevel) return

            when (decision.level) {
                3 -> revert(decision.reason)          // charging -> restore
                0 -> revert(decision.reason)          // idle -> restore
                1 -> applyScreenOff(decision)
                2 -> applyLowBattery(decision)
            }
            appliedLevel = decision.level
            stats.notePolicyApplied(decision.level, "${decision.reason} ($cause)")
        } catch (t: Throwable) {
            stats.noteFailOpen("evaluate($cause)")
            Logger.w(name, "评估/应用失败（fail-open，保持系统默认）: ${t.message}")
        }
    }

    private fun applyScreenOff(d: PowerPolicyEngine.Decision) {
        if (d.cpuCapPercent != null) {
            kernel.applyScreenOffProfile(d.cpuCapPercent)
        }
        if (d.restrictBackground) {
            val stopped = apps.restrictBackground(currentWhitelist(), currentForeground())
            if (stopped > 0) stats.noteBackgroundRestrict()
        }
    }

    private fun applyLowBattery(d: PowerPolicyEngine.Decision) {
        stats.noteLowBatteryEnter()
        if (d.cpuCapPercent != null) {
            // Reuse the conservative screen-off (cap-only, never lock) path with
            // a tighter percentage; it is fully reversible via kernel.revertAll.
            kernel.applyScreenOffProfile(d.cpuCapPercent)
        }
        if (d.restrictBackground) {
            val stopped = apps.restrictBackground(currentWhitelist(), currentForeground())
            if (stopped > 0) stats.noteBackgroundRestrict()
        }
    }

    private fun revert(reason: String) {
        try {
            kernel.revertAll()
            stats.notePolicyReverted(reason)
        } catch (t: Throwable) {
            stats.noteFailOpen("revert")
            Logger.w(name, "还原失败（忽略）: ${t.message}")
        }
    }

    /**
     * Build the live doze whitelist without forcing a shell call: read the
     * same sources the module uses (only_base_enable -> built-in, else the
     * user's doze.conf). Missing pieces simply yield a smaller (safe) set.
     */
    private fun currentWhitelist(): Set<String> {
        val out = LinkedHashSet<String>()
        try {
            if (config.switch("only_base_enable")) {
                out.addAll(parseWhitelist(ConfigManager.DEFAULT_DOZE_CONF))
            } else {
                val f = File(config.rootDir, "doze.conf")
                val text = if (f.exists()) f.readText() else ConfigManager.DEFAULT_DOZE_CONF
                out.addAll(parseWhitelist(text))
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun parseWhitelist(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim().removePrefix("+") }
            .filter { it.isNotEmpty() && it.contains('.') }
            .toList()

    private fun currentForeground(): String? =
        try {
            ProcessUtils.focusedPackage()
        } catch (_: Throwable) {
            null
        }

    /** Exposed for SelfTest / DebugMenu / WebUI diagnostics. */
    fun snapshot(): Map<String, Any> = stats.snapshot().toMutableMap().apply {
        put("eventDriven", eventDriven.get())
        put("screenOn", monitor.screenOn)
        put("charging", monitor.charging)
        put("battery", monitor.batteryLevel)
        put("kernelApplied", kernel.isApplied())
        put("hasBgTargets", apps.hasTargets())
    }

    /** Force a single evaluation now (used by DebugMenu). */
    fun evaluateNow(): String {
        safeEvaluate("manual")
        return stats.summary()
    }

    companion object {
        /**
         * Live instance registry so the WebUI HTTP backend can read the running
         * subsystem's snapshot without coupling to the daemon's module map.
         *
         * Set on [onStart], cleared on [onStop]; volatile because the HTTP
         * backend serves requests on its own threads.
         */
        @Volatile
        private var live: PowerOptimizer? = null

        fun live(): PowerOptimizer? = live

        internal fun attach(instance: PowerOptimizer) {
            live = instance
        }

        internal fun detach(instance: PowerOptimizer) {
            if (live === instance) live = null
        }
    }
}
