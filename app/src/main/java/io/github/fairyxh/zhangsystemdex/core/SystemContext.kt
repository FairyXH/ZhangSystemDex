package io.github.fairyxh.zhangsystemdex.core

import android.content.Context
import android.os.Looper

/**
 * Best-effort bootstrap of a system Context inside an app_process daemon.
 *
 * Path order (each step is wrapped in try/catch, failures never crash):
 *  1. currentActivityThread().getSystemContext() — zero side effect, normally
 *     null inside app_process.
 *  2. new ActivityThread() + createSystemContext() — on Android 15 the method
 *     is hidden-API filtered (fake NoSuchMethodException) even after
 *     VMRuntime.setHiddenApiExemptions(["L"]), so this usually fails.
 *  3. Looper.prepareMainLooper() + ActivityThread.systemMain() +
 *     getSystemContext() — systemMain/getSystemContext are greylist-visible;
 *     ActivityThread's constructor needs a main-thread Looper (the old code
 *     called systemMain without one and always died with
 *     "Can't create handler ... Looper.prepare()").
 *
 * A failed attempt is cached for 10 minutes so a 10s module loop does not
 * re-run ActivityThread/systemMain every tick (the old code printed two WARN
 * lines every 10 seconds and executed systemMain repeatedly).
 */
object SystemContext {
    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var lastAttemptMs = 0L

    @Volatile
    private var lastFailed = false

    private const val RETRY_INTERVAL_MS = 10 * 60 * 1000L

    fun get(): Context? {
        ctx?.let { return it }
        val now = System.currentTimeMillis()
        if (lastFailed && now - lastAttemptMs < RETRY_INTERVAL_MS) return null
        lastAttemptMs = now
        return tryBuild()
    }

    /** Force a fresh attempt (used by the debug menu), ignoring the retry cache. */
    fun getForced(): Context? {
        ctx?.let { return it }
        return tryBuild()
    }

    private fun tryBuild(): Context? {
        // Path 1: an existing ActivityThread (normally null under app_process).
        try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread")
            current.isAccessible = true
            val instance = current.invoke(null)
            if (instance != null) {
                val gsc = at.getDeclaredMethod("getSystemContext")
                gsc.isAccessible = true
                val created = gsc.invoke(instance) as Context
                return succeed(created, "currentActivityThread.getSystemContext ok")
            }
        } catch (t: Throwable) {
            Logger.w("SystemContext", "currentActivityThread 路径失败: ${t.message}")
        }

        // Path 2: fresh ActivityThread + createSystemContext (hidden-API filtered on Android 15).
        try {
            val at = Class.forName("android.app.ActivityThread")
            val create = at.getDeclaredMethod("createSystemContext")
            create.isAccessible = true
            val instance = at.getDeclaredConstructor().newInstance()
            val created = create.invoke(instance) as Context
            return succeed(created, "createSystemContext ok")
        } catch (t: Throwable) {
            Logger.w("SystemContext", "createSystemContext 不可用: ${t.message}")
        }

        // Path 3: ActivityThread.systemMain() needs a main-thread Looper; the
        // method itself is greylist-visible so it bypasses the reflection
        // filter while creating mSystemContext internally.
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val systemMain = at.getDeclaredMethod("systemMain")
            systemMain.isAccessible = true
            val instance = systemMain.invoke(null)
            val gsc = at.getDeclaredMethod("getSystemContext")
            gsc.isAccessible = true
            val created = gsc.invoke(instance) as Context
            return succeed(created, "systemMain fallback ok")
        } catch (t: Throwable) {
            Logger.e("SystemContext", "systemMain 回退失败", t)
        }

        lastFailed = true
        Logger.w("SystemContext", "系统上下文不可用，已启用 shell 回退")
        return null
    }

    private fun succeed(created: Context, log: String): Context {
        ctx = created
        lastFailed = false
        Logger.i("SystemContext", log)
        return created
    }

    fun reset() {
        ctx = null
        lastFailed = false
    }
}
