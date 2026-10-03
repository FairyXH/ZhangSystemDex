package io.github.fairyxh.zhangsystemdex.core.power

import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File

/**
 * Conservative, reversible kernel/CPU power adjustments for the screen-off and
 * low-battery levels.
 *
 * Hard constraints from the long-task brief:
 *  - never permanently lock a frequency (we only lower the *max*, and restore);
 *  - never offline a core;
 *  - never disable thermal;
 *  - never assume a sysfs node exists (each write is probed, failure is a
 *    no-op);
 *  - a failed write must never crash the daemon (all wrapped).
 *
 * Every change is recorded in a small in-memory undo list so [revertAll]
 * restores the exact previous value. Values are only written when they
 * actually differ from the current content (no duplicate writes).
 */
class KernelPowerManager(private val stats: PowerStatistics) {

    private class Undo(val path: String, val old: String)

    private val undo = ArrayList<Undo>()

    @Volatile
    private var applied = false

    /** True when this kernel exposes any tunable we can touch at all. */
    private val cpuDir = File("/sys/devices/system/cpu")

    private fun cpuDirs(): List<File> =
        cpuDir.listFiles { f: File -> f.isDirectory && Regex("^cpu\\d+$").matches(f.name) }?.toList() ?: emptyList()

    /**
     * Apply a conservative screen-off profile: cap the max frequency at a
     * fraction of the available maximum. Does NOT pin the min frequency, does
     * NOT change the governor, does NOT touch online state.
     *
     * @param capPercent portion of the highest available frequency to keep
     *        (e.g. 70 for 70%). Ignored when out of (0,100].
     */
    fun applyScreenOffProfile(capPercent: Int = 70) {
        if (capPercent !in 1..100) return
        try {
            var writes = 0
            for (core in cpuDirs()) {
                val availPath = File(core, "cpufreq/scaling_available_frequencies").path
                val maxPath = File(core, "cpufreq/scaling_max_freq").path
                val avail = ProcessUtils.readFile(availPath)?.split(' ')
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val cur = ProcessUtils.readFile(maxPath) ?: continue
                val highest = avail.max()
                val target = (highest * capPercent / 100).coerceAtLeast(avail.min())
                // Do not raise a frequency that is already capped lower.
                if (cur.trim().toLongOrNull()?.let { it <= target } == true) continue
                if (writeAndRecord(maxPath, target.toString())) writes++
            }
            if (writes > 0) {
                applied = true
                stats.noteKernelWrite()
                Logger.i("KernelPowerManager", "息屏降频已应用（max=${capPercent}%，${writes} 个核心）")
            }
        } catch (t: Throwable) {
            stats.noteFailOpen("kernel screen-off profile")
            Logger.w("KernelPowerManager", "息屏降频失败（fail-open）: ${t.message}")
        }
    }

    /**
     * Optional: relax the interactive/boost knobs only if they already exist.
     * These are Qualcomm/MediaTek generic names; absence is expected and fine.
     */
    fun applyScreenOffBoosts(down: Boolean) {
        for (p in BOOST_PATHS) {
            try {
                if (!File(p).exists()) continue
                val cur = ProcessUtils.readFile(p) ?: continue
                val target = if (down) "0" else null
                if (target == null) continue
                if (cur.trim() == target) continue
                if (writeAndRecord(p, target)) stats.noteKernelWrite()
            } catch (t: Throwable) {
                Logger.w("KernelPowerManager", "调整 $p 失败: ${t.message}")
            }
        }
    }

    private fun writeAndRecord(path: String, value: String): Boolean {
        val old = ProcessUtils.readFile(path)
        if (!ProcessUtils.writeFile(path, value)) {
            Logger.w("KernelPowerManager", "写入被拒（节点可能不存在或 SELinux 受限）: $path")
            return false
        }
        if (old != null) undo.add(Undo(path, old))
        return true
    }

    /** Restore every value this manager changed, newest first. */
    fun revertAll() {
        if (!applied && undo.isEmpty()) return
        var restored = 0
        for (i in undo.indices.reversed()) {
            val u = undo[i]
            try {
                if (ProcessUtils.writeFile(u.path, u.old)) restored++
            } catch (_: Throwable) {
            }
        }
        undo.clear()
        applied = false
        if (restored > 0) {
            stats.notePolicyReverted("内核参数已还原（${restored} 项）")
            Logger.i("KernelPowerManager", "内核参数已还原（${restored} 项）")
        }
    }

    fun isApplied(): Boolean = applied || undo.isNotEmpty()

    companion object {
        /** Generic boost/input knobs; probed before use, never assumed. */
        private val BOOST_PATHS = listOf(
            "/sys/module/cpu_boost/parameters/input_boost_enabled",
            "/sys/module/cpu_boost/parameters/input_boost_freq",
            "/sys/kernel/msm_performance/parameters/touchboost",
            "/proc/sys/kernel/sched_boost"
        )
    }
}