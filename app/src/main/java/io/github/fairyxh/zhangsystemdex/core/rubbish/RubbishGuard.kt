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
     *
     * 模型（2026-10-03 扩展）：从「列举允许根」改为「/data 全域 + 排除清单」，
     * 以覆盖系统级垃圾（/data/vendor、/data/misc、/data/system/dropbox 等）。
     * 真正的安全边界由 [FORBIDDEN_EXACT] / [FORBIDDEN_PREFIX] / 用户违禁规则承担。
     */
    private val ALLOWED_ROOTS: List<String> = listOf(
        // 外部存储真实路径（严禁经 /sdcard、/storage/emulated、/mnt/user）
        "/data/media",
        // 应用私有目录（真实路径，等价 /data/data，多用户正确）
        "/data/user",
        "/data/data",
        // 系统崩溃/诊断目录
        "/data/anr",
        "/data/tombstones",
        "/data/system/dropbox",
        // ===== 系统级垃圾区（本轮新增）=====
        "/data/log",
        "/data/bootchart",
        "/data/debugging",
        "/data/dropbox",
        "/data/ss",
        "/data/resource-cache",
        "/data/ramdump",
        "/data/misc",
        "/data/vendor",
        "/data/system_ce",
        "/data/system_de",
        "/data/cache",
        "/data/local/tmp",
    )

    /**
     * 「/data 全域」模式开关：允许白名单之外的 /data 子路径，
     * 只要它不命中 [FORBIDDEN_EXACT] / [FORBIDDEN_PREFIX] / 用户违禁规则。
     *
     * 这使扫描能覆盖整个 /data（用户明确要求），排除清单保证安全。
     */
    private const val ALLOW_BROAD_DATA = true

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

    /**
     * 活系统运行时目录前缀（**常量，避免高频重复分配**）。
     *
     * 这些目录被系统服务持有句柄、边写边用，删除会导致 HAL/服务崩溃、界面黑屏
     * （2026-10-03 真机事故教训）。命中即无条件拒绝，是审查链的「最后一道防线」。
     */
    private val LIVE_SYSTEM_PREFIXES: List<String> = listOf(
        "/data/vendor/", "/data/misc/", "/data/system_ce/", "/data/system_de/",
        "/data/ramdump", "/data/ss/", "/data/dropbox/", "/data/cache/",
    )

    /** 判定（已小写的）规范化路径是否命中活系统目录。 */
    private fun isLiveSystem(lowerCanonical: String): Boolean =
        LIVE_SYSTEM_PREFIXES.any {
            lowerCanonical == it.trimEnd('/') || lowerCanonical.startsWith(it)
        }

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

    /**
     * 快速判断路径是否落在「禁止目录」内（黑名单前缀 + 用户违禁路径）。
     *
     * 供扫描阶段做**预筛**，避免把模块自身 APK、系统目录等计入可清理候选。
     * 注意：这只是加速，真正的删除仍需走完整 [check]。
     */
    fun isForbiddenPath(path: String): Boolean {
        val canonical = try {
            File(path).canonicalPath
        } catch (_: Throwable) {
            path
        }
        for (bad in FORBIDDEN_PREFIX) {
            if (canonical == bad || canonical.startsWith(bad + "/")) return true
        }
        if (canonical in FORBIDDEN_EXACT) return true
        // 活系统运行时目录（与 check() 的第 2b 步保持一致）。
        if (isLiveSystem(canonical.lowercase())) return true
        val rules = userRules
        for (deny in rules.denyPaths) {
            if (canonical == deny || canonical.startsWith(deny.trimEnd('/') + "/")) return true
        }
        return false
    }

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
        // 例外：/data 根下的「顶层散落文件」（如内核追踪输出 /data/*_bcc.csv）。
        // 严格限制：必须是文件，且文件名匹配已知的临时产物模式。
        val isDataTopLevelFile = segments.size == 2 &&
            segments[0] == "data" &&
            File(canonical).isFile &&
            isKnownDataRootFile(segments[1])
        if (segments.size < MIN_SEGMENTS && !isDataTopLevelFile) {
            return Verdict.Reject("路径层级过浅（$canonical），疑似根/关键目录")
        }

        // 2b) 活系统目录硬拒绝（2026-10-03 真机事故后新增，最后一道防线）。
        //     /data/vendor（相机/基带/音频 HAL）、/data/misc（传感器/蓝牙运行时）、
        //     system_ce/de（system_server 状态快照）等目录被系统服务持有句柄，
        //     删除会导致 HAL 崩溃、界面黑屏，因此在此**无条件拒绝**。
        if (isLiveSystem(canonical.lowercase())) {
            return Verdict.Reject("命中活系统运行时目录（真机事故防护）: $canonical")
        }

        // 4) 精确黑名单（允许根自身已在 ALLOWED_ROOTS 中显式豁免）。
        if (canonical in FORBIDDEN_EXACT && canonical !in ALLOWED_ROOTS) {
            return Verdict.Reject("命中禁止目录（精确）: $canonical")
        }

        // 5) 前缀黑名单：禁止删除这些目录自身及其下所有内容。
        //    注意：ALLOWED_ROOTS 中的具体目录（如 /data/anr）需要豁免，
        //    否则「清理该目录内容」的目标会被误拒。
        for (bad in FORBIDDEN_PREFIX) {
            if (canonical == bad || canonical.startsWith(bad + "/")) {
                // 若该路径本身落在允许根内（如 /data/system/dropbox 属于允许根），放行。
                val inAllowedRoot = ALLOWED_ROOTS.any { root ->
                    canonical == root || canonical.startsWith(root + "/")
                }
                if (!inAllowedRoot) {
                    return Verdict.Reject("命中禁止目录: $bad")
                }
            }
        }

        // 6) 白名单：必须落在允许根之内（且不等于根自身）。
        val inAllowed = ALLOWED_ROOTS.any { root ->
            canonical.startsWith(root + "/")
        }
        // /data 全域模式：白名单之外，若落在 /data 下且未命中任何禁止项，也放行。
        val broadData = ALLOW_BROAD_DATA &&
            canonical.startsWith("/data/") &&
            canonical !in FORBIDDEN_EXACT
        if (!inAllowed && !broadData) {
            return Verdict.Reject("不在允许根白名单内: $canonical")
        }
        // 不允许直接删除白名单根自身。
        if (canonical in ALLOWED_ROOTS) {
            return Verdict.Reject("禁止删除允许根本身: $canonical")
        }
        // /data 下只允许删除「至少两级」的内容（如 /data/xxx/yyy），
        // 避免误删 /data/<topdir> 自身；
        // 例外：/data 根下匹配白名单模式的散落文件（如 *_bcc.csv）。
        if (broadData) {
            val rel = canonical.removePrefix("/data/")
            val topLevelOk = !rel.contains('/') &&
                File(canonical).isFile &&
                isKnownDataRootFile(rel)
            if (!rel.contains('/') && !topLevelOk) {
                return Verdict.Reject("禁止删除 /data 一级目录: $canonical")
            }
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
            // 已知保护文件（如 .nomedia）被拒属于「正常跳过」，不计入审计拒绝，
            // 避免日志噪音淹没真正的安全拒绝记录。
            if (isKnownProtectFile(rawPath, verdict.reason)) {
                return DeleteResult(0, 0L, emptyList())
            }
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
     *
     * 安全说明（重要）：
     *  - 对**目录自身**的审查采用「宽松模式」：只要求它是受信任的容器的语义
     *    （落在允许根内、命中禁止项则拒绝），但**不要求满足 MIN_SEGMENTS**
     *    等面向「删除目标」的约束——因为这里并不删除该目录本身，
     *    仅删除其**子项**，而每个子项都会走完整 [check]。
     *  - 例：`/data/anr` 只有 2 段，作为「要删除的目标」应拒绝，
     *    但作为「要被清空的容器」是合法的（其子项 /data/anr/xxx 才会被逐条审查）。
     *  - [filesOnly]=true 时**只删除直接子文件，不递归进子目录**（更保守）：
     *    用于「应用 cache」这类可能被应用挪作他用的目录（有应用把用户配置
     *    放在 cache/configs/ 下），避免误删子目录中的真实数据。
     */
    fun safeCleanDirContents(
        rawPath: String,
        ruleId: String,
        keepSelf: Boolean = true,
        filesOnly: Boolean = false,
    ): DeleteResult {
        val dir = File(rawPath)
        if (!dir.isDirectory) return DeleteResult(0, 0L, emptyList())

        // 容器审查（宽松）：禁止项命中即拒绝；不做 MIN_SEGMENTS 限制。
        val containerVerdict = checkContainer(rawPath)
        if (containerVerdict is Verdict.Reject) {
            audit.log(ruleId, "REJECT", rawPath, 0L, containerVerdict.reason)
            return DeleteResult(0, 0L, listOf(Rejected(rawPath, containerVerdict.reason)))
        }

        val children = dir.listFiles() ?: return DeleteResult(0, 0L, emptyList())
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<Rejected>()
        for (child in children) {
            if (filesOnly && child.isDirectory) continue
            val r = safeDelete(child.path, ruleId)
            files += r.deletedFiles
            bytes += r.deletedBytes
            rejected += r.rejected
        }
        if (!keepSelf) {
            // 仅在显式要求时删除目录自身，且必须再次通过**完整**审查。
            val self = safeDelete(rawPath, ruleId)
            files += self.deletedFiles
            bytes += self.deletedBytes
            rejected += self.rejected
        }
        return DeleteResult(files, bytes, rejected)
    }

    /**
     * 「容器」审查：用于 [safeCleanDirContents] 中对待清空目录自身的判定。
     *
     * 与 [check] 的区别：**不施加 MIN_SEGMENTS 与「禁止删除允许根本身」限制**，
     * 因为该目录本身不会被删除，只有其子项会。仍保留最严格的黑名单与活系统防护。
     */
    fun checkContainer(rawPath: String): Verdict {
        if (rawPath.isBlank()) return Verdict.Reject("路径为空")
        val canonical: String = try {
            File(rawPath).canonicalPath
        } catch (t: Throwable) {
            return Verdict.Reject("规范化失败: ${t.message}")
        }
        if (canonical.indexOf('\u0000') >= 0) return Verdict.Reject("路径含非法字符")

        // 活系统目录：容器也绝不允许（其子项会被删，风险相同）。
        if (isLiveSystem(canonical.lowercase())) {
            return Verdict.Reject("命中活系统运行时目录（真机事故防护）: $canonical")
        }
        // 前缀黑名单：容器自身若命中，直接拒绝（避免遍历系统目录）。
        for (bad in FORBIDDEN_PREFIX) {
            if (canonical == bad || canonical.startsWith(bad + "/")) {
                val inAllowedRoot = ALLOWED_ROOTS.any { root ->
                    canonical == root || canonical.startsWith(root + "/")
                }
                if (!inAllowedRoot) return Verdict.Reject("命中禁止目录: $bad")
            }
        }
        if (canonical in FORBIDDEN_EXACT && canonical !in ALLOWED_ROOTS) {
            return Verdict.Reject("命中禁止目录（精确）: $canonical")
        }
        // 用户违禁路径（前缀）。
        for (deny in userRules.denyPaths) {
            if (canonical == deny || canonical.startsWith(deny.trimEnd('/') + "/")) {
                return Verdict.Reject("命中用户违禁路径: $deny")
            }
        }
        // 用户违禁词（子串）。容器路径本身也适用。
        val lower = canonical.lowercase()
        for (word in userRules.denyWords) {
            if (lower.contains(word.lowercase())) return Verdict.Reject("命中用户违禁词: $word")
        }
        // 必须落在允许根内（或 /data 全域）。
        val inAllowed = ALLOWED_ROOTS.any { canonical == it || canonical.startsWith(it + "/") }
        val broadData = ALLOW_BROAD_DATA && canonical.startsWith("/data/")
        if (!inAllowed && !broadData) {
            return Verdict.Reject("不在允许根白名单内: $canonical")
        }
        return Verdict.Accept
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
                if (!isKnownProtectFile(dir.path, verdict.reason)) {
                    rejected += Rejected(dir.path, verdict.reason)
                    audit.log(ruleId, "REJECT", dir.path, 0L, verdict.reason)
                }
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
                // 已知保护文件（.nomedia 等）属预期跳过，不记审计、不计拒绝。
                if (!isKnownProtectFile(child.path, verdict.reason)) {
                    rejected += Rejected(child.path, verdict.reason)
                    audit.log(ruleId, "REJECT", child.path, 0L, verdict.reason)
                }
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
     *
     * 返回值以「路径是否真的消失」为最终判据——FUSE / 应用私有存储下
     * `File.delete()` 可能返回 false 而实际删除成功，反之亦然。
     */
    private fun deleteOne(f: File, followLink: Boolean): Boolean {
        if (!followLink) {
            try {
                f.delete()
            } catch (_: Throwable) {
            }
        } else {
            try {
                if (!f.delete()) {
                    io.github.fairyxh.zhangsystemdex.core.ShellExecutor
                        .runExit("rm -rf '${escape(f.path)}'")
                }
            } catch (_: Throwable) {
                io.github.fairyxh.zhangsystemdex.core.ShellExecutor
                    .runExit("rm -rf '${escape(f.path)}'")
            }
        }
        // 最终判据：路径是否确实已不存在。
        if (!f.exists() && !isSymlink(f)) return true
        // 兜底再试一次 shell（覆盖 FUSE 缓存导致的 exists() 误判）。
        if (followLink) {
            io.github.fairyxh.zhangsystemdex.core.ShellExecutor
                .runExit("rm -rf '${escape(f.path)}'")
        }
        return !f.exists() && !isSymlink(f)
    }

    private fun isSymlink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    /**
     * 是否为「已知保护文件」被拒（属预期跳过，不必写入审计拒绝）。
     *
     * 典型：`.nomedia` 被默认违禁词命中——它本身就是**防止媒体索引扫描**的标记，
     * 删除它反而有害（会导致相册重新扫描并生成缩略图），因此被拒是正确且预期的。
     */
    private fun isKnownProtectFile(rawPath: String, reason: String): Boolean {
        val name = rawPath.substringAfterLast('/').lowercase()
        return name == ".nomedia" || name == ".nomedia.temp"
    }

    private fun escape(s: String): String = s.replace("'", "'\\''")

    /**
     * /data 根下允许清理的顶层文件模式（严格白名单）。
     * 只放行「明确的临时产物」，绝不放行任何系统文件。
     */
    private val DATA_ROOT_FILE_PATTERNS: List<Regex> = listOf(
        Regex("^\\d{4}-\\d{2}-\\d{2}[_-].*\\.csv$"),   // BCC 追踪输出 2026-04-12_15-49-42_bcc.csv
        Regex("^.*_bcc\\.csv$"),
        Regex("^.*\\.hprof$"),                          // Java 堆转储
        Regex("^tombstone_\\d+$"),                      // 崩溃墓碑
    )

    private fun isKnownDataRootFile(name: String): Boolean =
        DATA_ROOT_FILE_PATTERNS.any { it.matches(name) }

    /** 禁止删除的前缀目录（其自身或位于其下的关键系统位置）。 */
    private val FORBIDDEN_PREFIX: List<String> = listOf(
        // ===== 模块与 root 环境（绝不可动）=====
        "/data/adb",
        "/data/local/tmp/zhang",
        // ===== Python 解释器 / 运行环境（用户明确要求排除）=====
        "/data/python-packages",
        "/data/Python",
        "/data/debian",
        "/data/data/com.termux",
        "/data/user/0/com.termux",
        "/data/data/ru.meefik.linuxdeploy",
        "/data/user/0/ru.meefik.linuxdeploy",
        // ===== 系统核心（删除会破坏系统）=====
        "/data/system",
        "/data/local",
        "/data/app",
        "/data/dalvik-cache",
        "/data/app-lib",
        "/data/app-private",
        "/data/app-staging",
        "/data/app-ephemeral",
        "/data/app-asec",
        "/data/app-metadata",
        "/data/misc/keystore",
        "/data/misc/user",
        "/data/misc/vold",
        "/data/misc/apexdata",
        "/data/misc/gatekeeper",
        "/data/misc/installd",
        "/data/misc/keychain",
        "/data/misc/audioserver",
        "/data/misc/credstore",
        "/data/misc/update_engine",
        "/data/apex",
        "/data/gsi",
        "/data/backup",
        "/data/bootchart/snapshot",   // 仅允许清理其余部分
        "/data/rollback",
        "/data/rollback-history",
        "/data/rollback-observer",
        "/data/sota_package",
        "/data/themes",
        "/data/theme",
        "/data/theme_bak",
        "/data/server_configurable_flags",
        "/data/incremental",
        "/data/mediadrm",
        "/data/drm",
        "/data/vendor/audio",          // 音频校准数据
        "/data/vendor/modem",          // 基带
        "/data/vendor/radio",
        // ===== 其它顶层关键 =====
        "/system",
        "/vendor",
        "/product",
        "/system_ext",
        "/proc",
        "/sys",
        "/dev",
        "/mnt",
        "/storage",
        // ===== 模块自身资源 =====
        "/data/media/0/Download/Files/ZhangProtect-Android",
        "/data/media/0/Download/ZhangSetting",
        // ===== 用户数据（清理会丢数据）=====
        "/data/user_de",               // 设备加密用户数据
        "/data/vendor_ce",
        "/data/vendor_de",
        "/data/unencrypted",
    )
}
