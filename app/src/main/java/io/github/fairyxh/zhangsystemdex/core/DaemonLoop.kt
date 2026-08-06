package io.github.fairyxh.zhangsystemdex.core

/**
 * Base class for every long-running module loop. Each module runs on its own
 * thread with independent exception isolation: one module crash can never kill
 * the daemon. Game pause is honored automatically when pauseAware is true.
 */
abstract class DaemonLoop(
    protected val ctx: DexContext,
    protected val intervalMs: Long,
    private val pauseAware: Boolean = true,
) : Runnable {
    protected open val name: String get() = this::class.java.simpleName

    private val running = java.util.concurrent.atomic.AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        thread = Thread(this, name).also { it.isDaemon = false; it.start() }
    }

    fun stop() {
        running.set(false)
    }

    final override fun run() {
        Logger.i(name, "feature enabled, module loop started (interval=${intervalMs}ms, pauseAware=$pauseAware)")
        try {
            onStart()
        } catch (t: Throwable) {
            Logger.e(name, "onStart failed", t)
        }
        var pausedLogged = false
        while (running.get()) {
            try {
                if (pauseAware && ctx.gamePause.isPaused()) {
                    if (!pausedLogged) {
                        Logger.i(name, "game in foreground, loop paused")
                        pausedLogged = true
                    }
                    sleepSafe(10000)
                    continue
                }
                pausedLogged = false
                tick()
            } catch (t: Throwable) {
                Logger.e(name, "tick failed", t)
                sleepSafe(10000)
            }
            if (running.get()) sleepLoop()
        }
        try {
            onStop()
        } catch (t: Throwable) {
            Logger.e(name, "onStop failed", t)
        }
        Logger.i(name, "module stopped")
    }

    private fun sleepLoop() {
        var slept = 0L
        while (running.get() && slept < intervalMs) {
            val step = minOf(1000L, intervalMs - slept)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
            }
            slept += step
        }
    }

    protected fun sleepSafe(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    protected open fun onStart() {}

    protected open fun onStop() {}

    protected abstract fun tick()
}
