package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.PropUtils

/**
 * Bluetooth A2DP / LE-Audio hardware offload guard.
 *
 * Built-in loop guard (the former one-shot shell script approach was removed).
 * The old script re-applied a few `resetprop` values at fixed timestamps
 * (0s / 20s / 60s) after boot to defeat Bluetooth audio offload, which is a
 * common cause of stutter, no-sound and reconnect loops on some firmware.
 * This module keeps the SAME EFFECTIVE
 * PRINCIPLE (force the offload "disabled" props back to a known-good state) but
 * instead of a fixed timeline it re-asserts them on a loop, so any late
 * overwrite by the vendor stack is silently corrected.
 *
 * It intentionally does NOT need to be identical to the shell script; it only
 * reuses the equivalent mechanism. All writes prefer the persistent property
 * space (`resetprop -n` / `persist.*`) exactly like the original script did.
 *
 * This loop is registered as a normal daemon entry, so it is gated by the
 * `bt_offload_guard_enable` switch and also frozen while a monitored game runs
 * (pauseAware = true).
 */
class BtOffloadGuardModule(ctx: DexContext) : DaemonLoop(ctx, 30000L, pauseAware = true) {

    override val name: String = "BtOffloadGuard"

    /**
     * name -> value pairs re-asserted every cycle.
     *
     * We only flip the "disabled" style switches to false / keep the "supported"
     * style switches true. `ro.*` props are read-only on a live system, so we
     * rely on resetprop's ability to overwrite them in-memory (same trick the
     * original script used).
     */
    private val desired: List<Pair<String, String>> = listOf(
        // 1) Turn OFF the "offload disabled" switches -> offload re-enabled path handled by vendor,
        //    but the *blocking* flags must NOT stay set, otherwise audio routing stalls.
        "persist.bluetooth.a2dp_offload.disabled" to "false",
        "persist.bluetooth.leaudio_offload.disabled" to "false",
        // 2) Re-assert that hardware offload is supported/available.
        "ro.bluetooth.a2dp_offload.supported" to "true",
        "ro.bluetooth.leaudio_offload.supported" to "true",
        "vendor.audio.feature.a2dp_offload.enable" to "true"
    )

    /** Last observed values, used only to log meaningful changes. */
    private val lastApplied = HashMap<String, String>()

    override fun onStart() {
        Logger.i(name, "蓝牙音频 offload 循环守护已启动（内置 dex 循环，周期 ${intervalMs}ms）")
    }

    override fun tick() {
        var changed = 0
        for ((prop, value) in desired) {
            val current = PropUtils.get(prop)
            if (current == value) continue
            // persistent = true  -> 走 resetprop -p -n，等价于原脚本的 resetprop 行为
            PropUtils.set(prop, value, persistent = true)
            // 读回校验：resetprop 的 ro.* 覆盖可能被内核拒绝，只有真正生效才计数。
            val after = PropUtils.get(prop)
            if (after == value) {
                changed++
                if (lastApplied[prop] != value) {
                    Logger.i(name, "已复位 $prop=$value （原值=${current ?: "<空>"}）")
                }
                lastApplied[prop] = value
            } else {
                Logger.w(name, "复位 $prop=$value 未生效（当前=${after ?: "<空>"}）")
            }
        }
        if (changed > 0) {
            Logger.i(name, "本轮已校正 $changed 个蓝牙 offload 属性")
        }
    }

    override fun onStop() {
        Logger.i(name, "蓝牙音频 offload 循环守护已停止")
    }
}
