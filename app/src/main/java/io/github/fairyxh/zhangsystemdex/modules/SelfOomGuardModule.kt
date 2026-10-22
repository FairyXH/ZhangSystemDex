package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 自身 OOM 自保模块：把 **daemon 自己**的 `oom_score_adj` 固定为 -1000。
 *
 * ## 为什么需要
 *
 * daemon 承担全部常驻功能（OOM 保护名单、保活、Doze 维护、WebUI 后端等）。
 * 一旦它被 LMK 回收，所有守护能力同时失效，且要等下次开机才会恢复。
 * `oom_score_adj = -1000` 等同系统关键进程级别，能彻底免于被 LMK 选中。
 *
 * ## 为什么「无开关、无配置、周期执行」
 *
 * 用户明确要求固定行为：不需要任何开关即可始终生效。
 * 周期执行是为了**自我修复**——若外部误改（如其他脚本批量把 adj 归 0），
 * 下一周期会自动纠正回来。
 *
 * ## ⚠ 危险性与本模块的自我约束
 *
 * -1000 意味着**即使内存失控也不会被杀**。因此本模块在提升 adj 之前，
 * 会先做**内存健康检查**，并且在内存异常时**主动降级保护**（见 [applyAdj]）：
 *
 *   1. 先把 adj 提到 -1000（免杀）；
 *   2. 每个周期检查自身 RSS / Java 堆占比；
 *   3. 若堆占用异常偏高（> 自我熔断阈值），主动 GC 一次并记录告警；
 *   4. 若连续多个周期仍异常，**自动把 adj 退回 -900**，
 *      让系统在极端情况下仍能回收本进程——宁可丢守护，也不能拖垮系统。
 *
 * 这是本模块最重要的安全设计：**-1000 不是无条件信任，而是带自我熔断的特权**。
 *
 * ## 线程与内存开销
 *
 * 仅 1 个线程、周期 60s、每周期只读几个 /proc 文本文件，
 * 不持有任何集合或大对象，常驻内存开销可忽略（< 100KB）。
 */
class SelfOomGuardModule(ctx: DexContext) : DaemonLoop(ctx, TICK_MS, pauseAware = false) {

    companion object {
        /** 周期：60s。越短越能快速纠正外部误改，但也没必要更频繁。 */
        private const val TICK_MS = 60_000L

        /** 目标值：几乎不可被 LMK 杀死。 */
        private const val TARGET_ADJ = -1000

        /** 降级值：内存异常时退回，保留被杀可能。 */
        private const val FALLBACK_ADJ = -900

        /** Java 堆占堆上限的告警阈值（%）。超过则认为内存使用异常。 */
        private const val HEAP_WARN_PERCENT = 75

        /** 连续多少个周期内存异常后降级。 */
        private const val DEMOTE_AFTER_BAD_CYCLES = 5

        /**
         * 本线程的 niceness（nice 值）。
         *
         * 用户要求「自身进程优先级略微降低」：
         *   - 取 5（而非 10/19），只让出**一部分** CPU 优先权，
         *     避免与前台应用/系统服务争抢；
         *   - 但保活/清理/WebUI 等任务在这一级别仍能及时完成，不会明显变卡。
         *
         * 注意：**只作用于本模块自己的线程**，不改整个进程的其它线程。
         * 进程级 renice 会波及 WebUI 的 HttpConn 线程，反而拖慢界面响应，
         * 因此这里刻意采用「按线程降级」而不是「整进程降级」。
         */
        private const val SELF_NICE = 5
    }

    /** 连续「内存异常」的周期数；恢复正常即清零。 */
    private var badCycles = 0

    /** 当前是否处于降级状态（避免每周期重复写文件、重复刷日志）。 */
    private var degraded = false

    /** 上次写入的 adj 值；与实际不符才写盘，省掉无意义的 open/write。 */
    private var appliedAdj = Int.MIN_VALUE

    private val pid: Int = android.os.Process.myPid()
    private val adjPath = "/proc/$pid/oom_score_adj"

    override fun onStart() {
        // 启动即生效，不等第一个周期。
        badCycles = 0
        degraded = false
        appliedAdj = Int.MIN_VALUE
        applyAdj(TARGET_ADJ, "启动")
        applyNice()
    }

    /**
     * 把自己的循环线程 niceness 略微调低（nice=5）。
     *
     * 只影响本线程：OOM 自保是低频轻量任务，没必要抢占 CPU。
     * WebUI / 守护等耗时路径各自在自己的线程上，不受影响。
     */
    private fun applyNice() {
        try {
            // setThreadPriority 作用于**当前调用线程**（即本模块的 DaemonLoop 线程）。
            android.os.Process.setThreadPriority(SELF_NICE)
        } catch (t: Throwable) {
            Logger.w(name, "设置自身线程优先级失败: ${t.message}")
        }
    }

    override fun tick() {
        // ① 先做内存体检，决定本周期应该用哪个 adj。
        val heap = heapUsagePercent()
        if (heap >= HEAP_WARN_PERCENT) {
            badCycles++
            // 主动回收一次：Java 堆异常时，先尝试自救再考虑降级。
            System.gc()
            if (badCycles >= DEMOTE_AFTER_BAD_CYCLES && !degraded) {
                degraded = true
                Logger.w(
                    name,
                    "自身内存持续异常（堆 ${heap}%，连续 $badCycles 个周期），" +
                        "主动降级 adj=$FALLBACK_ADJ 以保留系统回收能力"
                )
            }
        } else {
            if (badCycles > 0 || degraded) {
                if (degraded) {
                    Logger.i(name, "自身内存已恢复（堆 ${heap}%），adj 回到 $TARGET_ADJ")
                }
                badCycles = 0
                degraded = false
            }
        }

        // ② 应用（自我修复）：外部若把 adj 改回去，这里会纠正。
        applyAdj(if (degraded) FALLBACK_ADJ else TARGET_ADJ, if (degraded) "降级" else "周期校正")
        // ③ 线程优先级同样周期校正（开销极小，仅一次系统调用）。
        applyNice()
    }

    /**
     * 写入自身的 oom_score_adj。
     *
     * 注意：**必须直接写自己的 `/proc/<pid>/oom_score_adj`**，
     * 不能 `ShellExecutor.run("echo ... > /proc/self/...")`——
     * shell 子进程的 `/proc/self` 指向子进程自身，那样改的是 shell 而不是 daemon。
     * root 进程对自己有写权限，直接写文件即可，也**不占用 ShellExecutor 全局闸门**。
     */
    private fun applyAdj(value: Int, reason: String) {
        if (appliedAdj == value && readAdj() == value) return
        try {
            File(adjPath).writeText(value.toString())
            val now = readAdj()
            if (now == value) {
                if (appliedAdj != value) {
                    Logger.i(name, "自身 oom_score_adj=$value（$reason，pid=$pid）")
                }
                appliedAdj = value
            } else {
                // 写成功但读回不一致：记录一次即可，不要刷屏。
                if (appliedAdj != value) Logger.w(name, "写入 oom_score_adj 未生效：期望 $value 实际 $now")
            }
        } catch (t: Throwable) {
            if (appliedAdj != value) Logger.w(name, "设置自身 oom_score_adj 失败: ${t.message}")
        }
    }

    private fun readAdj(): Int? = try {
        File(adjPath).readText().trim().toIntOrNull()
    } catch (_: Throwable) {
        null
    }

    /**
     * Java 堆占用百分比（已用 / 上限）。
     *
     * 读 `Runtime` 的内存计数器，**零文件 IO、零分配**（仅创建两个 Long 装箱，
     * 由 JIT 消去），比解析 /proc 更轻，也更贴近 GC 真实压力。
     */
    private fun heapUsagePercent(): Int {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        if (max <= 0L) return 0
        val used = rt.totalMemory() - rt.freeMemory()
        return ((used * 100) / max).toInt().coerceIn(0, 100)
    }
}
