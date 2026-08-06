package io.github.fairyxh.zhangsystemdex.core

import android.content.Context

/**
 * Best-effort bootstrap of a system Context inside an app_process daemon.
 *
 * Reflection on ActivityThread avoids linking hidden APIs at compile time.
 * When unavailable (early boot or vendor restrictions) callers fall back to
 * shell commands through ShellExecutor.
 */
object SystemContext {
    @Volatile
    private var ctx: Context? = null

    fun get(): Context? {
        ctx?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getMethod("currentActivityThread").invoke(null)
            val instance = current ?: at.getDeclaredConstructor().newInstance()
            val create = at.getDeclaredMethod("createSystemContext")
            create.isAccessible = true
            val created = create.invoke(instance) as Context
            ctx = created
            created
        } catch (t: Throwable) {
            null
        }
    }

    fun reset() {
        ctx = null
    }
}
