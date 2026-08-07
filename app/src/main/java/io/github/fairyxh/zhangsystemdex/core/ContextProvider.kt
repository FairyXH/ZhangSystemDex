package io.github.fairyxh.zhangsystemdex.core

import android.content.Context

/**
 * Provides Android system Context for app_process based runtime.
 *
 * This class does not cache Context intentionally:
 * - app_process is not a normal Application lifecycle
 * - avoid static Context reference warnings
 * - always fetch current system context when needed
 */
object ContextProvider {

    /**
     * Get Android system Context.
     *
     * @return Context or null when ActivityThread is not ready
     */
    fun get(): Context? {
        return try {
            getSystemContext()
        } catch (t: Throwable) {
            Logger.w(
                "ContextProvider",
                "获取Context失败: ${t.message}"
            )
            null
        }
    }


    private fun getSystemContext(): Context? {

        val activityThreadClass =
            Class.forName(
                "android.app.ActivityThread"
            )


        /*
         * app_process启动时:
         *
         * ActivityThread.currentApplication()
         *
         * 可能为空，因此优先使用:
         *
         * ActivityThread.currentActivityThread()
         *          |
         *          +-- getSystemContext()
         *
         */


        val currentActivityThread =
            activityThreadClass
                .getMethod(
                    "currentActivityThread"
                )
                .invoke(null)


        if (currentActivityThread == null) {
            Logger.w(
                "ContextProvider",
                "ActivityThread未初始化"
            )
            return null
        }


        val systemContext =
            activityThreadClass
                .getMethod(
                    "getSystemContext"
                )
                .invoke(
                    currentActivityThread
                )


        if (systemContext is Context) {

            Logger.i(
                "ContextProvider",
                "获取SystemContext成功"
            )

            return systemContext
        }


        Logger.w(
            "ContextProvider",
            "SystemContext类型错误: ${systemContext?.javaClass}"
        )

        return null
    }
}