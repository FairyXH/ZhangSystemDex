package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils

/****
 * Accelerometer rotation disable module.
 * dedicated to disabling system accelerometer-based auto-rotation.
 * Runs its own independent loop and respects a configurable switch.
 *
 * Behavior:
 * - When enabled, writes 0 only if the setting differs.
 * - When disabled, restores the value captured before the module changed it.
 *
 * Design notes:
 * - Own DaemonLoop so it doesn't interfere with SystemTuningModule cycles.
 * - Checks at most every ten minutes after startup or a switch change.
 * - Simple, focused, and hard to accidentally disable by other logic.
 */
class AccelerometerRotationModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L) {
    private var original: String? = null
    private var changed = false
    private var lastEnabled: Boolean? = null
    private var lastAppliedAt = 0L

    companion object {
        private const val PREF_KEY = "accelerometer_rotation_enable"
    }

    override fun onStart() {
        Logger.i(
            name,
            "加速计自动旋转模块启动 — 初始周期 30s，开关：${ctx.config.switch(PREF_KEY)}"
        )
        applyRotationSetting(ctx.config.switch(PREF_KEY))
    }

    override fun tick() {
        val enabled = ctx.config.switch(PREF_KEY)
        val now = System.currentTimeMillis()
        if (enabled != lastEnabled || now - lastAppliedAt >= 600_000L) {
            applyRotationSetting(enabled)
        }
    }

    private fun applyRotationSystem(enabled: Boolean) {
        val current = SettingsUtils.getSystem("accelerometer_rotation")
        if (enabled) {
            if (current != "0") {
                SettingsUtils.putSystem("accelerometer_rotation", "0")
                if (!changed) original = current
                changed = true
                Logger.i(name, "强制禁用加速计自动旋转")
            }
        } else if (changed) {
            if (current == "0" && original != null) SettingsUtils.putSystem("accelerometer_rotation", original!!)
            changed = false
            original = null
        }
        lastEnabled = enabled
        lastAppliedAt = System.currentTimeMillis()
    }

    private fun applyRotationSetting(enabled: Boolean) {
        try {
            applyRotationSystem(enabled)
        } catch (t: Throwable) {
            Logger.e(name, "应用加速计旋转设置失败", t)
        }
    }

    override fun onStop() {
        applyRotationSetting(false)
    }
}
