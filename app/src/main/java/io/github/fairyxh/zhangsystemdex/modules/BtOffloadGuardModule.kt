package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.RootUtils
import java.util.concurrent.TimeUnit

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
 * It uses the Android property API first, then invokes resetprop directly
 * when the platform rejects a read-only property write.
 *
 * This loop is registered as a normal daemon entry, so it is gated by the
 * `bt_offload_guard_enable` switch.
 */
class BtOffloadGuardModule(ctx: DexContext) : DaemonLoop(ctx, 180_000L, pauseAware = false) {

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
    private val original = LinkedHashMap<String, String?>()

    override fun onStart() {
        Logger.i(name, "蓝牙音频 offload 循环守护已启动（内置 dex 循环，周期 ${intervalMs}ms）")
    }

    override fun tick() {
        var changed = 0
        for ((prop, value) in desired) {
            val current = readProp(prop)
            if (current == value) continue
            writeProp(prop, value)
            val after = readProp(prop)
            if (after == value) {
                original.putIfAbsent(prop, current)
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
        for ((prop, old) in original) {
            if (readProp(prop) == desired.firstOrNull { it.first == prop }?.second) {
                if (old.isNullOrEmpty()) directResetprop("-p", "-d", prop)
                else writeProp(prop, old)
            }
        }
        original.clear()
        lastApplied.clear()
        Logger.i(name, "蓝牙音频 offload 循环守护已停止")
    }

    private fun readProp(name: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, name) as String).ifEmpty { null }
    }.getOrNull()

    private fun writeProp(name: String, value: String) {
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("set", String::class.java, String::class.java).invoke(null, name, value)
        }
        if (readProp(name) != value) directResetprop("-p", "-n", name, value)
    }

    private fun directResetprop(vararg args: String): Boolean = runCatching {
        val process = ProcessBuilder(RootUtils.resetpropBinary(), *args)
            .redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else process.exitValue() == 0
    }.getOrDefault(false)
}
