package io.github.fairyxh.zhangsystemdex.core.power

import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, in-memory statistics for the power subsystem. All counters are
 * lock-free atomics so the event-driven monitor and the policy engine can write
 * from different threads without contention. Nothing here touches the disk on
 * the hot path; the WebUI reads a snapshot through [snapshot].
 *
 * The statistics object is intentionally tiny and allocation-free on updates;
 * it exists so the WebUI can show "what did the subsystem actually do",
 * which is a hard requirement of the long-task brief (observability).
 */
class PowerStatistics {

    /** Timestamp of the last observed state transition (screen on/off, level change). */
    @Volatile
    var lastTransitionMs: Long = 0L

    /** Last policy level that was actually applied (0=idle … see PowerPolicyEngine). */
    @Volatile
    var currentLevel: Int = 0

    /** Human readable last-action summary, shown verbatim in the WebUI. */
    @Volatile
    var lastAction: String = "未执行"

    private val screenOffCount = AtomicLong(0)
    private val screenOnCount = AtomicLong(0)
    private val policyAppliedCount = AtomicLong(0)
    private val policyRevertCount = AtomicLong(0)
    private val backgroundRestrictCount = AtomicLong(0)
    private val kernelWriteCount = AtomicLong(0)
    private val lowBatteryEnterCount = AtomicLong(0)
    private val failOpenCount = AtomicLong(0)

    fun noteScreenOff() {
        screenOffCount.incrementAndGet()
        lastTransitionMs = System.currentTimeMillis()
    }

    fun noteScreenOn() {
        screenOnCount.incrementAndGet()
        lastTransitionMs = System.currentTimeMillis()
    }

    fun notePolicyApplied(level: Int, action: String) {
        currentLevel = level
        policyAppliedCount.incrementAndGet()
        lastAction = action
        lastTransitionMs = System.currentTimeMillis()
    }

    fun notePolicyReverted(action: String) {
        policyRevertCount.incrementAndGet()
        lastAction = action
        lastTransitionMs = System.currentTimeMillis()
    }

    fun noteBackgroundRestrict() {
        backgroundRestrictCount.incrementAndGet()
    }

    fun noteKernelWrite() {
        kernelWriteCount.incrementAndGet()
    }

    fun noteLowBatteryEnter() {
        lowBatteryEnterCount.incrementAndGet()
    }

    fun noteFailOpen(reason: String) {
        failOpenCount.incrementAndGet()
        lastAction = "fail-open: $reason"
    }

    /** Immutable snapshot used by the WebUI / log summary. */
    fun snapshot(): Map<String, Any> = linkedMapOf(
        "level" to currentLevel,
        "lastAction" to lastAction,
        "lastTransitionMs" to lastTransitionMs,
        "screenOffCount" to screenOffCount.get(),
        "screenOnCount" to screenOnCount.get(),
        "policyAppliedCount" to policyAppliedCount.get(),
        "policyRevertCount" to policyRevertCount.get(),
        "backgroundRestrictCount" to backgroundRestrictCount.get(),
        "kernelWriteCount" to kernelWriteCount.get(),
        "lowBatteryEnterCount" to lowBatteryEnterCount.get(),
        "failOpenCount" to failOpenCount.get()
    )

    fun summary(): String =
        "等级=$currentLevel 应用=${policyAppliedCount.get()} 撤销=${policyRevertCount.get()} " +
            "后台限制=${backgroundRestrictCount.get()} 内核写入=${kernelWriteCount.get()} " +
            "低电量进入=${lowBatteryEnterCount.get()} fail-open=${failOpenCount.get()}"
}
