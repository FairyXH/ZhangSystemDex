package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor

/****
 * Accelerometer rotation disable module.
 * dedicated to disabling system accelerometer-based auto-rotation.
 * Runs its own independent loop and respects a configurable switch.
 *
 * Behavior:
 * - When `accelerometer_rotation_enable` is ON, every cycle writes
 *   `settings put system accelerometer_rotation 0` to disable auto-rotation.
 * - When the switch is OFF, the setting is cleared (restored to user preference).
 *
 * Design notes:
 * - Own DaemonLoop so it doesn't interfere with SystemTuningModule cycles.
 * - Uses SettingsUtils putSystem/shell for maximum compatibility.
 * - Simple, focused, and hard to accidentally disable by other logic.
 */
class AccelerometerRotationModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L) {

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
        if (ctx.config.switch(PREF_KEY)) {
            applyRotationSetting(true)
        } else {
            // Clear the forced setting, let system use user preference
            applyRotationSetting(false)
        }
    }

    private fun applyRotationSystem(enabled: Boolean) {
        if (enabled) {
            // Disable auto-rotation: 0 = off, based on Android system setting
            SettingsUtils.putSystem("accelerometer_rotation", "0")
            Logger.i(name, "强制禁用加速计自动旋转 (accelerometer_rotation=0)")
        } else {
            // Remove/clear the forced value — restores user's system preference
            // shell command: settings put system accelerometer_rotation user
            ShellExecutor.run("settings put system accelerometer_rotation user")
            Logger.i(name, "恢复加速计自动旋转用户首选项")
        }
    }

    private fun applyRotationSetting(enabled: Boolean) {
        try {
            applyRotationSystem(enabled)
        } catch (t: Throwable) {
            Logger.e(name, "应用加速计旋转设置失败", t)
        }
    }
}
