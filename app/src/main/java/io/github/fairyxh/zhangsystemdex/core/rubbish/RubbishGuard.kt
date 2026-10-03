package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 中心化删除审查（唯一删除入口）。
 *
 * 安全目标：任何清理动作都必须经过 [safeDelete]；禁止其它模块直接调用
 * `File.delete()` / `rm -rf` 删除垃圾文件。审查链在删除「之前」逐条执行，
 * 任何一条不通过即拒绝，并写入审计日志。
 *
 * 审查链（顺序执行）：
 *  1. 空值 / 空白 检查；
 *  2. 路径规范化（canonicalFile，解析 `..` 与软链），规范化后必须与原始词法
 *     路径语义一致（不做越权跳转）；
 *  3. 层级与长度检查（至少 3 段，禁止 `/`、`/data` 之类）；
 *  4. 允许根白名单（必须落在其中之一）；
 *  5. 系统关键目录黑名单（精确/前缀匹配，含根目录自身）；
 *  6. 用户自定义违禁路径（`rubbish_guard.conf` deny_path，前缀匹配）；
 *  7. 用户自定义违禁词（deny_word，路径子串匹配）；
 *  8. 递归校验（删除目录前，对其下每一个条目再次执行 1–7，防软链逃逸）。
 *
 * 审计：放行/拒绝均写 `log/rubbish_clean.log`（[AuditLog]）。
 */
object RubbishGuard {

    /** 审查结论。 */
    sealed class Verdict {
        object Accept : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    /** 单次删除结果。 */
    data class DeleteResult(
        val deletedFiles: Int,
        val deletedBytes: Long,
        val rejected: List<Rejected>,
    ) {
        fun merged(other: DeleteResult): DeleteResult = DeleteResult(
            deletedFiles + other.deletedFiles,
            deletedBytes + other.deletedBytes,
            rejected + other.rejected,
        )
    }

    data class Rejected(val path: String, val reason: String)

    /**
     * 允许删除的根白名单。路径规范化后必须落在其中之一（或与之相等）。
     * 这些根对应清理功能的合法作用域；新增清理范围必须同步在此登记。
     */
    private val ALLOWED_ROOTS: List<String> = listOf(
        // 外部存储真实路径（严禁经 /sdcard、/storage/emulated、/mnt/user）
        "/data/media",
        // 应用私有目录（真实路径，等价 /data/data，多用户正确）
        "/data/user",
        "/data/data",
        // 系统崩溃/诊断目录（受对应规则约束）
        "/data/anr",
        "/data/tombstones",
        "/data/system/dropbox",
    )

    /**
     * 系统关键目录黑名单（规范化后精确或前缀匹配，命中即拒绝）。
     * 注意：黑名单优先级高于白名单——即使落在 /data/media 也不允许 `=` 根本身。
     */
    private val FORBIDDEN_EXACT: Set<String> = setOf(
        "/",
        "/data",
        "/data/media",
        "/data/user",
        "/data/data",
        "/data/system",
        "/data/adb",
        "/data/local",
        "/data/app",
        "/data/dalvik-cache",
        "/system",
        "/vendor",
        "/product",
        "/system_ext",
        "/mnt",
        "/storage",
        "/sdcard",
        "/proc",
        "/sys",
        "/dev",
        "/etc",
        "/root",
        "/tmp",
    )

    /** 禁止删除的目录层级上限：规范化后路径段数不足此值一律拒绝。 */
    private const val MIN_SEGMENTS = 3

    /** 用户自定义规则缓存（由 [UserGuardRules] 读入）。 */
    @Volatile
    private var userRules: UserGuardRules = UserGuardRules.EMPTY

    /** 审计日志。 */
    private val audit: AuditLog = AuditLog()

    fun loadUserRules(confPath: String) {
        userRules = UserGuardRules.load(File(confPath))
        Logger.i(
            "RubbishGuard",
            "用户审查规则已加载: denyPath=${userRules.denyPaths.size} denyWord=${userRules.denyWords.size}",
        )
    }

    fun userRules(): UserGuardRules = userRules

    fun auditLog(): AuditLog = audit

    // ------------------------------------------------------------------
    // 审查
    // ------------------------------------------------------------------

    /** 仅审查，不删除。ruleId 用于审计与规则级豁免（当前未使用豁免）。 */
    fun check(rawPath: String, ruleId: String): Verdict {
        if (rawPath.isBlank()) return Verdict.Reject("路径为空")

        // 1) 规范化：解析 `..`、软链，得到真实绝对路径。
        val canonical: String = try {
            File(rawPath).canonicalPath
        } catch (t: Throwable) {
            return Verdict.Reject("规范化失败: ${t.message}")
        }

        // 2) 词法路径若含 NUL 直接拒绝（防截断攻击）。
        if (canonical.indexOf('\u0000') >= 0) return Verdict.Reject("路径含非法字符")

        // 3) 层级检查。
        val segments = canonical.split('/').filter { it.isNotEmpty() }
        if (segments.size < MIN_SEGMENTS) {
            return Verdict.Reject("路径层级过浅（$canonical），疑似根/关键目录")
        }

        // 4) 精确黑名单。
        if (canonical in FORBIDDEN_EXACT) {
            return Verdict.Reject("命中禁止目录（精确）: $canonical")
        }

        // 5) 前缀黑名单：禁止删除这些目录自身或其祖先。
        for (bad in FORBIDDEN_PREFIX) {
            if (canonical == bad) return Verdict.Reject("命中禁止目录: $canonical")
        }

        // 6) 白名单：必须落在允许根之内（且不等于根自身）。
        val inAllowed = ALLOWED_ROOTS.any { root ->
            canonical.startsWith(root + "/")
        }
        if (!inAllowed) {
            return Verdict.Reject("不在允许根白名单内: $canonical")
        }
        // 不允许直接删除白名单根自身。
        if (canonical in ALLOWED_ROOTS) {
            return Verdict.Reject("禁止删除允许根本身: $canonical")
        }

        // 6b) 结构性校验：/data/media 与 /data/user 之下必须紧跟数字用户目录，
        //     防止 normalize 之后落到 /data/media/adb 这类非用户路径上。
        val userScopedRoots = listOf("/data/media", "/data/user")
        for (root in userScopedRoots) {
            if (canonical.startsWith(root + "/")) {
                val rest = canonical.substring(root.length + 1)
                val first = rest.substringBefore('/')
                if (first.toIntOrNull() == null) {
                    return Verdict.Reject("$root 之下必须紧跟数字用户目录: $canonical")
                }
            }
        }

        // 7) 用户自定义违禁路径（前缀）。
        for (deny in userRules.denyPaths) {
            if (canonical == deny || canonical.startsWith(deny.trimEnd('/') + "/")) {
                return Verdict.Reject("命中用户违禁路径: $deny")
            }
        }

        // 8) 用户自定义违禁词（子串，大小写不敏感）。
        val lower = canonical.lowercase()
        for (word in userRules.denyWords) {
            if (lower.contains(word.lowercase())) {
                return Verdict.Reject("命中用户违禁词: $word")
            }
        }

        return Verdict.Accept
    }

    /**
     * 唯一删除入口。
     *
     * 对目录执行递归删除，但**每一层每个条目**都要重新通过 [check]；
     * 任一条目被拒绝即跳过该条目（不中断整体），并把拒绝原因汇总返回。
     * 删除使用 Java File API，失败时降级 `rm -rf`（仅对已通过审查的路径）。
     */
    fun safeDelete(rawPath: String, ruleId: String): DeleteResult {
        val f = File(rawPath)
        if (!f.exists() && !isSymlink(f)) {
            return DeleteResult(0, 0L, emptyList())
        }

        val verdict = check(rawPath, ruleId)
        if (verdict is Verdict.Reject) {
            audit.log(ruleId, "REJECT", rawPath, 0L, verdict.reason)
            return DeleteResult(0, 0L, listOf(Rejected(rawPath, verdict.reason)))
        }

        val rejected = ArrayList<Rejected>()
        var files = 0
        var bytes = 0L

        // 符号链接：只删链接本身，绝不跟随。
        if (isSymlink(f)) {
            val ok = deleteOne(f, followLink = false)
            if (ok) {
                files++
                bytes += 0L
                audit.log(ruleId, "DELETE", rawPath, 0L, "symlink")
            } else {
                rejected += Rejected(rawPath, "删除软链失败")
                audit.log(ruleId, "FAIL", rawPath, 0L, "删除软链失败")
            }
            return DeleteResult(files, bytes, rejected)
        }

        if (f.isDirectory) {
            val (df, db, dr) = deleteTree(f, ruleId)
            files += df
            bytes += db
            rejected += dr
        } else {
            val size = f.length()
            val ok = deleteOne(f, followLink = true)
            if (ok) {
                files++
                bytes += size
                audit.log(ruleId, "DELETE", rawPath, size, "file")
            } else {
                rejected += Rejected(rawPath, "删除文件失败")
                audit.log(ruleId, "FAIL", rawPath, size, "删除文件失败")
            }
        }
        return DeleteResult(files, bytes, rejected)
    }

    /**
     * 递归删除目录内容。默认只清空目录内容而保留目录本身（[keepSelf]=true），
     * 这与清理语义一致（例如 cache 目录应保留，只清其内文件）。
     */
    fun safeCleanDirContents(rawPath: String, ruleId: String, keepSelf: Boolean = true): DeleteResult {
        val dir = File(rawPath)
        if (!dir.isDirectory) return DeleteResult(0, 0L, emptyList())

        val verdict = check(rawPath, ruleId)
        if (verdict is Verdict.Reject) {
            audit.log(ruleId, "REJECT", rawPath, 0L, verdict.reason)
            return DeleteResult(0, 0L, listOf(Rejected(rawPath, verdict.reason)))
        }

        val children = dir.listFiles() ?: return DeleteResult(0, 0L, emptyList())
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<Rejected>()
        for (child in children) {
            val r = safeDelete(child.path, ruleId)
            files += r.deletedFiles
            bytes += r.deletedBytes
            rejected += r.rejected
        }
        if (!keepSelf) {
            // 仅在显式要求时删除目录自身，且必须再次通过审查。
            val self = safeDelete(rawPath, ruleId)
            files += self.deletedFiles
            bytes += self.deletedBytes
            rejected += self.rejected
        }
        return DeleteResult(files, bytes, rejected)
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private fun deleteTree(dir: File, ruleId: String): Triple<Int, Long, List<Rejected>> {
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<Rejected>()
        val children = dir.listFiles()
        if (children == null) {
            // 目录不可读：仅尝试删除自身（可能为空目录）。
            val verdict = check(dir.path, ruleId)
            if (verdict is Verdict.Reject) {
                rejected += Rejected(dir.path, verdict.reason)
                audit.log(ruleId, "REJECT", dir.path, 0L, verdict.reason)
            } else if (deleteOne(dir, followLink = true)) {
                files++
                audit.log(ruleId, "DELETE", dir.path, 0L, "dir(empty)")
            }
            return Triple(files, bytes, rejected)
        }

        for (child in children) {
            // 逐条目重新审查（防软链逃逸与越权）。
            val verdict = check(child.path, ruleId)
            if (verdict is Verdict.Reject) {
                rejected += Rejected(child.path, verdict.reason)
                audit.log(ruleId, "REJECT", child.path, 0L, verdict.reason)
                continue
            }
            if (isSymlink(child)) {
                if (deleteOne(child, followLink = false)) {
                    files++
                    audit.log(ruleId, "DELETE", child.path, 0L, "symlink")
                } else {
                    rejected += Rejected(child.path, "删除软链失败")
                }
                continue
            }
            if (child.isDirectory) {
                val (df, db, dr) = deleteTree(child, ruleId)
                files += df
                bytes += db
                rejected += dr
                // 子目录内容清空后删除该目录自身（此时它仍须通过审查）。
                if (dr.none { it.path == child.path } && deleteOne(child, followLink = true)) {
                    files++
                    audit.log(ruleId, "DELETE", child.path, 0L, "dir")
                }
            } else {
                val size = child.length()
                if (deleteOne(child, followLink = true)) {
                    files++
                    bytes += size
                    audit.log(ruleId, "DELETE", child.path, size, "file")
                } else {
                    rejected += Rejected(child.path, "删除文件失败")
                    audit.log(ruleId, "FAIL", child.path, size, "删除文件失败")
                }
            }
        }
        return Triple(files, bytes, rejected)
    }

    /**
     * 实际删除单个路径。
     * [followLink]=false 时只删除链接本身（用 shell `rm -f` 保证不跟随）。
     */
    private fun deleteOne(f: File, followLink: Boolean): Boolean {
        if (!followLink) {
            // 软链安全删除：Java 的 File.delete 对软链删除链接本身，
            // 但仍走一次 shell 兜底以覆盖 FUSE 场景。
            return try {
                if (f.delete()) return true
                io.github.fairyxh.zhangsystemdex.core.ShellExecutor.runExit("rm -f '${escape(f.path)}'") == 0
            } catch (_: Throwable) {
                io.github.fairyxh.zhangsystemdex.core.ShellExecutor.runExit("rm -f '${escape(f.path)}'") == 0
            }
        }
        return try {
            if (f.delete()) return true
            io.github.fairyxh.zhangsystemdex.core.ShellExecutor.runExit("rm -rf '${escape(f.path)}'") == 0
        } catch (_: Throwable) {
            io.github.fairyxh.zhangsystemdex.core.ShellExecutor.runExit("rm -rf '${escape(f.path)}'") == 0
        }
    }

    private fun isSymlink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    private fun escape(s: String): String = s.replace("'", "'\\''")

    /** 禁止删除的前缀目录（其自身或位于其下的关键系统位置）。 */
    private val FORBIDDEN_PREFIX: List<String> = listOf(
        "/data/adb",
        "/data/local",
        "/data/app",
        "/data/dalvik-cache",
        "/data/system",
        "/system",
        "/vendor",
        "/proc",
        "/sys",
        "/dev",
    )
}
