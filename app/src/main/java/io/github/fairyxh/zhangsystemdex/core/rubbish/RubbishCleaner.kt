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

    /**
     * 并行扫描器（IDM 风格分片）。并发度可经 `rubbish_scan_threads` 配置
     * （默认 4，范围 1..8），避免在某些低端存储上打爆随机读。
     */
    private val scanner: ParallelScanner by lazy { ParallelScanner(configuredThreads()) }

    /**
     * 扫描进度：最近一次扫描的（已完成分片, 总分片）。供 `/api/rubbish/scan`
     * 的异步任务与概览页展示。volatile 保证跨线程可见。
     */
    @Volatile
    var lastScanDone: Int = 0
        private set

    @Volatile
    var lastScanTotal: Int = 0
        private set

    @Volatile
    var lastScanRule: String = ""
        private set

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
        /**
         * 该规则是否「仅列出不删」（[CleanRule.listOnly]）。
         * 关键：listOnly 规则统计到的是「值得人工复核的文件占用」，
         * 并非「可回收空间」。汇总的 totalFiles/totalBytes 必须排除它们，
         * 否则像 `big_files_private`（整个 /data/user 下 >50MB 文件，
         * 含游戏资源、微信数据库等正常数据）会把「可清理」虚高到数十 GB。
         */
        val listOnly: Boolean = false,
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
            RubbishProgress.enterRule(rule.id, rule.name)
            val r = scanRule(rule, maxSamples)
            results += r
            RubbishProgress.addHits(r.files.toLong(), r.bytes)
            RubbishProgress.finishRule()
            // ★ 仅列出不删（listOnly）的规则不计入「可清理」总量：
            //   它们统计的是可复核的大文件占用，而非可回收空间。
            if (!rule.listOnly) {
                totalFiles += r.files
                totalBytes += r.bytes
            }
        }
        return Summary(results, totalFiles, totalBytes, dryRun = true)
    }

    /**
     * 清理预览：列出「点击清理后将被删除」的具体路径（只读，绝不删除）。
     *
     * 与 [scan] 的区别在于样本语义：scan 的样本是规则目标（可能只是目录），
     * preview 尽量给出**实际会被删除的文件/目录**，供用户清理前核对。
     *
     * 每条规则最多返回 [maxSamples] 条路径（防止超大列表拖垮 WebView），
     * 同时给出该规则的完整 files/bytes，UI 用「等共 N 个」提示被截断。
     */
    fun preview(ruleIds: List<String> = emptyList(), maxSamples: Int = 200): Summary {
        val rules = selectRules(ruleIds)
        val results = ArrayList<RuleResult>()
        var totalFiles = 0
        var totalBytes = 0L
        for (rule in rules) {
            val skip = shouldSkipForRunning(rule)
            if (skip != null) {
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, skip, emptyList(), listOnly = rule.listOnly)
                continue
            }
            val victims = previewVictims(rule, maxSamples)
            val r = RuleResult(
                rule.id, rule.name, rule.group.key, rule.risk,
                victims.first, victims.second, false, "", victims.third, listOnly = rule.listOnly,
            )
            results += r
            if (!rule.listOnly) {
                totalFiles += r.files
                totalBytes += r.bytes
            }
        }
        return Summary(results, totalFiles, totalBytes, dryRun = true)
    }

    /**
     * 收集一条规则的预览数据：返回 (文件数, 字节数, 待删路径样本[≤maxSamples])。
     * 所有路径仅用于展示；真正删除仍走 [RubbishGuard]。
     */
    private fun previewVictims(rule: CleanRule, maxSamples: Int): Triple<Int, Long, List<String>> {
        val samples = ArrayList<String>()
        var files = 0
        var bytes = 0L

        fun add(path: String, size: Long) {
            files++
            bytes += size
            if (samples.size < maxSamples) samples += path
        }

        // 深度模式：复用各自的受害者收集逻辑，样本即真实删除目标。
        if (isDeepMode(rule.mode)) {
            val roots = collectRoots(rule)
            when (rule.mode) {
                MatchMode.APK_SCAN -> {
                    val idx = cache.load(rule.id)
                    for (f in collectFiles(rule, roots, rule.minBytes)) {
                        if (classify(rule, idx, f) == 'A') add(f.path, f.length())
                    }
                }
                MatchMode.BIG_FILE_SCAN -> {
                    val threshold = (if (rule.bigFileMb > 0) rule.bigFileMb else 50).toLong() * 1024 * 1024
                    for (f in collectFiles(rule, roots, threshold).sortedByDescending { it.length() }) {
                        add(f.path, f.length())
                    }
                }
                MatchMode.DUP_SAME_SIZE, MatchMode.DUP_CONTENT -> {
                    val candidates = collectFiles(rule, roots, rule.minBytes)
                    for (group in candidates.groupBy { it.length() }.values) {
                        if (group.size < 2) continue
                        val victims = if (rule.mode == MatchMode.DUP_CONTENT) {
                            group.groupBy { FileIdentifier.contentHash(it, 0) ?: it.path }
                                .values.filter { it.size > 1 }
                                .flatMap { pickVictims(it, rule.keepNewest) }
                        } else pickVictims(group, rule.keepNewest)
                        for (v in victims) add(v.path, v.length())
                    }
                }
                MatchMode.JUNK_SCAN -> {
                    for (f in collectJunkVictims(rule, roots)) {
                        if (f.isDirectory) {
                            if (samples.size < maxSamples) samples += f.path + "/（空目录）"
                            files++
                        } else add(f.path, f.length())
                    }
                }
                else -> {}
            }
            return Triple(files, bytes, samples)
        }

        // 卸载残留：运行时判定仍安装后剩余目录（无 .apk 后缀，不遍历大目录，仅列目录名）。
        if (rule.mode == MatchMode.UNINSTALLED_SCAN) {
            val leftovers = findUninstalledLeftovers()
            for (d in leftovers) add(d.path, dirSize(d))
            return Triple(files, bytes, samples)
        }

        // 普通模式：逐 target 展开为具体待删项。
        for (userId in mediaUserIds()) {
            for (root in expandRoots(rule, userId)) {
                for (target in resolveTargets(rule, root)) {
                    when (rule.mode) {
                        MatchMode.DIR_CONTENT -> {
                            // 清理目录内容：列出（直接子文件的递归集合）
                            for (f in listFilesRecursive(target, if (rule.id == "app_cache") 1 else Int.MAX_VALUE)) {
                                add(f.path, f.length())
                            }
                        }
                        MatchMode.DIR_SELF -> add(target.path, dirSize(target))
                        MatchMode.EMPTY_DIR -> {
                            val empty = collectEmptyItems(rule, target)
                            for (p in empty) {
                                files++
                                if (samples.size < maxSamples) samples += p
                            }
                        }
                        MatchMode.OLDER_THAN -> {
                            val cutoff = System.currentTimeMillis() - rule.ageDays.toLong() * 86400_000L
                            for (f in listFilesRecursive(target, Int.MAX_VALUE)) {
                                if (f.lastModified() in 1 until cutoff) add(f.path, f.length())
                            }
                        }
                        MatchMode.GLOB -> add(target.path, if (target.isDirectory) dirSize(target) else target.length())
                        else -> add(target.path, if (target.isDirectory) dirSize(target) else target.length())
                    }
                }
            }
        }
        return Triple(files, bytes, samples)
    }

    /** 递归列出目录下的文件（depthLimit 限制下钻层数，用于 app_cache 只列直接子文件）。 */
    private fun listFilesRecursive(dir: File, depthLimit: Int): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(dir to 0)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 300000) {
            val (cur, depth) = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (depth + 1 < depthLimit || depthLimit == Int.MAX_VALUE) stack.addLast(c to (depth + 1))
                } else if (c.isFile) out += c
            }
        }
        return out
    }

    /** 收集空目录与 0 字节文件路径（预览用，不删除）。 */
    private fun collectEmptyItems(rule: CleanRule, root: File): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.name in rule.keep) continue
                if (c.isDirectory) {
                    stack.addLast(c)
                    if (c.listFiles()?.isEmpty() == true) out += c.path + "/（空目录）"
                } else if (c.isFile && c.length() == 0L) {
                    out += c.path + "（0 字节）"
                }
            }
        }
        return out
    }

    private fun scanRule(rule: CleanRule, maxSamples: Int): RuleResult {
        val skip = shouldSkipForRunning(rule)
        if (skip != null) {
            return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, skip, emptyList(), listOnly = rule.listOnly)
        }
        // 深度扫描模式：走专用实现（带缓存增量）
        if (isDeepMode(rule.mode)) {
            val deep = scanDeep(rule, maxSamples)
            return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, deep.files, deep.bytes, false, "", deep.samples, listOnly = rule.listOnly)
        }
        // 卸载残留：必须运行时校验「包是否仍安装」，绝不做无差别匹配。
        if (rule.mode == MatchMode.UNINSTALLED_SCAN) {
            val leftovers = findUninstalledLeftovers()
            val samples = leftovers.take(maxSamples).map { it.path }
            var bytes = 0L
            leftovers.forEach { bytes += dirSize(it) }
            return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, leftovers.size, bytes, false, "", samples, listOnly = rule.listOnly)
        }
        val samples = ArrayList<String>()
        var files = 0
        var bytes = 0L
        // 非深度模式：逐 target 处理时上报「当前路径」，并在每处理完一个 target
        // 后刷新累计命中，让前端也能看到进度（虽然通常很快）。
        for (userId in mediaUserIds()) {
            for (root in expandRoots(rule, userId)) {
                for (target in resolveTargets(rule, root)) {
                    RubbishProgress.onPath(target.path)
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
        return RuleResult(rule.id, rule.name, rule.group.key, rule.risk, files, bytes, false, "", samples, listOnly = rule.listOnly)
    }

    /** 测试用：强制单线程扫描（保证 SelfTest 可确定性复现）。 */
    internal fun forceSingleThreadForTest() { scannerThreadsOverride = 1 }

    /** 读取配置的并发度（1..8，默认 4）。 */
    private fun configuredThreads(): Int =
        config.getString("rubbish_scan_threads", "4").toIntOrNull()
            ?.coerceIn(1, 8) ?: ParallelScanner.DEFAULT_THREADS

    @Volatile
    private var scannerThreadsOverride: Int = 0

    /**
     * 测试用：对显式 roots 跑一次 JUNK_SCAN，返回 (文件数, 字节数)。
     * idx 可复用（第二次即模拟暖扫的「分片复用」）。
     */
    internal fun scanJunkForTest(rule: CleanRule, roots: List<File>, idx: ScanCache.Index): Pair<Int, Long> {
        val visitor = JunkVisitor(rule, idx)
        val th = if (scannerThreadsOverride > 0) scannerThreadsOverride else configuredThreads()
        val shards = buildShards(roots)
        val res = ParallelScanner(th).scan(
            shards = shards, idx = idx, cache = cache, ruleId = rule.id,
            visitor = visitor, maxSamples = 3,
        )
        return res.hits to res.bytes
    }

    /**
     * 把扫描根切成分片：每个 root 的直接子项 = 1 个分片（0 层 IDM 分片）。
     * root 自身无子项时，root 作为唯一分片。
     */
    private fun buildShards(roots: List<File>): List<File> {
        val shards = ArrayList<File>()
        for (r in roots) {
            val children = r.listFiles()
            if (children == null || children.isEmpty()) shards.add(r)
            else for (c in children) shards.add(c)
        }
        return shards
    }

    // ------------------------------------------------------------------
    // 深度扫描（文件头识别 + 分片缓存增量）
    // ------------------------------------------------------------------

    private fun isDeepMode(m: MatchMode): Boolean = when (m) {
        MatchMode.APK_SCAN, MatchMode.BIG_FILE_SCAN,
        MatchMode.DUP_SAME_SIZE, MatchMode.DUP_CONTENT, MatchMode.JUNK_SCAN -> true
        else -> false
    }

    private data class DeepResult(val files: Int, val bytes: Long, val samples: List<String>)

    /**
     * 深度扫描入口。分片并行 + 分片级缓存复用（首次全扫，之后未变分片直接复用）。
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
     * 泛化垃圾扫描：分片并行 + 分片级缓存（IDM 风格）。
     *
     * 命中 [JunkPatterns] 特征的文件与空目录计入结果；遍历/删除均受
     * [RubbishGuard] 与 [JunkPatterns.isProtectedPath] 保护。
     */
    private fun scanJunk(rule: CleanRule, roots: List<File>, maxSamples: Int): DeepResult {
        val idx = cache.load(rule.id)
        lastScanRule = rule.id
        lastScanDone = 0
        lastScanTotal = 0
        val visitor = JunkVisitor(rule, idx)
        val shards = buildShards(roots)
        val res = scanner.scan(
            shards = shards,
            idx = idx,
            cache = cache,
            ruleId = rule.id,
            visitor = visitor,
            maxSamples = maxSamples,
            onProgress = { d, t ->
                lastScanDone = d
                lastScanTotal = t
                RubbishProgress.onShard(d, t)
                if (d < shards.size) RubbishProgress.onPath(shards[d].path)
            },
        )
        Logger.i(
            "RubbishCleaner",
            "垃圾扫描完成(${rule.id}): 命中=${res.hits} 字节=${res.bytes} " +
                "分片=${lastScanDone}/${lastScanTotal} 复用=${res.reusedShards}",
        )
        return DeepResult(res.hits, res.bytes, res.samples)
    }

    /**
     * 泛化垃圾扫描访问器。
     *  - 文件级增量：`isFresh` 命中 → 复用缓存判定，不重复读文件头；
     *  - 所有判定受 [RubbishGuard.isForbiddenPath] 与 [JunkPatterns.isProtectedPath] 保护；
     *  - 删除永不在此处发生。
     */
    private inner class JunkVisitor(
        private val rule: CleanRule,
        private val idx: ScanCache.Index,
    ) : ParallelScanner.Visitor {

        override fun onFile(file: File, parentDirName: String, shard: ScanCache.Shard): Boolean {
            val size = file.length()
            // ★ ageDays 过滤：只清理 N 天以上未修改的文件（防碰到活跃句柄）。
            if (rule.ageDays > 0 && !olderThan(file, rule.ageDays)) return false
            val mtime = file.lastModified()
            val fresh = idx.isFresh(file.path, size, mtime)
            return if (fresh) {
                idx.get(file.path)?.kind == 'J'
            } else {
                val j = JunkPatterns.isJunk(file.name, parentDirName, file, size)
                // ★ 只落盘「命中(J)」条目：非命中(O)数量巨大（数十万），
                //    且分片级聚合已能整体复用未变分片，O 条目纯属体积放大。
                //    未变分片整片跳过；变动分片内重扫其文件（成本=单个 App 子树）。
                if (j) shard.addEntry(ScanCache.Entry('J', file.path, size, mtime))
                j
            }
        }

        override fun shouldSkip(child: File, parent: File): Boolean {
            if (child.name in rule.keep) return true
            val path = child.path
            if (RubbishGuard.isForbiddenPath(path)) return true
            val lower = try { path.lowercase() } catch (_: Throwable) { "" }
            if (lower.isNotEmpty() && JunkPatterns.isProtectedPath(lower)) return true
            // 深度限制：避免遍历到过深层
            if (rule.maxDepth > 0 && path.split('/').size > rule.maxDepth) return true
            return false
        }
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
        // 小范围走单线程（避免为几十个文件开线程池）；大范围走分片并行。
        return collectFilesParallel(rule, roots, minBytes)
    }

    /**
     * 分片并行收集候选文件（IDM 风格）。
     *
     * 与递归串行版语义一致：跳过 keep/禁止路径，只收 ≥minBytes 的普通文件；
     * 但把顶层子树切成分片并发遍历，显著加快大范围收集。
     * 收集阶段**不使用缓存**（需全量，且无需增量）。
     */
    private fun collectFilesParallel(rule: CleanRule, roots: List<File>, minBytes: Long): List<File> {
        val out = java.util.Collections.synchronizedList(ArrayList<File>(1024))
        val visitor = object : ParallelScanner.Visitor {
            override fun onFile(file: File, parentDirName: String, shard: ScanCache.Shard): Boolean {
                if (file.length() >= minBytes) {
                    if (!RubbishGuard.isForbiddenPath(file.path)) out.add(file)
                }
                return false
            }
            override fun shouldSkip(child: File, parent: File): Boolean {
                if (child.name in rule.keep) return true
                val path = child.path
                if (RubbishGuard.isForbiddenPath(path)) return true
                if (rule.maxDepth > 0 && path.split('/').size > rule.maxDepth) return true
                return false
            }
        }
        val shards = buildShards(roots)
        // 用独立的一次性缓存，避免污染规则索引
        val tmpCache = ScanCache(File(config.rootDir, "rubbish_index_tmp"))
        scanner.scan(
            shards = shards,
            idx = ScanCache.Index(),
            cache = tmpCache,
            ruleId = "__collect_${rule.id}",
            visitor = visitor,
            maxSamples = 0,
            onProgress = { d, t ->
                lastScanDone = d
                lastScanTotal = t
                RubbishProgress.onShard(d, t)
                if (d < shards.size) RubbishProgress.onPath(shards[d].path)
            },
        )
        return synchronized(out) { ArrayList(out) }
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
        if (alive.isEmpty()) return
        val dead = idx.entries.keys.filter { it !in alive }
        dead.forEach { idx.entries.remove(it) }
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
            // ★ 目录（空目录）：删除前再确认一次「仍为空」，防 TOCTOU 竞态误删刚写入的内容。
            if (v.isDirectory) {
                val children = v.listFiles()
                if (children == null || children.isNotEmpty()) continue
                // 空目录经 safeDelete 走 deleteTree，此时已确认无子项，安全。
                val r = RubbishGuard.safeDelete(v.path, rule.id)
                files += r.deletedFiles
                bytes += r.deletedBytes
                rejected += r.rejected
                continue
            }
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
            RubbishProgress.enterRule(rule.id, rule.name)
            val skip = shouldSkipForRunning(rule)
            if (skip != null) {
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, skip, emptyList(), listOnly = rule.listOnly)
                RubbishProgress.finishRule()
                continue
            }
            // 只读规则（listOnly）：clean() 绝不删除，直接以空结果返回。
            // 这是所有模式（含 OLDER_THAN/GLOB/DIR_*）的统一兜底，避免仅靠
            // cleanDeep 分支检查而遗漏普通模式。
            if (rule.listOnly) {
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, 0, 0L, true, "仅列出（不删除）", emptyList(), listOnly = true)
                RubbishProgress.finishRule()
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
                RubbishProgress.addHits(files.toLong(), bytes)
                RubbishProgress.finishRule()
                continue
            }

            // 卸载残留：运行时逐个校验包是否仍安装，仅删确认已卸载的。
            if (rule.mode == MatchMode.UNINSTALLED_SCAN) {
                val leftovers = findUninstalledLeftovers()
                if (samples.size < maxSamples) leftovers.take(maxSamples).forEach { samples += it.path }
                for (dir in leftovers) {
                    // 二次校验：删除前再确认一次包仍未安装（防 TOCTOU）。
                    val pkg = dir.name
                    if (pkg in AppListProvider.allPackages().toSet()) continue
                    val r = RubbishGuard.safeDelete(dir.path, rule.id)
                    files += r.deletedFiles
                    bytes += r.deletedBytes
                    rejected += r.rejected
                }
                results += RuleResult(rule.id, rule.name, rule.group.key, rule.risk, files, bytes, false, "", samples, rejected)
                totalFiles += files
                totalBytes += bytes
                RubbishProgress.addHits(files.toLong(), bytes)
                RubbishProgress.finishRule()
                continue
            }

            for (userId in mediaUserIds()) {
                for (root in expandRoots(rule, userId)) {
                    for (target in resolveTargets(rule, root)) {
                        RubbishProgress.onPath(target.path)
                        if (samples.size < maxSamples) samples += target.path
                        val res: RubbishGuard.DeleteResult = when (rule.mode) {
                            MatchMode.DIR_CONTENT -> RubbishGuard.safeCleanDirContents(
                                target.path,
                                rule.id,
                                // app_cache：部分应用把用户配置放进 cache/ 子目录
                                //（如 AGC 相机把滤镜配置放在 cache/sdcard/configs/），
                                // 因此只清直接子文件，不递归子目录，避免误删真实数据。
                                filesOnly = rule.id == "app_cache",
                            )
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
            RubbishProgress.addHits(files.toLong(), bytes)
            RubbishProgress.finishRule()
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
        // 内建规则 + 在线规则 + 用户本地规则（数据驱动，只增不删）。
        // 合并顺序：内建优先，其后为在线/用户规则；id 前缀已保证不冲突。
        val all = allRulesMerged()
        if (ruleIds.isEmpty()) {
            // 空选择 = 全部「非内建开关控制」的规则：内建按 switchKey 开关，
            // 在线/用户规则的 switchKey 为空（随总开关），因此一并纳入。
            return all.filter { it.switchKey.isEmpty() || config.switch(it.switchKey) }
        }
        val set = ruleIds.toSet()
        return all.filter { it.id in set }
    }

    /**
     * 内建 + 在线 + 用户规则合并表（结果缓存 5 秒，避免每规则/每预览重复读盘）。
     *
     * 在线/用户规则来自 [OnlineRuleStore]；解析失败或缺失时静默跳过（不影响内建）。
     */
    private fun allRulesMerged(): List<CleanRule> {
        val now = System.currentTimeMillis()
        val cached = mergedCache
        if (cached != null && now - mergedCacheAt < 5000L) return cached
        val merged = ArrayList<CleanRule>(RubbishRuleSet.ALL.size + 16)
        merged += RubbishRuleSet.ALL
        try {
            val extra = OnlineRuleStore.allRules(File(config.rootDir))
            if (extra.isNotEmpty()) {
                // 去重：按 id 与 (roots+mode+name) 双重判定，避免重复规则重复扫描。
                val seen = merged.map { it.id }.toHashSet()
                val sig = merged.map { ruleSignature(it) }.toHashSet()
                for (r in extra) {
                    if (r.id in seen) continue
                    val s = ruleSignature(r)
                    if (s in sig) continue
                    seen += r.id
                    sig += s
                    merged += r
                }
            }
        } catch (t: Throwable) {
            Logger.w("RubbishCleaner", "在线/用户规则合并失败: ${t.message}")
        }
        mergedCache = merged
        mergedCacheAt = now
        return merged
    }

    /** 规则签名：用于跨来源去重（同 root+mode+pattern 视为同一规则）。 */
    private fun ruleSignature(r: CleanRule): String =
        r.mode.name + "|" + r.roots.joinToString(",") + "|" + r.pattern

    @Volatile
    private var mergedCache: List<CleanRule>? = null

    @Volatile
    private var mergedCacheAt: Long = 0L

    /** 使合并缓存失效（在线规则更新/保存用户规则后调用）。 */
    fun invalidateRuleCache() {
        mergedCache = null
        mergedCacheAt = 0L
    }

    /** 选中规则数（供进度显示总数；不触发任何扫描）。 */
    fun previewRuleCount(ruleIds: List<String>): Int = selectRules(ruleIds).size

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
        var scanned = 0
        var skippedInstalled = 0
        var skippedProtected = 0
        for (uid in mediaUserIds()) {
            val extData = File("/data/media/$uid/Android/data")
            val list = extData.listFiles() ?: continue
            for (d in list) {
                scanned++
                if (!d.isDirectory) continue
                val name = d.name
                // 必须是形如包名的目录；必须确认该包当前**未安装**。
                if (!name.contains('.')) continue
                if (name.startsWith(".")) continue
                if (name in installed) {
                    skippedInstalled++
                    continue
                }
                // 二次防护：绝不把系统关键包/模块相关目录当残留。
                if (name.startsWith("com.android.") || name.startsWith("android")) {
                    skippedProtected++
                    continue
                }
                if (name.startsWith("com.google.android.")) {
                    skippedProtected++
                    continue
                }
                result += d
            }
        }
        Logger.i(
            "RubbishCleaner",
            "卸载残留判定: 扫描=$scanned 已安装=$skippedInstalled 受保护=$skippedProtected 候选=${result.size}",
        )
        return result
    }

    /** 递归统计目录占用字节数（仅用于扫描展示，不做删除）。 */
    private fun dirSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 200000) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.addLast(c) else total += c.length()
            }
        }
        return total
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
                    key("listOnly"); value(r.listOnly); comma()
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
        // 列出「内建 + 在线 + 用户」合并后的全部规则，并标注来源，
        // 便于 WebUI 区分展示（在线/用户规则由数据驱动，可随时增删）。
        key("rules"); raw(JsonBuilder.arr {
            allRulesMerged().forEachIndexed { i, r ->
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
                    key("mode"); value(r.mode.name); comma()
                    key("source"); value(ruleSource(r.id)); comma()
                    key("note"); value(r.note)
                })
            }
        })
    }

    /**
     * 规则来源：internal（内建 RubbishRuleSet）/ online（在线订阅）/ user（本地用户规则）。
     * 依据 id 前缀判定——在线规则 `ol_`、用户规则 `ur_`（见 [OnlineRuleStore]）。
     */
    private fun ruleSource(id: String): String = when {
        id.startsWith("ol_") -> "online"
        id.startsWith("ur_") -> "user"
        else -> "internal"
    }

    /**
     * 导出合并规则为 [RuleDoc.Group] 列表（供 WebUI 规则编辑器展示/导出 JSON）。
     *
     * 覆盖内建 + 在线 + 用户三来源；`mode`/`risk`/`roots`/`keep` 等完整保留，
     * 使导出的 JSON 再导入后可等价还原（schema 见 [RuleDocCodec]）。
     */
    fun exportGroups(): List<RuleDoc.Group> =
        allRulesMerged().map { r ->
            RuleDoc.Group(
                name = r.name,
                enabled = true,
                mode = r.mode,
                risk = r.risk,
                defaultOn = r.defaultOn,
                roots = r.roots,
                pattern = r.pattern,
                ageDays = r.ageDays,
                keep = r.keep,
                note = r.note,
                uiGroup = r.group,
            )
        }
}