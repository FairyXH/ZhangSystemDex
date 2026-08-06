package io.github.fairyxh.zhangsystemdex.core

import android.content.Context

/**
 * Best-effort bootstrap of a system Context inside an app_process daemon.
 *
 * Primary path reflects ActivityThread.createSystemContext; fallback path uses
 * ActivityThread.systemMain() + getSystemContext() (heavier but works on more
 * Android versions). Failures are logged, never silent, so scanning can decide
 * whether to use PackageManager metadata or LSPosed database fallbacks.
 */
object SystemContext {
    @Volatile
    private var ctx: Context? = null

    fun get(): Context? {
        ctx?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val create = at.getDeclaredMethod("createSystemContext")
            create.isAccessible = true
            val instance = at.getDeclaredConstructor().newInstance()
            val created = create.invoke(instance) as Context
            ctx = created
            Logger.i("SystemContext", "createSystemContext ok")
            created
        } catch (t: Throwable) {
            Logger.w("SystemContext", "createSystemContext failed: ${t.message}")
            try {
                val at = Class.forName("android.app.ActivityThread")
                val systemMain = at.getMethod("systemMain").invoke(null)
                val getSysCtx = at.getMethod("getSystemContext")
                val created = getSysCtx.invoke(systemMain) as Context
                ctx = created
                Logger.i("SystemContext", "systemMain fallback ok")
                created
            } catch (t2: Throwable) {
                Logger.w("SystemContext", "systemMain fallback failed: ${t2.message}")
                null
            }
        }
    }

    fun reset() {
        ctx = null
    }
}
