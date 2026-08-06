package io.github.fairyxh.zhangsystemdex.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Installed-package enumeration. Prefers the system PackageManager obtained
 * from SystemContext; falls back to `pm list packages` shell output.
 */
object AppListProvider {
    fun allPackages(): List<String> {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                return ctx.packageManager.getInstalledApplications(0).map { it.packageName }
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "PackageManager enumeration failed: ${t.message}")
            }
        }
        return shellPackages("pm list packages")
    }

    fun thirdPartyPackages(): List<String> {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                return ctx.packageManager.getInstalledApplications(0)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { it.packageName }
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "PackageManager third-party enumeration failed: ${t.message}")
            }
        }
        return shellPackages("pm list packages -3")
    }

    fun systemPackages(): List<String> {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                return ctx.packageManager.getInstalledApplications(0)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                    .map { it.packageName }
            } catch (_: Throwable) {
            }
        }
        return shellPackages("pm list packages -s")
    }

    fun installed(pkg: String): Boolean = allPackages().contains(pkg)

    fun sourceDir(pkg: String): String? {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                return ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
            } catch (_: Throwable) {
            }
        }
        return ShellExecutor.run("pm path $pkg")
            ?.lineSequence()
            ?.firstOrNull()
            ?.removePrefix("package:")
    }

    fun appInfo(pkg: String): ApplicationInfo? {
        val ctx = SystemContext.get() ?: return null
        return try {
            ctx.packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        } catch (_: Throwable) {
            null
        }
    }

    fun label(pkg: String): String? {
        val ctx = SystemContext.get() ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            null
        }
    }

    fun enabledSetting(pkg: String): Int? {
        val ctx = SystemContext.get() ?: return null
        return try {
            ctx.packageManager.getApplicationEnabledSetting(pkg)
        } catch (_: Throwable) {
            null
        }
    }

    fun inputMethods(): List<String> {
        val out = ShellExecutor.run("ime list -s") ?: return emptyList()
        return out.lineSequence().map { it.trim().substringBefore('/') }.filter { it.isNotEmpty() }.toList()
    }

    private fun shellPackages(cmd: String): List<String> {
        val out = ShellExecutor.run(cmd) ?: return emptyList()
        return out.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
