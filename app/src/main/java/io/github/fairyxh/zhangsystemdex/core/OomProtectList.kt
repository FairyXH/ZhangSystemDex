package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * OOM 保护名单（`oom_protect.conf`，一行一个包名，`#` 注释）。
 *
 * 与 [GameListProvider] 同构：集中负责「读取 → 归一化 → 校验」，
 * 供 [OomProtectModule]、HTTP 后端与应用选择器共用。
 *
 * ## 名单来源（自动合并）
 *
 * 实际生效的保护名单 = 三个来源的**并集**：
 *   1. `oom_protect.conf`（本文件，用户手工维护 / WebUI 清理页编辑）
 *   2. [KeepAliveKind.NOTIFICATION] 名单（`notif_keepalive.conf`）
 *   3. [KeepAliveKind.ACCESSIBILITY] 名单（`a11y_keepalive.conf`）
 *
 * 即：**凡是被保活（通知使用权 / 无障碍服务）的应用，自动纳入 OOM 保护**，
 * 无需再手工往 `oom_protect.conf` 里加一遍。合并逻辑见 [effectivePackages]。
 *
 * ## 归一化规则
 * - 去首尾空白；空行与 `#` 注释行丢弃；
 * - 去重（保持首次出现顺序）；
 * - 仅保留合法包名（`[A-Za-z0-9_.]+` 且至少含一个 `.`），
 *   过滤掉可能混入的路径/命令，避免写 `/proc/...` 时被注入。
 */
object OomProtectList {
    const val FILE_NAME = "oom_protect.conf"

    /**
     * 内置默认包名（用户指定）：
     *   - `com.ai.assistance.operit`    Operit AI
     *   - `com.catchingnow.np`          通知滤盒
     *   - `com.omarea.vtools`           Scene
     *   - `li.songe.gkd`                GKD
     *   - `moe.shizuku.privileged.api`  Shizuku（含其 999/root 服务进程）
     */
    val DEFAULT_PACKAGES: List<String> = listOf(
        "com.ai.assistance.operit",
        "com.catchingnow.np",
        "com.omarea.vtools",
        "li.songe.gkd",
        "moe.shizuku.privileged.api",
    )

    /** 兼容旧调用点：第一个默认包名。 */
    const val DEFAULT_PACKAGE = "com.ai.assistance.operit"

    /** 默认文件内容（带说明注释）。 */
    val DEFAULT_CONTENT: String = buildString {
        append("# OOM 保护名单：一行一个包名，列表中的应用会被提升 oom_score_adj 与进程优先级。\n")
        append("# 以 # 开头为注释。修改后保存即生效（无需重启）。\n")
        append("# 注意：保活名单（notif_keepalive.conf / a11y_keepalive.conf）中的应用\n")
        append("#       会自动纳入 OOM 保护，无需在此重复添加。\n")
        append("# 内置默认：\n")
        for (p in DEFAULT_PACKAGES) append(p).append('\n')
    }

    fun file(rootDir: File): File = File(rootDir, FILE_NAME)
    /** 读取并归一化。文件不存在返回空列表。 */
    fun read(rootDir: File): List<String> = normalize(
        try {
            val f = file(rootDir)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        } catch (t: Throwable) {
            Logger.w("OomProtectList", "读取失败: ${t.message}")
            ""
        }
    )

    /**
     * 实际生效的 OOM 保护名单 = `oom_protect.conf` ∪ 通知保活名单 ∪ 无障碍保活名单。
     *
     * 保活名单里的应用本身就需要长期存活（否则权限会掉），因此自动纳入
     * OOM 保护，避免用户在两处重复维护。顺序：先 `oom_protect.conf`，
     * 再通知保活，最后无障碍保活；去重保持首次出现顺序。
     */
    fun effectivePackages(rootDir: File): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(read(rootDir))
        for (kind in KeepAliveKind.entries) {
            try {
                out.addAll(KeepAliveList.read(rootDir, kind))
            } catch (t: Throwable) {
                Logger.w("OomProtectList", "合并 ${kind.fileName} 失败: ${t.message}")
            }
        }
        return out.toList()
    }

    /** 归一化文本为包名列表（去注释/空行/重复/非法）。 */
    fun normalize(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // 允许行内注释（pkg # 备注）
            val pkg = line.substringBefore('#').trim()
            if (isValidPackage(pkg)) out.add(pkg)
        }
        return out.toList()
    }

    /** 把列表渲染成文件文本（含文件头注释）。 */
    fun render(packages: List<String>): String = buildString {
        append("# OOM 保护名单：一行一个包名，列表中的应用会被提升 oom_score_adj 与进程优先级。\n")
        append("# 以 # 开头为注释。修改后保存即生效（无需重启）。\n")
        for (p in packages) append(p).append('\n')
    }

    /**
     * 合法包名：仅字母/数字/下划线/点，至少一个点，总长 ≤ 255，
     * 段不能为空或以数字开头（Android 包名规则）。
     */
    fun isValidPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.length > 255) return false
        if (!pkg.contains('.')) return false
        for (seg in pkg.split('.')) {
            if (seg.isEmpty()) return false
            val c0 = seg[0]
            if (c0.isDigit()) return false
            for (c in seg) {
                if (!(c.isLetterOrDigit() || c == '_')) return false
            }
        }
        return true
    }
}