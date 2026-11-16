package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.RuntimeRegistry
import io.github.fairyxh.zhangsystemdex.core.SettingsUtils

/**
 * Accelerometer rotation disable module.
 * Dedicated to disabling system accelerometer-based auto-rotation.
 *
 * Behavior:
 * - When enabled, target value is accelerometer_rotation=0. Every write is
 *   verified by read-back; any failure schedules a 30s short retry instead of
 *   silently waiting for the 10-minute reapply window.
 * - When disabled, restores the value captured before the module changed it.
 * - Each applied action is reported to RuntimeRegistry so /api/overview
 *   shows a non-empty lastAction for this module.
 */
class AccelerometerRotationModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L) {
    private var original: String? = null
    private var changed = false
    private var lastEnabled: Boolean? = null
    private var lastAppliedAt = 0L
    private var lastFailAt = 0L

    companion object {
        private const val PREF_KEY = "accelerometer_rotation_enable"
    }

    override fun onStart() {
        Logger.i(name, "加速计自动旋转模块启动 — 初始周期 30s，开关：${ctx.config.switch(PREF_KEY)}")
        applyRotationSetting(ctx.config.switch(PREF_KEY))
    }

    override fun tick() {
        val enabled = ctx.config.switch(PREF_KEY)
        val now = System.currentTimeMillis()
        // 状态跃迁、失败短重试、周期重施，三者任一命中即应用。
        val shortRetry = lastFailAt > 0 && now - lastFailAt >= 30_000L
        if (enabled != lastEnabled || shortRetry || now - lastAppliedAt >= 600_000L) {
            applyRotationSetting(enabled)
        } else {
            // 未到重施窗口也要上报 tick，保持 overview 的 lastTickAgoMs 新鲜。
            RuntimeRegistry.markTick(registryKey ?: name)
        }
    }

    private fun applyRotationSystem(enabled: Boolean) {
        val current = SettingsUtils.getSystem("accelerometer_rotation")
        var ok = true
        if (enabled) {
            if (current != "0") {
                ok = SettingsUtils.putSystem("accelerometer_rotation", "0")
                if (!changed) original = current
                changed = true
                if (ok) Logger.i(name, "强制禁用加速计自动旋转")
                else Logger.w(name, "写入 accelerometer_rotation=0 未确认，30s 后重试")
            }
            // 目标值已在位则视为成功；外部改回会由下一轮 tick 捕获并纠正。
        } else if (changed) {
            if (original != null && current != original) {
                ok = SettingsUtils.putSystem("accelerometer_rotation", original!!)
                if (ok) Logger.i(name, "已恢复自动旋转为 $original")
                else Logger.w(name, "恢复 accelerometer_rotation 失败，30s 后重试")
            }
            if (ok) {
                changed = false
                original = null
            }
        }
        lastEnabled = enabled
        lastAppliedAt = System.currentTimeMillis()
        lastFailAt = if (ok) 0L else lastAppliedAt
        val key = registryKey ?: name
        val action = when {
            !ok -> "失败（30s后重试）"
            enabled && current != "0" -> "已写入 0（禁用自动旋转）"
            enabled -> "确认 0（禁用自动旋转）"
            else -> "空闲"
        }
        RuntimeRegistry.markTick(key, action)
    }

    private fun applyRotationSetting(enabled: Boolean) {
        try {
            applyRotationSystem(enabled)
        } catch (t: Throwable) {
            // 单条设置失败绝不向外抛，更不阻碍其他逻辑；标记失败等下轮重试。
            lastFailAt = System.currentTimeMillis()
            Logger.e(name, "应用加速计旋转设置失败", t)
            RuntimeRegistry.markTick(registryKey ?: name, "异常（30s后重试）")
        }
    }

    override fun onStop() {
        applyRotationSetting(false)
    }
}
