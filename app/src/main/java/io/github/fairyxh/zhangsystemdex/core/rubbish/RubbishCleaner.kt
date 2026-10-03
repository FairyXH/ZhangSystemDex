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
        val samples = ArrayList<String>()
        var files = 0
        var bytes = 0L
        for (userId in mediaUserIds()) {
            for (root in expandRoots(rule, userId)) {
                for (target in resolveTargets(root)) {
                    if (samples.size < maxSamples) samples += target.path
                    if (target.isDirectory) {
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

            for (userId in mediaUserIds()) {
                for (root in expandRoots(rule, userId)) {
                    for (target in resolveTargets(root)) {
                        if (samples.size < maxSamples) samples += target.path
                        val res: RubbishGuard.DeleteResult = when (rule.mode) {
                            MatchMode.DIR_CONTENT -> RubbishGuard.safeCleanDirContents(target.path, rule.id)
                            MatchMode.OLDER_THAN -> cleanOlderThan(target, rule)
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

    private fun resolveTargets(root: String): List<File> {
        if (root.indexOf('*') < 0) {
            val f = File(root)
            return if (f.exists()) listOf(f) else emptyList()
        }
        return expandWildcard(root)
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