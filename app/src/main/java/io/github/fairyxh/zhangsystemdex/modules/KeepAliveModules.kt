package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AccessibilityGrant
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.KeepAliveKind
import io.github.fairyxh.zhangsystemdex.core.KeepAliveList
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.NotificationGrant

/**
 * 通知使用权保活。
 *
 * 名单来源：[KeepAliveKind.NOTIFICATION]（`notif_keepalive.conf`，WebUI「保活」页维护）。
 * 周期检查名单内每个包的通知使用权是否仍在
 * `enabled_notification_listeners` 中；若掉线则自动重新授权
 * （组件路径优先复用名单里已记录的，否则现场探测）。
 *
 * 与 [AccessibilityKeepAliveModule] 对称，互不影响。
 */
class NotificationKeepAliveModule(ctx: DexContext) : DaemonLoop(ctx, 15_000L) {

    /** pkg -> 组件（学习到的），避免每次重新探测。 */
    private val learned = HashMap<String, String>()

    override fun onStart() {
        Logger.i(name, "配置: ${KeepAliveList.file(java.io.File(ctx.config.rootDir), KeepAliveKind.NOTIFICATION).path}")
    }

    override fun tick() {
        val pkgs = KeepAliveList.read(java.io.File(ctx.config.rootDir), KeepAliveKind.NOTIFICATION)
        if (pkgs.isEmpty()) return
        val enabled = NotificationGrant.listListeners()
        for (pkg in pkgs) {
            try {
                val found = enabled.firstOrNull { it.startsWith("$pkg/") }
                if (found != null) {
                    if (learned[pkg] != found) {
                        learned[pkg] = found
                        Logger.i(name, "已学习 $pkg 的监听组件 -> $found")
                    }
                    continue
                }
                // 掉线：重新授权
                val path = learned[pkg]
                if (path != null) {
                    val rc = io.github.fairyxh.zhangsystemdex.core.ShellExecutor
                        .runExit("cmd notification allow_listener '$path'", 20_000L)
                    if (rc == 0) {
                        Logger.i(name, "已恢复通知使用权 $path")
                        continue
                    }
                    Logger.w(name, "用已学习路径恢复失败($rc)，改为现场探测: $path")
                }
                if (NotificationGrant.grant(pkg, background = false).ok) {
                    NotificationGrant.listenersOf(pkg).firstOrNull()?.let { learned[pkg] = it }
                    Logger.i(name, "已重新启用通知使用权 $pkg")
                } else {
                    Logger.w(name, "$pkg 的通知使用权恢复失败（可能组件不存在或已被禁用）")
                }
            } catch (t: Throwable) {
                Logger.w(name, "保活 $pkg 失败: ${t.message}")
            }
        }
    }
}

/**
 * 无障碍服务保活。
 *
 * 名单来源：[KeepAliveKind.ACCESSIBILITY]（`a11y_keepalive.conf`，WebUI「保活」页维护）。
 * 周期检查名单内每个包的无障碍服务是否仍在
 * `enabled_accessibility_services` 中；若掉线则自动重新启用。
 *
 * 同时兼容旧的 `asguard.conf`（包名列表）以便平滑迁移。
 */
class AccessibilityKeepAliveModule(ctx: DexContext) : DaemonLoop(ctx, 15_000L) {

    private val learned = HashMap<String, String>()

    override fun onStart() {
        Logger.i(name, "配置: ${KeepAliveList.file(java.io.File(ctx.config.rootDir), KeepAliveKind.ACCESSIBILITY).path}")
    }

    override fun tick() {
        val rootDir = java.io.File(ctx.config.rootDir)
        val pkgs = LinkedHashSet<String>()
        pkgs.addAll(KeepAliveList.read(rootDir, KeepAliveKind.ACCESSIBILITY))
        // 兼容旧 asguard.conf
        try {
            val legacy = java.io.File(rootDir, "asguard.conf")
            if (legacy.exists()) {
                legacy.readLines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { if (io.github.fairyxh.zhangsystemdex.core.OomProtectList.isValidPackage(it)) pkgs.add(it) }
            }
        } catch (_: Throwable) {
        }
        if (pkgs.isEmpty()) return

        val enabled = AccessibilityGrant.listComponents()
        for (pkg in pkgs) {
            try {
                val found = enabled.firstOrNull { it.startsWith("$pkg/") }
                if (found != null) {
                    if (learned[pkg] != found) {
                        learned[pkg] = found
                        Logger.i(name, "已学习 $pkg 的无障碍组件 -> $found")
                    }
                    continue
                }
                if (AccessibilityGrant.enable(pkg)) {
                    AccessibilityGrant.servicesOf(pkg).firstOrNull()?.let { learned[pkg] = it }
                    Logger.i(name, "已重新启用无障碍服务 $pkg")
                } else {
                    Logger.w(name, "$pkg 的无障碍服务恢复失败（可能组件不存在）")
                }
            } catch (t: Throwable) {
                Logger.w(name, "保活 $pkg 失败: ${t.message}")
            }
        }
    }
}