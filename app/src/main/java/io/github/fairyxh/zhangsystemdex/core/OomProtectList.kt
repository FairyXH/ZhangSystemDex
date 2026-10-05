package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * OOM 保护名单（`oom_protect.conf`，一行一个包名，`#` 注释）。
 *
 * 与 [GameListProvider] 同构：集中负责「读取 → 归一化 → 校验」，
 * 供 [OomProtectModule]、HTTP 后端与应用选择器共用。
 *
 * ## 归一化规则
 * - 去首尾空白；空行与 `#` 注释行丢弃；
 * - 去重（保持首次出现顺序）；
 * - 仅保留合法包名（`[A-Za-z0-9_.]+` 且至少含一个 `.`），
 *   过滤掉可能混入的路径/命令，避免写 `/proc/...` 时被注入。
 */
object OomProtectList {

    const val FILE_NAME = "oom_protect.conf"

    /** 内置默认包名（用户要求）。 */
    const val DEFAULT_PACKAGE = "com.ai.assistance.operit"

    /** 默认文件内容（带说明注释）。 */
    val DEFAULT_CONTENT: String = buildString {
        append("# OOM 保护名单：一行一个包名，列表中的应用会被提升 oom_score_adj 与进程优先级。\n")
        append("# 以 # 开头为注释。修改后保存即生效（无需重启）。\n")
        append("# 内置默认：\n")
        append(DEFAULT_PACKAGE).append('\n')
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