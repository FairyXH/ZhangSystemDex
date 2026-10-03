package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.ConfigManager
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File

/**
 * 垃圾清理引擎：只读扫描 + 规则驱动删除。
 *
 * 扫描（[scan]）绝不删除，只统计每条规则匹配到的文件数与字节数；
 * 清理（[clean]）逐规则调用 [RubbishGuard]，所有删除都经中心化审查，
 * 目标包运行中时按配置跳过。
 *
 * 路径策略：外置用 `/data/media/<u>`，私有用 `/data/user/<u>/<pkg>`，
 * 绝不经 `/sdcard`、`/storage/emulated`、`/mnt/user`。
 */
class RubbishCleaner(private val config: ConfigManager) {

    /** 深度扫描缓存（首次全扫，之后仅扫变动位置）。 */
    private val cache = ScanCache(File(config.rootDir))

    data class RuleResult(
        val ruleId: String,
        val name: String,
        val group: String,
        val risk: RiskLevel,
        val files: Int,
        val bytes: Long,
        val skipped: Boolean,
        val skipReason: String,
        val samples: List<String>,
        val rejected: List<RubbishGuard.Rejected> = emptyList(),
    )

    data class Summary(
        val results: List<RuleResult>,
        val totalFiles: Int,
        val totalBytes: Long,
        val dryRun: Boolean,
    )

    // ------------------------------------------------------------------
    // 扫描（只读）
    // ------------------------------------------------------------------

    fun scan(ruleIds: List<String> = emptyList(), maxSamples: Int = 5): Summary {
        val rules = selectRules(ruleIds)
        val results = ArrayList<RuleResult>()
        var totalFiles = 0
        var totalBytes = 0L
        for (rule in rules) {
            val r = scanRule(rule, maxSamples)
            results += r
            totalFiles += r.files
            totalBytes += r.bytes
        }
        return Summary(results, totalFiles, totalBytes, dryRun = true)
    }

    private fun scanRule(rule: CleanRule, maxSamples: Int): RuleResult {
        val skip = shouldSkipForRunning(rule)
        if (skip != null) {
            return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, skip, emptyList())
        }
        // 深度扫描模式：走专用实现（带缓存增量）
        if (isDeepMode(rule.mode)) {
            val deep = scanDeep(rule, maxSamples)
            return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, deep.files, deep.bytes, false, "", deep.samples)
        }
        val samples = ArrayList<String>()
        var files = 0
        var bytes = 0L
        for (userId in mediaUserIds()) {
            for (root in expandRoots(rule, userId)) {
                for (target in resolveTargets(rule, root)) {
                    if (samples.size < maxSamples) samples += target.path
                    if (rule.mode == MatchMode.EMPTY_DIR) {
                        val stat = statEmpty(rule, target)
                        files += stat.first
                        bytes += stat.second
                    } else if (rule.mode == MatchMode.OLDER_THAN && target.isDirectory) {
                        val stat = statOlderThan(rule, target)
                        files += stat.first
                        bytes += stat.second
                    } else if (target.isDirectory) {
                        val stat = statDir(rule, target)
                        files += stat.first
                        bytes += stat.second
                    } else if (target.isFile) {
                        files++
                        bytes += target.length()
                    }
                }
            }
        }
        return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, files, bytes, false, "", samples)
    }

    // ------------------------------------------------------------------
    // 深度扫描（文件头识别 + 缓存增量）
    // ------------------------------------------------------------------

    private fun isDeepMode(m: MatchMode): Boolean = when (m) {
        MatchMode.APK_SCAN, MatchMode.BIG_FILE_SCAN,
        MatchMode.DUP_SAME_SIZE, MatchMode.DUP_CONTENT, MatchMode.JUNK_SCAN -> true
        else -> false
    }

    private data class DeepResult(val files: Int, val bytes: Long, val samples: List<String>)

    /**
     * 深度扫描入口。结果写入 [ScanCache]：首次全扫（逐文件读文件头/哈希），
     * 之后仅对「尺寸或 mtime 变化」的文件重新判定，未变文件直接复用缓存。
     */
    private fun scanDeep(rule: CleanRule, maxSamples: Int): DeepResult {
        val roots = collectRoots(rule)
        return when (rule.mode) {
            MatchMode.APK_SCAN -> scanApk(rule, roots, maxSamples)
            MatchMode.BIG_FILE_SCAN -> scanBigFiles(rule, roots, maxSamples)
            MatchMode.DUP_SAME_SIZE, MatchMode.DUP_CONTENT -> scanDuplicates(rule, roots, maxSamples)
            MatchMode.JUNK_SCAN -> scanJunk(rule, roots, maxSamples)
            else -> DeepResult(0, 0L, emptyList())
        }
    }

    /**
     * 泛化垃圾扫描：按 [JunkPatterns] 特征识别全部 App 的潜在垃圾。
     *
     * 覆盖维度：目录名（cache/logs/tmp/crash...）、文件名后缀（.log/.tmp/.bak...）、
     * 文件名特征（thumbs.db/core...）、零字节文件、hprof 堆转储。
     *
     * 预筛：跳过 [RubbishGuard.isForbiddenPath] 与 [JunkPatterns.isProtectedPath]。
     */
    private fun scanJunk(rule: CleanRule, roots: List<File>, maxSamples: Int): DeepResult {
        val idx = cache.load(rule.id)
        var count = 0
        var bytes = 0L
        val samples = ArrayList<String>()
        val alive = HashSet<String>()
        val stack = ArrayDeque<File>()
        roots.forEach { stack.addLast(it) }
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!visited.add(cur.path)) continue
            if (RubbishGuard.isForbiddenPath(cur.path)) continue
            if (JunkPatterns.isProtectedPath(cur.path.lowercase())) continue
            // 深度限制：避免遍历到过深层（默认 8 层）
            if (rule.maxDepth > 0 && cur.path.split('/').size > rule.maxDepth) continue

            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (RubbishGuard.isForbiddenPath(c.path)) continue
                if (JunkPatterns.isProtectedPath(c.path.lowercase())) continue
                if (c.isDirectory) {
                    // 空目录：直接计为垃圾
                    if (JunkPatterns.isEmptyDir(c)) {
                        count++
                        if (samples.size < maxSamples) samples += c.path
                    } else {
                        stack.addLast(c)
                    }
                } else if (c.isFile) {
                    alive += c.path
                    val size = c.length()
                    // ★ ageDays 过滤：只清理 N 天以上未修改的文件（防碰到活跃句柄）。
                    if (rule.ageDays > 0 && !olderThan(c, rule.ageDays)) continue
                    // 增量：命中缓存则复用
                    val fresh = idx.isFresh(c.path, size, c.lastModified())
                    val junk = if (fresh) {
                        idx.get(c.path)?.kind == 'J'
                    } else {
                        val j = JunkPatterns.isJunk(c.name, cur.name, c, size)
                        idx.put(ScanCache.Entry(if (j) 'J' else 'O', c.path, size, c.lastModified()))
                        j
                    }
                    if (junk) {
                        count++
                        bytes += size
                        if (samples.size < maxSamples) samples += c.path
                    }
                }
            }
        }
        pruneIndex(idx, alive)
        cache.save(rule.id, idx)
        return DeepResult(count, bytes, samples)
    }

    /** 增量判定单个文件类型（命中缓存则复用，否则读文件头）。 */
    private fun classify(rule: CleanRule, idx: ScanCache.Index, f: File): Char {
        val size = f.length()
        val mtime = f.lastModified()
        if (idx.isFresh(f.path, size, mtime)) {
            return idx.get(f.path)?.kind ?: 'O'
        }
        val kind = when {
            FileIdentifier.isApk(f) -> 'A'
            FileIdentifier.hasZipMagic(f) -> 'Z'
            else -> 'O'
        }
        idx.put(ScanCache.Entry(kind, f.path, size, mtime))
        return kind
    }

    /** 递归收集候选文件（按尺寸下限过滤，跳过 keep 与目录）。 */
    private fun collectFiles(rule: CleanRule, roots: List<File>, minBytes: Long): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        roots.forEach { stack.addLast(it) }
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val key = cur.path
            if (!visited.add(key)) continue
            // 纵深防御：扫描阶段即排除「禁止目录」，避免统计虚高与无效候选。
            if (RubbishGuard.isForbiddenPath(key)) continue
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (c.isDirectory) {
                    stack.addLast(c)
                } else if (c.isFile && c.length() >= minBytes) {
                    if (!RubbishGuard.isForbiddenPath(c.path)) out += c
                }
            }
        }
        return out
    }

    /** 全盘/私有 APK 扫描：文件头识别，无视扩展名。 */
    private fun scanApk(rule: CleanRule, roots: List<File>, maxSamples: Int): DeepResult {
        val idx = cache.load(rule.id)
        val files = collectFiles(rule, roots, rule.minBytes)
        var count = 0
        var bytes = 0L
        val samples = ArrayList<String>()
        for (f in files) {
            val kind = classify(rule, idx, f)
            if (kind == 'A') {
                count++
                bytes += f.length()
                if (samples.size < maxSamples) samples += f.path
            }
        }
        // 清理索引中已消失的条目
        pruneIndex(idx, files.map { it.path }.toHashSet())
        cache.save(rule.id, idx)
        return DeepResult(count, bytes, samples)
    }

    /** 大文件列出（仅列出，不删）。 */
    private fun scanBigFiles(rule: CleanRule, roots: List<File>, maxSamples: Int): DeepResult {
        val threshold = (if (rule.bigFileMb > 0) rule.bigFileMb else 50).toLong() * 1024 * 1024
        val files = collectFiles(rule, roots, threshold).sortedByDescending { it.length() }
        val samples = ArrayList<String>()
        var bytes = 0L
        files.forEach { f ->
            bytes += f.length()
            if (samples.size < maxSamples) samples += "${f.length() / 1024 / 1024}MB  ${f.path}"
        }
        return DeepResult(files.size, bytes, samples)
    }

    /**
     * 重复文件扫描。
     *  - DUP_SAME_SIZE：仅按尺寸分组（轻量预筛），每组保留最新，其余计为可清理。
     *  - DUP_CONTENT：尺寸分组后，再按内容哈希（前 4MB + 全量）细分，完全相同的才处理。
     */
    private fun scanDuplicates(rule: CleanRule, roots: List<File>, maxSamples: Int): DeepResult {
        val files = collectFiles(rule, roots, rule.minBytes)
        val bySize = files.groupBy { it.length() }
        var count = 0
        var bytes = 0L
        val samples = ArrayList<String>()
        for ((_, group) in bySize) {
            if (group.size < 2) continue
            val victims: List<File> = if (rule.mode == MatchMode.DUP_CONTENT) {
                group.groupBy { FileIdentifier.contentHash(it, 0) ?: it.path }
                    .values.filter { it.size > 1 }
                    .flatMap { pickVictims(it, rule.keepNewest) }
            } else {
                pickVictims(group, rule.keepNewest)
            }
            for (v in victims) {
                count++
                bytes += v.length()
                if (samples.size < maxSamples) samples += v.path
            }
        }
        return DeepResult(count, bytes, samples)
    }

    /** 从一组重复文件中选出「可删除项」（保留最新一份）。 */
    private fun pickVictims(group: List<File>, keepNewest: Boolean): List<File> {
        if (group.size < 2) return emptyList()
        val sorted = group.sortedByDescending { it.lastModified() }
        val keep = if (keepNewest) sorted.first() else sorted.last()
        return sorted.filter { it.path != keep.path }
    }

    /**
     * 文件是否「超过 N 天未修改」。用于避免清理正在被写入的活跃文件。
     */
    private fun olderThan(f: File, days: Int): Boolean {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        return f.lastModified() in 1 until cutoff
    }

    /** 从索引中剔除已不存在的路径（增量维护）。 */
    private fun pruneIndex(idx: ScanCache.Index, alive: Set<String>) {
        val dead = idx.entries.keys.filter { it !in alive }
        dead.forEach { idx.remove(it) }
    }

    /**
     * 收集泛化垃圾清理目标（JUNK_SCAN 的实际删除候选）。
     *
     * 与 [scanJunk] 判定一致，但**只返回文件**（空目录由 cleanup 阶段单独处理），
     * 且严格排除受保护路径。
     */
    private fun collectJunkVictims(rule: CleanRule, roots: List<File>): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        roots.forEach { stack.addLast(it) }
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!visited.add(cur.path)) continue
            if (RubbishGuard.isForbiddenPath(cur.path)) continue
            if (JunkPatterns.isProtectedPath(cur.path.lowercase())) continue
            if (rule.maxDepth > 0 && cur.path.split('/').size > rule.maxDepth) continue
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (RubbishGuard.isForbiddenPath(c.path)) continue
                if (JunkPatterns.isProtectedPath(c.path.lowercase())) continue
                if (c.isDirectory) {
                    if (JunkPatterns.isEmptyDir(c)) out += c else stack.addLast(c)
                } else if (c.isFile && JunkPatterns.isJunk(c.name, cur.name, c, c.length())) {
                    // ★ ageDays 过滤：只清理 N 天以上未修改的文件。
                    if (rule.ageDays > 0 && !olderThan(c, rule.ageDays)) continue
                    out += c
                }
            }
        }
        return out
    }

    /**
     * 深度模式的实际删除：
     *  1. 复用 [scanDeep] 得到「可清理目标」（APK 列表 / 重复文件受害者）；
     *  2. BIG_FILE_SCAN 为 listOnly，永不删除；
     *  3. 其余逐个走 [RubbishGuard.safeDelete]（唯一删除入口）；
     *  4. 删除后使该规则缓存失效，强制下次重扫。
     */
    private fun cleanDeep(
        rule: CleanRule,
        maxSamples: Int,
    ): Triple<Int, Long, List<RubbishGuard.Rejected>> {
        if (rule.listOnly) return Triple(0, 0L, emptyList())
        val roots = collectRoots(rule)
        val victims: List<File> = when (rule.mode) {
            MatchMode.APK_SCAN -> {
                val idx = cache.load(rule.id)
                collectFiles(rule, roots, rule.minBytes).filter { classify(rule, idx, it) == 'A' }
            }
            MatchMode.DUP_SAME_SIZE, MatchMode.DUP_CONTENT -> {
                val files = collectFiles(rule, roots, rule.minBytes)
                files.groupBy { it.length() }
                    .filterValues { it.size > 1 }
                    .values
                    .flatMap { group ->
                        if (rule.mode == MatchMode.DUP_CONTENT) {
                            group.groupBy { FileIdentifier.contentHash(it, 0) ?: it.path }
                                .values.filter { it.size > 1 }
                                .flatMap { pickVictims(it, rule.keepNewest) }
                        } else {
                            pickVictims(group, rule.keepNewest)
                        }
                    }
            }
            MatchMode.JUNK_SCAN -> collectJunkVictims(rule, roots)
            else -> emptyList()
        }
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<RubbishGuard.Rejected>()
        for (v in victims) {
            val r = RubbishGuard.safeDelete(v.path, rule.id)
            files += r.deletedFiles
            bytes += r.deletedBytes
            rejected += r.rejected
        }
        // 删除后缓存失效（下次重扫，避免复用过期索引）
        if (victims.isNotEmpty()) cache.invalidate(rule.id)
        return Triple(files, bytes, rejected)
    }

    /** 统计目录中的空目录与 0 字节文件（EMPTY_DIR 模式，与 cleanEmpty 对称）。 */
    private fun statEmpty(rule: CleanRule, dir: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (c.isDirectory) {
                    stack.addLast(c)
                    if (c.listFiles()?.isEmpty() == true) files++ // 空目录计入
                } else if (c.isFile && c.length() == 0L) {
                    files++
                }
            }
        }
        return files to bytes
    }

    /** 统计目录中超过 ageDays 的文件（OLDER_THAN 模式，与 cleanOlderThan 对称）。 */
    private fun statOlderThan(rule: CleanRule, dir: File): Pair<Int, Long> {
        val cutoff = System.currentTimeMillis() - rule.ageDays.toLong() * 86400_000L
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (c.isDirectory) {
                    stack.addLast(c)
                } else if (c.isFile && c.lastModified() in 1 until cutoff) {
                    files++
                    bytes += c.length()
                }
            }
        }
        return files to bytes
    }

    private fun statDir(rule: CleanRule, dir: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        val children = dir.listFiles() ?: return 0 to 0L
        for (child in children) {
            if (child.name in rule.keep) continue
            if (child.isDirectory) {
                val sub = statDir(rule, child)
                files += sub.first
                bytes += sub.second
            } else if (child.isFile) {
                files++
                bytes += child.length()
            }
        }
        return files to bytes
    }

    // ------------------------------------------------------------------
    // 清理（全部经 RubbishGuard）
    // ------------------------------------------------------------------

    fun clean(ruleIds: List<String> = emptyList(), maxSamples: Int = 5): Summary {
        val rules = selectRules(ruleIds)
        val results = ArrayList<RuleResult>()
        var totalFiles = 0
        var totalBytes = 0L
        val handledRules = ArrayList<String>()

        for (rule in rules) {
            val skip = shouldSkipForRunning(rule)
            if (skip != null) {
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, skip, emptyList())
                continue
            }
            handledRules += rule.id
            val samples = ArrayList<String>()
            var files = 0
            var bytes = 0L
            val rejected = ArrayList<RubbishGuard.Rejected>()

            // 深度模式：先扫描出受害者列表，再逐个经 RubbishGuard 删除。
            if (isDeepMode(rule.mode)) {
                val deepClean = cleanDeep(rule, maxSamples)
                files += deepClean.first
                bytes += deepClean.second
                rejected += deepClean.third
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, files, bytes, false, "", samples, rejected)
                totalFiles += files
                totalBytes += bytes
                continue
            }

            for (userId in mediaUserIds()) {
                for (root in expandRoots(rule, userId)) {
                    for (target in resolveTargets(rule, root)) {
                        if (samples.size < maxSamples) samples += target.path
                        val res: RubbishGuard.DeleteResult = when (rule.mode) {
                            MatchMode.DIR_CONTENT -> RubbishGuard.safeCleanDirContents(target.path, rule.id)
                            MatchMode.OLDER_THAN -> cleanOlderThan(target, rule)
                            MatchMode.EMPTY_DIR -> cleanEmpty(rule, target)
                            else -> RubbishGuard.safeDelete(target.path, rule.id)
                        }
                        files += res.deletedFiles
                        bytes += res.deletedBytes
                        rejected += res.rejected
                    }
                }
            }
            results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, files, bytes, false, "", samples, rejected)
            totalFiles += files
            totalBytes += bytes
        }

        RubbishGuard.auditLog().logSession(handledRules, totalFiles, totalBytes, results.sumOf { it.rejected.size })
        Logger.i("RubbishCleaner", "清理完成: rules=${handledRules.size} files=$totalFiles bytes=$totalBytes")
        return Summary(results, totalFiles, totalBytes, dryRun = false)
    }

    /**
     * 清理根下的空目录与 0 字节文件（EMPTY_DIR 模式）。
     * 只删除「空目录」与「0 字节普通文件」，绝不删除非空内容。
     */
    private fun cleanEmpty(rule: CleanRule, root: File): RubbishGuard.DeleteResult {
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<RubbishGuard.Rejected>()
        // 后序遍历：先处理子目录，再判断父目录是否变空。
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (cur, visited) = stack.removeLast()
            if (!visited) {
                stack.addLast(cur to true)
                cur.listFiles()?.forEach { c ->
                    if (c.isDirectory) stack.addLast(c to false)
                }
                continue
            }
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (c.isFile && c.length() == 0L) {
                    val r = RubbishGuard.safeDelete(c.path, rule.id)
                    files += r.deletedFiles
                    bytes += r.deletedBytes
                    rejected += r.rejected
                }
            }
            // 目录变空且不是根本身 → 删除该空目录
            if (cur.path != root.path) {
                val left = cur.listFiles()
                if (left != null && left.isEmpty()) {
                    val r = RubbishGuard.safeDelete(cur.path, rule.id)
                    files += r.deletedFiles
                    bytes += r.deletedBytes
                    rejected += r.rejected
                }
            }
        }
        return RubbishGuard.DeleteResult(files, bytes, rejected)
    }

    /** 按时间删除：仅删除修改时间早于 ageDays 天的文件。 */
    private fun cleanOlderThan(target: File, rule: CleanRule): RubbishGuard.DeleteResult {
        val cutoff = System.currentTimeMillis() - rule.ageDays.toLong() * 86400_000L
        var files = 0
        var bytes = 0L
        val rejected = ArrayList<RubbishGuard.Rejected>()
        val stack = ArrayDeque<File>()
        stack.addLast(target)
        val toDelete = ArrayList<File>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    stack.addLast(c)
                } else if (c.lastModified() in 1 until cutoff) {
                    toDelete += c
                }
            }
        }
        for (f in toDelete) {
            val r = RubbishGuard.safeDelete(f.path, rule.id)
            files += r.deletedFiles
            bytes += r.deletedBytes
            rejected += r.rejected
        }
        return RubbishGuard.DeleteResult(files, bytes, rejected)
    }

    // ------------------------------------------------------------------
    // 规则选择 / 目标解析
    // ------------------------------------------------------------------

    private fun selectRules(ruleIds: List<String>): List<CleanRule> {
        val all = RubbishRuleSet.ALL
        if (ruleIds.isEmpty()) {
            return all.filter { it.switchKey.isNotEmpty() && config.switch(it.switchKey) }
        }
        val set = ruleIds.toSet()
        return all.filter { it.id in set }
    }

    /**
     * 解析一条 root 为实际清理目标。
     *
     * 语义区分（关键，避免误删）：
     *  - GLOB：root 展开后的每个「目录」都作为**搜索根**，再在其中按 pattern
     *    匹配子项；匹配到的子项才是清理目标（绝不清理 root 目录本身）。
     *  - 其它模式（DIR_CONTENT / DIR_SELF / EMPTY_DIR / OLDER_THAN）：root 展开
     *    结果本身就是目标。
     */
    private fun resolveTargets(rule: CleanRule, root: String): List<File> {
        val bases = if (root.indexOf('*') >= 0) expandWildcard(root) else {
            val f = File(root)
            if (f.exists()) listOf(f) else emptyList()
        }
        if (rule.mode != MatchMode.GLOB) return bases

        // GLOB：在 bases 下按 pattern 匹配子项
        val patterns = rule.pattern.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return emptyList()
        val matched = ArrayList<File>()
        for (base in bases) {
            if (!base.isDirectory) continue
            val children = base.listFiles() ?: continue
            for (child in children) {
                if (child.name in rule.keep) continue
                if (patterns.any { globMatch(it, child.name) }) matched += child
            }
        }
        return matched
    }

    /** 单段 glob 匹配（支持 `*` 与 `?`，大小写敏感）。 */
    private fun globMatch(pattern: String, name: String): Boolean {
        val regex = buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).matches(name)
    }

    /** 单段通配展开（如 data/user/0 下的 cache 目录匹配）。 */
    private fun expandWildcard(path: String): List<File> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var current: List<File> = listOf(File("/"))
        for (seg in segments) {
            val next = ArrayList<File>()
            for (dir in current) {
                if (seg == "*") {
                    dir.listFiles()?.forEach { next += it }
                } else if (seg.indexOf('*') >= 0) {
                    val regex = Regex("^" + Regex.escape(seg).replace("\\*", ".*") + "$")
                    dir.listFiles()?.forEach { if (regex.matches(it.name)) next += it }
                } else {
                    val child = File(dir, seg)
                    if (child.exists()) next += child
                }
            }
            current = next
            if (current.isEmpty()) break
        }
        return current
    }

    /**
     * 收集规则的实际扫描根。
     *
     * - 含 `<u>` 的 root：按每个媒体用户 id 展开（如 `/data/user/<u>` → `/data/user/0`、`/data/user/999`）
     * - 不含 `<u>` 的 root：直接使用（如 `/data/log`、`/data/vendor/camera`）
     */
    private fun collectRoots(rule: CleanRule): List<File> {
        val roots = ArrayList<File>()
        val hasPlaceholder = rule.roots.any { it.contains("<u>") }
        if (hasPlaceholder) {
            for (userId in mediaUserIds()) {
                for (p in expandRoots(rule, userId)) {
                    if (p.contains("<u>")) continue
                    val f = File(p)
                    if (f.exists()) roots += f
                }
            }
        } else {
            for (p in rule.roots) {
                if (p.contains("<u>")) continue
                val f = File(p)
                if (f.exists()) roots += f
            }
        }
        return roots
    }

    private fun expandRoots(rule: CleanRule, userId: Int): List<String> =
        rule.roots.map { it.replace("<u>", userId.toString()) }

    /** 枚举 /data/media 与 /data/user 下的用户 id（真实路径）。 */
    fun mediaUserIds(): List<Int> {
        val ids = LinkedHashSet<Int>()
        val bases = listOf("/data/media", "/data/user")
        for (base in bases) {
            val list = File(base).listFiles()
            if (list == null) continue
            for (f in list) {
                if (!f.isDirectory) continue
                val id = f.name.toIntOrNull() ?: continue
                ids += id
            }
        }
        if (ids.isEmpty()) ids += 0
        return ids.toList()
    }

    private fun shouldSkipForRunning(rule: CleanRule): String? {
        val pkg = rule.targetPackage
        if (pkg.isEmpty()) return null
        if (config.switch("rubbish_force_when_running")) return null
        val pids = ProcessUtils.pidsOf(pkg)
        if (pids.isNotEmpty()) {
            return "目标应用运行中（$pkg，pid=${pids.first()}），已跳过"
        }
        return null
    }

    /** 已卸载应用的外部数据目录（运行时判定包是否仍安装）。 */
    fun findUninstalledLeftovers(): List<File> {
        val installed = AppListProvider.allPackages().toSet()
        val result = ArrayList<File>()
        for (uid in mediaUserIds()) {
            val extData = File("/data/media/$uid/Android/data")
            val list = extData.listFiles() ?: continue
            for (d in list) {
                val name = d.name
                if (name.contains('.') && name !in installed && !name.startsWith(".")) {
                    result += d
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // JSON 序列化（供 HTTP 后端）
    // ------------------------------------------------------------------

    fun summaryToJson(s: Summary): String = JsonBuilder.obj {
        key("ok"); value(true); comma()
        key("dryRun"); value(s.dryRun); comma()
        key("totalFiles"); value(s.totalFiles); comma()
        key("totalBytes"); value(s.totalBytes); comma()
        key("rules"); raw(JsonBuilder.arr {
            s.results.forEachIndexed { i, r ->
                if (i > 0) comma()
                raw(JsonBuilder.obj {
                    key("id"); value(r.ruleId); comma()
                    key("name"); value(r.name); comma()
                    key("group"); value(r.group); comma()
                    key("risk"); value(r.risk.name); comma()
                    key("files"); value(r.files); comma()
                    key("bytes"); value(r.bytes); comma()
                    key("skipped"); value(r.skipped); comma()
                    key("skipReason"); value(r.skipReason); comma()
                    key("rejectedCount"); value(r.rejected.size); comma()
                    key("samples"); raw(JsonBuilder.arr {
                        r.samples.forEachIndexed { j, sample ->
                            if (j > 0) comma()
                            value(sample)
                        }
                    })
                })
            }
        })
    }

    /** 规则表 + 当前开关状态（供 WebUI 渲染清理 Tab）。 */
    fun rulesToJson(): String = JsonBuilder.obj {
        key("ok"); value(true); comma()
        key("rules"); raw(JsonBuilder.arr {
            RubbishRuleSet.ALL.forEachIndexed { i, r ->
                if (i > 0) comma()
                raw(JsonBuilder.obj {
                    key("id"); value(r.id); comma()
                    key("name"); value(r.name); comma()
                    key("group"); value(r.group.key); comma()
                    key("groupTitle"); value(r.group.title); comma()
                    key("risk"); value(r.risk.name); comma()
                    key("defaultOn"); value(r.defaultOn); comma()
                    key("enabled"); value(if (r.switchKey.isEmpty()) false else config.switch(r.switchKey)); comma()
                    key("switchKey"); value(r.switchKey); comma()
                    key("note"); value(r.note)
                })
            }
        })
    }
}