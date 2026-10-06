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
                Logger.w("AppListProvider", "PackageManager 枚举失败: ${t.message}")
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
                Logger.w("AppListProvider", "PackageManager 第三方应用枚举失败: ${t.message}")
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

    fun packageNameFromApk(path: String): String? {
        val pm = SystemContext.get()?.packageManager
        if (pm != null) {
            try {
                return pm.getPackageArchiveInfo(path, 0)?.packageName
                    ?.takeIf { it.matches(PACKAGE_NAME_PATTERN) }
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "Framework APK 解析失败: ${t.message}")
            }
        }
        return null
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
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                if (imm != null) {
                    return imm.inputMethodList.map { it.packageName }.distinct()
                }
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "InputMethodManager 枚举失败: ${t.message}")
            }
        }
        val out = ShellExecutor.run("ime list -s") ?: return emptyList()
        return out.lineSequence().map { it.trim().substringBefore('/') }.filter { it.isNotEmpty() }.toList()
    }

    /**
     * 枚举「声明了通知监听服务（NotificationListenerService）」的应用。
     *
     * 优先用 PackageManager.queryIntentServices（解析 manifest 中的
     * `android.service.notification.NotificationListenerService` action），
     * 失败时回退到 shell（dumpsys 遍历成本高，故仅在必要时使用）。
     */
    fun notificationListenerApps(): List<String> {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                val intent = android.content.Intent(
                    "android.service.notification.NotificationListenerService"
                )
                val pm = ctx.packageManager
                val services = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentServices(
                        intent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentServices(intent, 0)
                }
                return services.mapNotNull { it.serviceInfo?.packageName }.distinct().sorted()
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "枚举通知监听应用失败: ${t.message}")
            }
        }
        return emptyList()
    }

    /**
     * 枚举「声明了无障碍服务（AccessibilityService）」的应用。
     *
     * 优先用 PackageManager.queryIntentServices（
     * `android.accessibilityservice.AccessibilityService` action），
     * 失败时回退到 AccessibilityManager.getInstalledAccessibilityServiceList。
     */
    fun accessibilityApps(): List<String> {
        val ctx = SystemContext.get()
        if (ctx != null) {
            try {
                val intent = android.content.Intent(
                    "android.accessibilityservice.AccessibilityService"
                )
                val pm = ctx.packageManager
                val services = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentServices(
                        intent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentServices(intent, 0)
                }
                val fromPm = services.mapNotNull { it.serviceInfo?.packageName }.distinct()
                if (fromPm.isNotEmpty()) return fromPm.sorted()
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "枚举无障碍应用失败: ${t.message}")
            }
            try {
                val am = ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                        as? android.view.accessibility.AccessibilityManager
                if (am != null) {
                    return am.getInstalledAccessibilityServiceList()
                        .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                        .distinct().sorted()
                }
            } catch (t: Throwable) {
                Logger.w("AppListProvider", "AccessibilityManager 枚举失败: ${t.message}")
            }
        }
        return emptyList()
    }

    private val PACKAGE_NAME_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")

    private fun shellPackages(cmd: String): List<String> {
        val out = ShellExecutor.run(cmd) ?: return emptyList()
        return out.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
