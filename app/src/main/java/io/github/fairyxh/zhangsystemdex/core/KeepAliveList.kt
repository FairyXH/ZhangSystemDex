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

    /** 各类型的「学习到的路径」文件名（与 asguard.paths 同构）。 */
    fun pathsFile(rootDir: File, kind: KeepAliveKind): File = File(
        rootDir,
        when (kind) {
            KeepAliveKind.NOTIFICATION -> "notif_keepalive.paths"
            KeepAliveKind.ACCESSIBILITY -> "a11y_keepalive.paths"
        },
    )

    /** 内置默认保活包名（用户指定：通知滤盒 + Operit AI）。 */
    val DEFAULT_PACKAGES: Map<KeepAliveKind, List<String>> = mapOf(
        KeepAliveKind.NOTIFICATION to listOf("com.catchingnow.np", "com.ai.assistance.operit"),
        KeepAliveKind.ACCESSIBILITY to emptyList(),
    )

    fun file(rootDir: File, kind: KeepAliveKind): File = File(rootDir, kind.fileName)

    /**
     * 模块内置应用（`system/app/`）—— **始终**纳入保活名单，不听从配置。
     *
     * 见 [BuiltinApps]：这些应用是模块功能的载体，掉线即模块失效，
     * 因此不提供关闭它们的开关（[KeepAliveKind] 的总开关仅影响用户自选名单）。
     */
    fun builtinPackages(rootDir: File): List<String> = BuiltinApps.packagesFromRoot(rootDir)

    /**
     * 读取并归一化。文件不存在返回内置默认列表。
     *
     * **注意**：返回值始终 union [builtinPackages]（模块内置应用强制保活），
     * 因此调用方拿到的就是「实际生效名单」。
     */
    fun read(rootDir: File, kind: KeepAliveKind): List<String> {
        val f = file(rootDir, kind)
        val userList = if (!f.exists()) {
            DEFAULT_PACKAGES[kind] ?: emptyList()
        } else {
            val text = try {
                f.readText(Charsets.UTF_8)
            } catch (t: Throwable) {
                Logger.w("KeepAliveList", "读取 ${kind.fileName} 失败: ${t.message}")
                ""
            }
            normalize(text)
        }
        return mergeBuiltin(userList, rootDir)
    }

    /** 用户名单 ∪ 内置应用（保持顺序：先用户名单，再内置应用）。 */
    fun mergeBuiltin(list: List<String>, rootDir: File): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(list)
        try {
            out.addAll(builtinPackages(rootDir))
        } catch (t: Throwable) {
            Logger.w("KeepAliveList", "合并内置应用失败: ${t.message}")
        }
        return out.toList()
    }

    /**
     * 读取保活名单；文件不存在时把「内置默认」落盘后再返回，
     * 使配置自始就存在（用户要求：像 asguard.conf 一样有配置文件）。
     */
    fun ensureFile(rootDir: File, kind: KeepAliveKind): List<String> {
        val f = file(rootDir, kind)
        if (!f.exists()) {
            val def = DEFAULT_PACKAGES[kind] ?: emptyList()
            write(rootDir, kind, def)
            Logger.i("KeepAliveList", "已创建默认配置 ${kind.fileName}（${def.size} 项）")
            return mergeBuiltin(def, rootDir)
        }
        return read(rootDir, kind)
    }

    /** 用户在文件中的名单（不含内置应用，供 WebUI 编辑与回写）。 */
    fun readUserOnly(rootDir: File, kind: KeepAliveKind): List<String> {
        val f = file(rootDir, kind)
        if (!f.exists()) return DEFAULT_PACKAGES[kind] ?: emptyList()
        val text = try {
            f.readText(Charsets.UTF_8)
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
        append("# ${kind.title}名单：一行一个包名，`#` 开头为注释（支持行内注释）。\n")
        append("# 列表中的应用，其「${kind.title.replace("保活", "")}」掉线后会被自动重新授权。\n")
        append("# 由 WebUI「保活」页维护，也可手工编辑（保存后无需重启）。\n")
        append("# 注意：模块内置应用（system/app/）始终强制保活，本文件无需重复列出。\n")
        val def = DEFAULT_PACKAGES[kind] ?: emptyList()
        if (def.isNotEmpty()) {
            append("# 内置默认：").append(def.joinToString("、")).append('\n')
        }
        append('\n')
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

    // ==================== 路径映射（与 asguard.paths 同构） ====================

    /**
     * 读取「包名=组件路径」映射。
     *
     * 用途：记录每个包实际生效的 listener/无障碍组件，掉线恢复时可直接复用，
     * 免去每次重新探测（探测需 fork dumpsys，较慢）。
     */
    fun readPaths(rootDir: File, kind: KeepAliveKind): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = pathsFile(rootDir, kind)
        if (!f.exists()) return out
        try {
            f.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val pkg = line.substringBefore('=').trim()
                val path = line.substringAfter('=', "").trim()
                if (pkg.isNotEmpty() && path.isNotEmpty()) out[pkg] = path
            }
        } catch (t: Throwable) {
            Logger.w("KeepAliveList", "读取 ${f.name} 失败: ${t.message}")
        }
        return out
    }

    /** 保存单个包的路径映射（保留其它条目）。 */
    fun savePath(rootDir: File, kind: KeepAliveKind, pkg: String, path: String): Boolean {
        val map = readPaths(rootDir, kind)
        if (map[pkg] == path) return true
        map[pkg] = path
        return writePaths(rootDir, kind, map)
    }

    /** 整体写入路径映射表。 */
    fun writePaths(rootDir: File, kind: KeepAliveKind, map: Map<String, String>): Boolean {
        val f = pathsFile(rootDir, kind)
        return try {
            f.parentFile?.mkdirs()
            val text = buildString {
                append("# ${kind.title} · 已学习组件路径（pkg=组件），由守护模块自动维护。\n")
                map.forEach { (p, c) -> append(p).append('=').append(c).append('\n') }
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Logger.w("KeepAliveList", "写入 ${f.name} 失败: ${t.message}")
            false
        }
    }
}
