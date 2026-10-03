package io.github.fairyxh.zhangsystemdex.core.rubbish

import java.io.File

/**
 * 用户自定义审查规则（`rubbish_guard.conf`）。
 *
 * 语义：**只增加拒绝，永不减少**。即使用户把内建白名单之外的路径写进来，
 * 也只会让审查更严格。文件不存在时使用内建默认值（默认已是保守的拒绝集合）。
 *
 * 格式（逐行，`#` 起注释）：
 * ```
 * deny_path=/data/adb          # 前缀匹配，命中即拒绝
 * deny_word=EnMicroMsg.db      # 子串匹配（大小写不敏感），命中即拒绝
 * ```
 *
 * 行内注释（` # ` 之后）会被剥离；值两端的空白会去除。
 */
data class UserGuardRules(
    val denyPaths: List<String>,
    val denyWords: List<String>,
) {
    companion object {
        val EMPTY = UserGuardRules(emptyList(), emptyList())

        /** 内建默认违禁词：这些是聊天记录/账号数据的关键文件名，绝不允许删除。 */
        val DEFAULT_DENY_WORDS: List<String> = listOf(
            // 微信主数据
            "EnMicroMsg.db",
            "SnsMicroMsg.db",
            "FTS5Index",
            "AppBrandComm.db",
            "MM.sqlite",
            // 账号与凭据
            "shared_prefs",
            "accounts",
        )

        /**
         * 内建默认违禁路径：这些位置即使落在白名单根内也不允许删除。
         * 注意：这里只放「相对具体、可安全前缀匹配」的路径，避免误伤合法清理。
         */
        val DEFAULT_DENY_PATHS: List<String> = listOf(
            "/data/adb",
            "/data/system",
            "/data/local/tmp/zhang",
            // ★ 模块自身挂载的伪装系统应用 APK（全盘 APK 扫描绝不能碰）
            "/data/media/0/Download/Files/ZhangProtect-Android",
            // ★ 模块解包/备份目录
            "/data/media/0/Download/ZhangSetting",
            // ★ 系统关键 APK 存放位置
            "/data/media/0/Android/data/com.android.appsearch.apk",
        )

        const val HEADER: String = "# ZhangSystemDex 垃圾清理审查规则（用户可编辑）\n" +
            "# 只增加拒绝，永不减少；命中任一条即拒绝删除。\n" +
            "# deny_path=<绝对路径>  前缀匹配，命中即拒绝该路径及其子项\n" +
            "# deny_word=<关键词>    路径子串匹配（忽略大小写），命中即拒绝\n" +
            "# 修改后无需重启：daemon 每周期重载，或 WebUI 保存后立即生效。\n"

        /**
         * 生成默认配置文件内容。若文件不存在则写出；存在则不动（用户配置优先）。
         */
        fun defaultContent(): String {
            val sb = StringBuilder(HEADER)
            sb.append("\n# ===== 默认违禁路径（可删除或修改，但删除会降低安全性） =====\n")
            for (p in DEFAULT_DENY_PATHS) sb.append("deny_path=").append(p).append('\n')
            sb.append("\n# ===== 默认违禁词（聊天/账号/密钥关键文件） =====\n")
            for (w in DEFAULT_DENY_WORDS) sb.append("deny_word=").append(w).append('\n')
            sb.append("\n# ===== 用户自定义（在此追加） =====\n")
            return sb.toString()
        }

        /**
         * 从文件加载。文件不存在时返回「内建默认」（不落盘；落盘由调用方决定）。
         * 解析失败的行被忽略，不抛异常。
         */
        fun load(f: File): UserGuardRules {
            if (!f.exists() || !f.isFile) {
                return UserGuardRules(DEFAULT_DENY_PATHS, DEFAULT_DENY_WORDS)
            }
            val paths = LinkedHashSet<String>()
            val words = LinkedHashSet<String>()
            try {
                f.readLines().forEach { raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isEmpty()) return@forEach
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (value.isEmpty()) return@forEach
                    when (key) {
                        "deny_path" -> {
                            // 规范化：必须绝对路径，去除尾部斜杠。
                            if (value.startsWith("/")) paths.add(value.trimEnd('/'))
                        }
                        "deny_word" -> words.add(value)
                    }
                }
            } catch (_: Throwable) {
                return UserGuardRules(DEFAULT_DENY_PATHS, DEFAULT_DENY_WORDS)
            }
            return UserGuardRules(paths.toList(), words.toList())
        }

        /** 确保配置文件存在（首次生成默认内容）。 */
        fun ensureFile(f: File): Boolean {
            if (f.exists()) return false
            return try {
                f.parentFile?.mkdirs()
                f.writeText(defaultContent())
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun isEmpty(): Boolean = denyPaths.isEmpty() && denyWords.isEmpty()
}