package io.github.fairyxh.zhangsystemdex.core.power

import io.github.fairyxh.zhangsystemdex.core.ConfigManager
import io.github.fairyxh.zhangsystemdex.core.Logger

/**
 * Pure decision layer: given the current power state, decide which conservative
 * level applies. It performs NO I/O — the orchestrator ([PowerOptimizer])
 * executes whatever [Decision] this returns. Keeping it pure makes the policy
 * easy to reason about and impossible to crash the daemon.
 *
 * Levels (ascending aggressiveness):
 *  0 = IDLE      nothing to do (screen on, not charging, battery healthy)
 *  1 = SCREEN_OFF  screen off: mild kernel cap + optional background restrict
 *  2 = LOW_BATTERY battery below the configured threshold: stronger cap
 *  3 = CHARGING  charging: FORCE IDLE (revert everything, let the system charge fast)
 *
 * Charging is treated as the "restored" state: it always wins and reverts any
 * screen-off/low-battery profile, matching the brief's "充电自动退出".
 */
class PowerPolicyEngine(private val config: ConfigManager) {

    class Decision(
        val level: Int,
        val reason: String,
        /** percent to cap the max CPU frequency at, or null to leave untouched */
        val cpuCapPercent: Int?,
        /** whether background apps may be restricted at this level */
        val restrictBackground: Boolean,
        /** whether charging (forces revert) */
        val charging: Boolean,
    )

    /**
     * @param screenOn    current screen state from [PowerStateMonitor]
     * @param charging    current charging state
     * @param batteryPct  0..100
     */
    fun decide(screenOn: Boolean, charging: Boolean, batteryPct: Int): Decision {
        val lowThreshold = config.getString("power_low_battery_threshold", "20")
            .trim().toIntOrNull()?.coerceIn(1, 99) ?: 20

        if (charging && config.switch("power_charging_release")) {
            return Decision(
                level = 3,
                reason = "充电中（电池 ${batteryPct}%），恢复系统默认策略",
                cpuCapPercent = null,
                restrictBackground = false,
                charging = true
            )
        }

        if (batteryPct in 0 until lowThreshold) {
            return Decision(
                level = 2,
                reason = "低电量（${batteryPct}% < ${lowThreshold}%）",
                cpuCapPercent = config.getString("power_low_battery_cpu_cap", "55")
                    .trim().toIntOrNull()?.coerceIn(10, 100) ?: 55,
                restrictBackground = config.switch("power_low_battery_restrict_bg"),
                charging = false
            )
        }

        if (!screenOn) {
            val capEnabled = config.switch("power_screen_off_cpu_cap")
            return Decision(
                level = 1,
                reason = "息屏（电池 ${batteryPct}%）",
                cpuCapPercent = if (capEnabled)
                    config.getString("power_screen_off_cpu_cap_percent", "70")
                        .trim().toIntOrNull()?.coerceIn(10, 100) ?: 70
                else null,
                restrictBackground = config.switch("power_screen_off_restrict_bg"),
                charging = false
            )
        }

        return Decision(
            level = 0,
            reason = "亮屏且电量正常（${batteryPct}%）",
            cpuCapPercent = null,
            restrictBackground = false,
            charging = false
        )
    }

    /** Whether the whole subsystem's own switch is on. */
    fun subsystemEnabled(): Boolean = config.switch("power_optimize_enable")

    fun logDecision(d: Decision, previousLevel: Int) {
        if (d.level != previousLevel) {
            Logger.i("PowerPolicyEngine", "策略切换: $previousLevel -> ${d.level}（${d.reason}）")
        }
    }
}