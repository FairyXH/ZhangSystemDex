package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * 权限保活列表（通知使用权 / 无障碍服务）。
 *
 * 一行一个包名，`#` 注释。与 [OomProtectList] 同构，集中负责
 * 「读取 → 归一化 → 渲染」，供保活模块、HTTP 后端与 WebUI 共用。
 *
 * 两类保活各用一个文件（互不影响）：
 *   - [Kind.NOTIFICATION] -> `notif_keepalive.conf`
 *   - [Kind.ACCESSIBILITY] -> `a11y_keepalive.conf`
 *
 * 语义：列表中的包，若其「通知使用权」或「无障碍服务」掉线，
 * 守护模块会自动重新授权（组件路径复用已学习值 / 现场探测）。
 */
enum class KeepAliveKind(val fileName: String, val title: String, val settingsKey: String) {
    NOTIFICATION(
        "notif_keepalive.conf",
        "通知使用权保活",
        "enabled_notification_listeners",
    ),
    ACCESSIBILITY(
        "a11y_keepalive.conf",
        "无障碍服务保活",
        "enabled_accessibility_services",
    ),
}

object KeepAliveList {

    fun file(rootDir: File, kind: KeepAliveKind): File = File(rootDir, kind.fileName)

    /** 读取并归一化。文件不存在返回空列表。 */
    fun read(rootDir: File, kind: KeepAliveKind): List<String> {
        val text = try {
            val f = file(rootDir, kind)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        } catch (t: Throwable) {
            Logger.w("KeepAliveList", "读取 ${kind.fileName} 失败: ${t.message}")
            ""
        }
        return normalize(text)
    }

    /** 归一化文本为包名列表（去注释/空行/重复/非法）。 */
    fun normalize(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val pkg = line.substringBefore('#').trim()
            if (OomProtectList.isValidPackage(pkg)) out.add(pkg)
        }
        return out.toList()
    }

    /** 渲染成文件文本（含文件头注释）。 */
    fun render(packages: List<String>, kind: KeepAliveKind): String = buildString {
        append("# ${kind.title}名单：一行一个包名，`#` 开头为注释。\n")
        append("# 列表中的应用，其 «${kind.title.replace("保活", "")}» 掉线后会被自动重新授权。\n")
        append("# 由 WebUI「保活」页维护，也可手工编辑（保存后无需重启）。\n")
        for (p in packages) append(p).append('\n')
    }

    /** 原子写入。 */
    fun write(rootDir: File, kind: KeepAliveKind, packages: List<String>): Boolean {
        val f = file(rootDir, kind)
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(render(packages, kind), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(render(packages, kind), Charsets.UTF_8)
                tmp.delete()
            }
            Logger.i("KeepAliveList", "${kind.fileName} 已写入 ${packages.size} 项")
            true
        } catch (t: Throwable) {
            Logger.w("KeepAliveList", "写入 ${kind.fileName} 失败: ${t.message}")
            false
        }
    }
}
