package io.github.fairyxh.zhangsystemdex.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global game-pause state. DaemonLoop instances check it before ticking so the
 * whole module pauses while a game is in the foreground (replacing the
 * pause_on_game flag duplicated in every shell script).
 */
class GamePauseCoordinator {
    private val paused = AtomicBoolean(false)

    @Volatile
    private var lastState: Boolean? = null

    fun setPaused(value: Boolean) {
        val prev = paused.getAndSet(value)
        if (prev != value) {
            lastState = value
        }
    }

    fun isPaused(): Boolean = paused.get()

    fun stateChanged(): Boolean {
        val cur = paused.get()
        val prev = lastState
        if (prev == null || prev != cur) {
            lastState = cur
            return true
        }
        return false
    }
}
