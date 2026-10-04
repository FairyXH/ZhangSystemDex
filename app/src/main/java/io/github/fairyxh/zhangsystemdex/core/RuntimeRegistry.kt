package io.github.fairyxh.zhangsystemdex.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Live runtime state shared between the daemon's module loops and the WebUI
 * HTTP backend (which runs on its own threads inside the same process).
 *
 * Why a global registry instead of a per-module singleton: the overview page
 * needs to tell, at a glance and in real time, WHICH modules are actually
 * running (not merely which switches are on), WHEN each of them last did
 * something, and HOW MUCH work they have done. Threads are the ground truth
 * for "running", but they can't report counters; the module loops can.
 *
 * Design notes:
 *  - All access is lock-free (ConcurrentHashMap); counter writes are cheap
 *    enough to call from hot loops.
 *  - The registry never imports any module class (avoids cycles); modules push
 *    state into it, the backend reads it out.
 */
object RuntimeRegistry {

    /** One module's live status snapshot. */
    class ModuleState(
        @Volatile var label: String,
        @Volatile var desc: String,
        @Volatile var enabled: Boolean = false,
        @Volatile var running: Boolean = false,
    ) {
        @Volatile var lastTickMs: Long = 0L
        @Volatile var lastAction: String = ""
        @Volatile var tickCount: Long = 0L
        /** Named work counters a module may increment (e.g. "cleaned", "killed"). */
        val counters: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        /** Arbitrary key/value extras a module may publish (numbers/bools/strings). */
        val extras: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

        fun bump(key: String, delta: Long = 1L) {
            counters.merge(key, delta) { a, b -> a + b }
        }
    }

    private val states = ConcurrentHashMap<String, ModuleState>()

    /** Daemon process start time (ms), set once by Main. */
    @Volatile
    var daemonStartedMs: Long = 0L

    /** Config root dir (for display). */
    @Volatile
    var configRoot: String = ""

    /** Module install dir (for display). */
    @Volatile
    var moduleDir: String = ""

    /** Register/refresh a module entry. Preserves counters across re-registers. */
    fun register(key: String, label: String, desc: String): ModuleState {
        val st = states.computeIfAbsent(key) { ModuleState(label, desc) }
        st.label = label
        st.desc = desc
        return st
    }

    fun setEnabled(key: String, enabled: Boolean) {
        states[key]?.enabled = enabled
    }

    fun setRunning(key: String, running: Boolean) {
        val st = states[key] ?: return
        st.running = running
        if (running) st.lastTickMs = System.currentTimeMillis()
    }

    /** Called by a module at the end of each successful tick. */
    fun markTick(key: String, action: String = "") {
        val st = states[key] ?: return
        st.lastTickMs = System.currentTimeMillis()
        st.tickCount++
        if (action.isNotEmpty()) st.lastAction = action
    }

    fun bump(key: String, counter: String, delta: Long = 1L) {
        states[key]?.bump(counter, delta)
    }

    fun put(key: String, field: String, value: Any) {
        states[key]?.extras?.put(field, value)
    }

    fun snapshot(): List<Pair<String, ModuleState>> =
        states.entries.sortedBy { it.key }.map { it.key to it.value }

    fun get(key: String): ModuleState? = states[key]
}