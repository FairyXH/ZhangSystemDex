package io.github.fairyxh.zhangsystemdex.core

import android.content.ContentResolver
import android.provider.Settings

/**
 * Settings database access. Prefers the system ContentResolver obtained from
 * SystemContext, falls back to the `settings` shell command.
 *
 * The app_process systemMain Context has a working PackageManager but its
 * ContentResolver cannot reach the settings provider ("Unable to find app for
 * caller") because the thread is not attached to ActivityManager. The first
 * failure is logged once, then every later call goes straight to shell so a
 * per-cycle loop does not spam WARN lines.
 */
object SettingsUtils {
    @Volatile
    private var frameworkBroken = false

    private fun resolver(): ContentResolver? {
        if (frameworkBroken) return null
        return SystemContext.get()?.contentResolver
    }

    private fun frameworkFailed(op: String, t: Throwable) {
        if (!frameworkBroken) {
            frameworkBroken = true
            Logger.w("SettingsUtils", "$op 失败，降级 shell（仅记录一次）: ${t.message}")
        }
    }

    fun putGlobal(key: String, value: String) {
        val cr = resolver()
        if (cr != null) {
            try {
                Settings.Global.putString(cr, key, value)
                return
            } catch (t: Throwable) {
                frameworkFailed("ContentResolver global put", t)
            }
        }
        ShellExecutor.run("settings put global $key $value")
    }

    fun putSystem(key: String, value: String) {
        val cr = resolver()
        if (cr != null) {
            try {
                Settings.System.putString(cr, key, value)
                return
            } catch (t: Throwable) {
                frameworkFailed("ContentResolver system put", t)
            }
        }
        ShellExecutor.run("settings put system $key $value")
    }

    fun putSecure(key: String, value: String) {
        val cr = resolver()
        if (cr != null) {
            try {
                Settings.Secure.putString(cr, key, value)
                return
            } catch (t: Throwable) {
                frameworkFailed("ContentResolver secure put", t)
            }
        }
        ShellExecutor.run("settings put secure $key $value")
    }

    fun getSecure(key: String): String? {
        val cr = resolver()
        if (cr != null) {
            try {
                return Settings.Secure.getString(cr, key)
            } catch (t: Throwable) {
                frameworkFailed("ContentResolver secure get", t)
            }
        }
        return ShellExecutor.run("settings get secure $key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun getGlobal(key: String): String? {
        val cr = resolver()
        if (cr != null) {
            try {
                return Settings.Global.getString(cr, key)
            } catch (t: Throwable) {
                frameworkFailed("ContentResolver global get", t)
            }
        }
        return ShellExecutor.run("settings get global $key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
