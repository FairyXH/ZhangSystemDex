package io.github.fairyxh.zhangsystemdex.core

import android.content.ContentResolver
import android.provider.Settings

/**
 * Settings database access. Prefers the system ContentResolver obtained from
 * SystemContext, falls back to the `settings` shell command.
 */
object SettingsUtils {
    private fun resolver(): ContentResolver? = SystemContext.get()?.contentResolver

    fun putGlobal(key: String, value: String) {
        val cr = resolver()
        if (cr != null) {
            try {
                Settings.Global.putString(cr, key, value)
                return
            } catch (t: Throwable) {
                Logger.w("SettingsUtils", "ContentResolver global put failed, fallback shell: ${t.message}")
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
                Logger.w("SettingsUtils", "ContentResolver system put failed, fallback shell: ${t.message}")
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
                Logger.w("SettingsUtils", "ContentResolver secure put failed, fallback shell: ${t.message}")
            }
        }
        ShellExecutor.run("settings put secure $key $value")
    }

    fun getSecure(key: String): String? {
        val cr = resolver()
        if (cr != null) {
            try {
                return Settings.Secure.getString(cr, key)
            } catch (_: Throwable) {
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
            } catch (_: Throwable) {
            }
        }
        return ShellExecutor.run("settings get global $key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }
}
