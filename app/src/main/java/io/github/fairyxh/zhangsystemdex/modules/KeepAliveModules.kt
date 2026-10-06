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

    /** pkg -> 组件（学习到的，持久化到 notif_keepalive.paths）。 */
    private val learned = HashMap<String, String>()
    private var pathsLoaded = false

    private fun rootDir() = java.io.File(ctx.config.rootDir)

    override fun onStart() {
        val root = rootDir()
        Logger.i(name, "配置: ${KeepAliveList.file(root, KeepAliveKind.NOTIFICATION).path}")
        Logger.i(name, "路径表: ${KeepAliveList.pathsFile(root, KeepAliveKind.NOTIFICATION).path}")
        // 首次启动确保配置文件存在（默认保活 com.catchingnow.np）
        KeepAliveList.ensureFile(root, KeepAliveKind.NOTIFICATION)
    }

    private fun loadPaths() {
        if (pathsLoaded) return
        learned.putAll(KeepAliveList.readPaths(rootDir(), KeepAliveKind.NOTIFICATION))
        pathsLoaded = true
        if (learned.isNotEmpty()) Logger.i(name, "已加载 ${learned.size} 条组件路径")
    }

    override fun tick() {
        loadPaths()
        val root = rootDir()
        // 文件不存在时按内置默认（含 com.catchingnow.np）处理
        val pkgs = KeepAliveList.read(root, KeepAliveKind.NOTIFICATION)
        if (pkgs.isEmpty()) return
        val enabled = NotificationGrant.listListeners()
        for (pkg in pkgs) {
            try {
                val found = enabled.firstOrNull { it.startsWith("$pkg/") }
                if (found != null) {
                    if (learned[pkg] != found) {
                        learned[pkg] = found
                        KeepAliveList.savePath(root, KeepAliveKind.NOTIFICATION, pkg, found)
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
                    NotificationGrant.listenersOf(pkg).firstOrNull()?.let {
                        learned[pkg] = it
                        KeepAliveList.savePath(root, KeepAliveKind.NOTIFICATION, pkg, it)
                    }
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
    private var pathsLoaded = false

    private fun rootDir() = java.io.File(ctx.config.rootDir)

    override fun onStart() {
        val root = rootDir()
        Logger.i(name, "配置: ${KeepAliveList.file(root, KeepAliveKind.ACCESSIBILITY).path}")
        Logger.i(name, "路径表: ${KeepAliveList.pathsFile(root, KeepAliveKind.ACCESSIBILITY).path}")
        KeepAliveList.ensureFile(root, KeepAliveKind.ACCESSIBILITY)
    }

    private fun loadPaths() {
        if (pathsLoaded) return
        learned.putAll(KeepAliveList.readPaths(rootDir(), KeepAliveKind.ACCESSIBILITY))
        // 兼容旧 asguard.paths
        try {
            val legacy = java.io.File(rootDir(), "asguard.paths")
            if (legacy.exists()) {
                legacy.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.isNotEmpty() && !line.startsWith("#") && line.contains('=')) {
                        learned.putIfAbsent(line.substringBefore('=').trim(), line.substringAfter('=').trim())
                    }
                }
            }
        } catch (_: Throwable) {
        }
        pathsLoaded = true
        if (learned.isNotEmpty()) Logger.i(name, "已加载 ${learned.size} 条组件路径")
    }

    override fun tick() {
        loadPaths()
        val root = rootDir()
        val pkgs = LinkedHashSet<String>()
        pkgs.addAll(KeepAliveList.read(root, KeepAliveKind.ACCESSIBILITY))
        // 兼容旧 asguard.conf
        try {
            val legacy = java.io.File(root, "asguard.conf")
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
                        KeepAliveList.savePath(root, KeepAliveKind.ACCESSIBILITY, pkg, found)
                        Logger.i(name, "已学习 $pkg 的无障碍组件 -> $found")
                    }
                    continue
                }
                if (AccessibilityGrant.enable(pkg)) {
                    AccessibilityGrant.servicesOf(pkg).firstOrNull()?.let {
                        learned[pkg] = it
                        KeepAliveList.savePath(root, KeepAliveKind.ACCESSIBILITY, pkg, it)
                    }
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