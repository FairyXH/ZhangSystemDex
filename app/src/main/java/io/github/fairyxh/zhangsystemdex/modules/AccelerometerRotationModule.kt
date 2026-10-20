package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import android.provider.Settings

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
 * - Uses Settings.System directly, without a polling shell command.
 * - Simple, focused, and hard to accidentally disable by other logic.
 */
class AccelerometerRotationModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L) {
    private var original: String? = null
    private var changed = false

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
        applyRotationSetting(ctx.config.switch(PREF_KEY))
    }

    private fun applyRotationSystem(enabled: Boolean) {
        val resolver = ctx.androidContext?.contentResolver ?: return
        val current = Settings.System.getString(resolver, "accelerometer_rotation")
        if (enabled) {
            if (current != "0" && Settings.System.putString(resolver, "accelerometer_rotation", "0")) {
                if (!changed) original = current
                changed = true
                Logger.i(name, "强制禁用加速计自动旋转")
            }
        } else if (changed) {
            if (current == "0") Settings.System.putString(resolver, "accelerometer_rotation", original)
            changed = false
            original = null
        }
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
