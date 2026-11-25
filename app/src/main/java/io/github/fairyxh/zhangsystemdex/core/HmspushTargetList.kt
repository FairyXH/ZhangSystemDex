package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * 增量目标列表引擎：负责 tricky_store / hmspush 的 target 列表增量维护。
 *
 * 语义来源：母版 Main.dex（MD5 1f20bddcdf820f3f147a7df1e6d999ce）反编译 smali 逐行还原。
 * 对应 smali 行号在每处关键逻辑上标注，便于回查。
 */
object IncrementalTargetList {

    /** 墓碑保留期：90 天。smali: .field REMOVED_TTL_DAYS:J = 0x5aL */
    private const val REMOVED_TTL_DAYS = 90L

    /** 90 天毫秒数，smali line 188 常量 0x1cf7c5800L */
    private const val REMOVED_TTL_MS = REMOVED_TTL_DAYS * 24L * 60L * 60L * 1000L

    /**
     * 白名单文件默认头部。smali line 301-305，buildString 三段拼接，非 listOf。
     */
    private val WHITELIST_HEADER: String = buildString {
        append("# hmspush 白名单：一行一个包名。\n")
        append("# 白名单中的应用不会被写入目标列表；每轮轮询发现即移除。\n")
        append("# 以 # 开头为注释；支持行内注释（pkg # 备注）。\n")
    }

    /**
     * 目标列表配置项。smali: IncrementalTargetList$Spec 字段顺序 83-113。
     */
    data class Spec(
        val dir: File,
        val confName: String,
        val knownName: String,
        val removedName: String?,
        val whitelistName: String?,
        val header: List<String> = emptyList(),
        val seed: List<String> = emptyList(),
        val tag: String = "IncrementalTargetList"
    ) {
        /** smali line 451 */
        val conf: File get() = File(dir, confName)
        /** smali line 855 */
        val known: File get() = File(dir, knownName)
        /** smali line 870 */
        val removed: File? get() = removedName?.let { File(dir, it) }
        /** smali line 1017 */
        val whitelist: File? get() = whitelistName?.let { File(dir, it) }
    }

    /**
     * 同步结果。smali: IncrementalTargetList$SyncResult 字段顺序 67-99。
     */
    data class SyncResult(
        val added: List<String>,
        val removedByWhitelist: List<String>,
        val tombstoned: List<String>,
        val dedupedLines: Int,
        val total: Int
    ) {
        /** smali getChanged(): line 429，四条件取并集 */
        val changed: Boolean
            get() = added.isNotEmpty() ||
                removedByWhitelist.isNotEmpty() ||
                dedupedLines > 0 ||
                tombstoned.isNotEmpty()
    }

    // ---------------------------------------------------------------- 基础 IO

    /**
     * 原子写：先写 .tmp 再 rename；rename 失败则直接覆盖并删 tmp。
     * smali line 127-142。
     */
    private fun atomicWrite(target: File, text: String): Boolean {
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(target)) {
                true
            } else {
                target.writeText(text)
                tmp.delete()
                true
            }
        } catch (t: Throwable) {
            Logger.w("IncrementalTargetList", "写入 ${target.path} 失败: ${t.message}")
            false
        }
    }

    /**
     * 解析文本为有序去重的合法包名列表。
     * smali line 75-82。用 OomProtectList.isValidPackage 校验。
     */
    fun parsePackages(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) continue
            val pkg = trimmed.substringBefore('#').trim()
            if (OomProtectList.isValidPackage(pkg)) {
                out.add(pkg)
            }
        }
        return out.toList()
    }

    /**
     * 读取文件并解析包名。文件不存在返回空。
     * smali line 86-91。
     */
    fun readPackages(f: File): List<String> {
        if (!f.exists()) return emptyList()
        return try {
            parsePackages(f.readText())
        } catch (t: Throwable) {
            Logger.w("IncrementalTargetList", "读取 ${f.path} 失败: ${t.message}")
            emptyList()
        }
    }

    /**
     * 读取白名单集合。whitelist File 为 null 或文件不存在返回空集。
     * smali line 96-102，异常日志用 spec.tag。
     */
    fun readWhitelist(spec: Spec): Set<String> {
        val f = spec.whitelist ?: return emptySet()
        if (!f.exists()) return emptySet()
        return try {
            parsePackages(f.readText()).toSet()
        } catch (t: Throwable) {
            Logger.w(spec.tag, "读取白名单失败: ${t.message}")
            emptySet()
        }
    }

    /**
     * 读取墓碑映射 pkg -> 时间戳。空文件/不存在返回空 map。
     * smali line 107-121，格式 ts|pkg。
     */
    private fun readRemoved(f: File?): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        if (f == null || !f.exists()) return out
        return try {
            for (line in f.readText().lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) continue
                val ts = trimmed.substringBefore('|').toLongOrNull()
                val pkg = trimmed.substringAfter('|', "").trim()
                if (ts != null && OomProtectList.isValidPackage(pkg)) {
                    out[pkg] = ts
                }
            }
            out
        } catch (t: Throwable) {
            Logger.w("IncrementalTargetList", "读取墓碑失败: ${t.message}")
            out
        }
    }

    /**
     * 读取墓碑包名列表，排序返回。
     * smali line 324。
     */
    fun readRemovedPackages(spec: Spec): List<String> =
        readRemoved(spec.removed).keys.toList().sorted()

    // ---------------------------------------------------------------- 渲染

    /**
     * 渲染 known 快照文件。
     * smali line 295-298：固定头 + 排序后每行一个包名。
     */
    private fun renderKnown(packages: Collection<String>): String = buildString {
        append("# 已处理快照：由 ZhangSystemDex 自动维护，请勿手工编辑。\n")
        for (p in packages.sorted()) {
            append(p)
            append('\n')
        }
    }

    /**
     * 渲染墓碑文件。
     * smali line 289-293：两行头 + 每行 `ts|pkg`。保持 map 插入顺序，不排序。
     */
    private fun renderRemoved(map: Map<String, Long>): String = buildString {
        append("# 墓碑记录：用户主动从列表删除过的包名（ts|pkg）。\n")
        append("# 这些包不会被自动补入；应用卸载超过 90 天后该条自动丢弃。\n")
        for ((pkg, ts) in map) {
            append(ts)
            append('|')
            append(pkg)
            append('\n')
        }
    }

    // ---------------------------------------------------------------- 重建

    /**
     * 重建目标列表文本。
     * smali line 247-286。
     *
     * 行为细节（易被“更合理”的写法改坏，逐条对应 smali）：
     * - 注释行按 trimEnd 后原文保留，不重新缩进
     * - 只有 existing 中已存在的包名才会被保留（不在 existing 的包名被丢弃）
     * - 行内注释用 "  # " 分隔（两个空格 + # + 一个空格）
     * - sawEntry 为 false 时才追加 header 与 seed
     * - 返回 Pair(text, deduped 重复行数)
     */
    private fun rebuild(
        spec: Spec,
        text: String,
        existing: Set<String>,
        append: List<String>
    ): Pair<String, Int> {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        var deduped = 0
        var sawEntry = false

        for (line in text.lineSequence()) {
            val raw = line.trimEnd()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#")) {
                sb.append(raw).append('\n')
                continue
            }

            val pkg = trimmed.substringBefore('#').trim()
            val inline = trimmed.substringAfter('#', "").trim()

            if (!OomProtectList.isValidPackage(pkg)) continue
            if (!existing.contains(pkg)) continue

            if (!seen.add(pkg)) {
                deduped++
                continue
            }

            sawEntry = true
            if (inline.isEmpty()) {
                sb.append(pkg).append('\n')
            } else {
                sb.append(pkg).append("  # ").append(inline).append('\n')
            }
        }

        if (!sawEntry) {
            for (h in spec.header) {
                sb.append(h).append('\n')
            }
            for (s in spec.seed) {
                if (seen.add(s)) {
                    sb.append(s).append('\n')
                }
            }
        }

        for (p in append) {
            if (seen.add(p)) {
                sb.append(p).append('\n')
            }
        }

        return sb.toString() to deduped
    }

    // ---------------------------------------------------------------- 同步主流程

    /**
     * 增量同步。
     * smali line 155-229。
     *
     * @param candidates 期望进入目标列表的包名（已安装且需要处理）
     * @param installedPackages 当前实际已安装的包名
     */
    fun sync(
        spec: Spec,
        candidates: Collection<String>,
        installedPackages: Collection<String>
    ): SyncResult {
        spec.dir.mkdirs()

        val conf = spec.conf
        val known = spec.known
        val removedFile = spec.removed
        val whitelistFile = spec.whitelist

        // line 163-164：白名单文件不存在则写入默认头
        if (whitelistFile != null && !whitelistFile.exists()) {
            atomicWrite(whitelistFile, WHITELIST_HEADER)
        }

        // line 167-169：读目标列表，异常吞掉当空文本
        val confText = if (conf.exists()) {
            try {
                conf.readText()
            } catch (t: Throwable) {
                ""
            }
        } else {
            ""
        }

        val existing: List<String> = parsePackages(confText)          // line 170
        val knownSet: MutableSet<String> = readPackages(known).toMutableSet() // line 171
        val whitelist: Set<String> = readWhitelist(spec)              // line 172
        val removedMap: MutableMap<String, Long> = readRemoved(removedFile)   // line 173
        val installed: HashSet<String> = installedPackages.toHashSet() // line 174

        val existingSet: HashSet<String> = existing.toHashSet()        // line 177
        val tombstoned = ArrayList<String>()                          // line 178

        // line 179-185：known 里有、但已卸载且不在白名单/墓碑 → 补墓碑
        for (pkg in knownSet) {
            if (installed.contains(pkg)) continue
            if (whitelist.contains(pkg)) continue
            if (removedMap.containsKey(pkg)) continue
            removedMap[pkg] = System.currentTimeMillis()              // line 183
            tombstoned.add(pkg)                                        // line 184
        }

        val now = System.currentTimeMillis()                          // line 187

        // line 188-191：计算过期墓碑 —— 已卸载且超过 TTL。
        // smali 局部变量名为 expired，lambda 名 sync$expired$1，语义是只删过期键。
        val expired = removedMap
            .filter { (pkg, ts) -> !(installed.contains(pkg) || now - ts <= REMOVED_TTL_MS) }
            .keys.toList()
        for (k in expired) {
            removedMap.remove(k)
        }

        // line 192-193：墓碑有变化（新增墓碑或发生 TTL 丢弃）才落盘
        if (tombstoned.isNotEmpty() || expired.isNotEmpty()) {
            removedFile?.let { atomicWrite(it, renderRemoved(removedMap)) }
        }

        // line 197：白名单命中的现存条目
        val toRemove = existing.filter { whitelist.contains(it) }

        // line 198-201：从现有集合中去掉白名单项
        val base: Collection<String> =
            if (toRemove.isEmpty()) existing else existing.minus(toRemove.toSet())

        // line 201-210：候选增量
        val baseSet: HashSet<String> = base.toHashSet()
        val toAdd = ArrayList<String>()
        for (pkg in candidates) {
            if (!OomProtectList.isValidPackage(pkg)) continue
            if (baseSet.contains(pkg)) continue
            if (whitelist.contains(pkg)) continue
            if (removedMap.containsKey(pkg)) continue
            toAdd.add(pkg)
            baseSet.add(pkg)                                          // line 209
        }

        val sortedAdd = toAdd.sorted()                                // line 211

        // line 214：重建
        val rebuilt = rebuild(spec, confText, baseSet, sortedAdd)
        val newText = rebuilt.first
        val deduped = rebuilt.second

        // line 215-216：无变化且文件已存在 → 跳过写
        val needWrite = !(toRemove.isEmpty() && toAdd.isEmpty() &&
            deduped <= 0 && conf.exists())

        // line 217-219：写入失败直接返回，added 置空
        if (needWrite) {
            if (!atomicWrite(conf, newText)) {
                return SyncResult(
                    added = emptyList(),
                    removedByWhitelist = toRemove,
                    tombstoned = tombstoned,
                    dedupedLines = deduped,
                    total = baseSet.size
                )
            }
        }

        // line 222：known 快照
        val knownSnapshot: HashSet<String> = knownSet.toHashSet()

        // line 223：并入 base
        knownSet.addAll(baseSet)

        // line 224 + sync$lambda$3：剔除「已卸载且不在 base」的项
        knownSet.removeAll { pkg -> !installed.contains(pkg) && !baseSet.contains(pkg) }

        // line 225-226：快照变化才写
        if (knownSet != knownSnapshot) {
            atomicWrite(known, renderKnown(knownSet))
        }

        // line 229
        return SyncResult(
            added = sortedAdd,
            removedByWhitelist = toRemove,
            tombstoned = tombstoned,
            dedupedLines = deduped,
            total = baseSet.size
        )
    }

    // ---------------------------------------------------------------- 白名单 / 墓碑操作

    /**
     * 写白名单。
     * smali line 309-314：whitelist File 为 null 返回 false；写入 header + 每行包名。
     */
    fun writeWhitelist(spec: Spec, packages: List<String>): Boolean {
        val f = spec.whitelist ?: return false
        val text = buildString {
            append(WHITELIST_HEADER)
            for (p in packages) {
                append(p)
                append('\n')
            }
        }
        return atomicWrite(f, text)
    }

    /**
     * 清除所有墓碑（全部还原）。
     * smali line 378-380：无墓碑返回空；否则等价于 restoreRemoved(全部)。
     */
    fun clearRemoved(spec: Spec): List<String> {
        val all = readRemovedPackages(spec)
        if (all.isEmpty()) return emptyList()
        return restoreRemoved(spec, all)
    }

    /**
     * 从墓碑中还原指定包：移出墓碑，并把「目标列表里还没有的」补回去。
     * smali line 336-362。
     */
    fun restoreRemoved(spec: Spec, packages: Collection<String>): List<String> {
        spec.dir.mkdirs()                                             // line 336
        val removedFile = spec.removed                                // line 337
        val removedMap = readRemoved(removedFile)                     // line 338

        val toRestore = LinkedHashSet<String>()                       // line 340
        for (pkg in packages) {                                       // line 341
            if (!OomProtectList.isValidPackage(pkg)) continue         // line 342
            if (!removedMap.containsKey(pkg)) continue                // line 343
            toRestore.add(pkg)
        }

        if (toRestore.isEmpty()) return emptyList()                   // line 345

        for (pkg in toRestore) {                                      // line 348
            removedMap.remove(pkg)
        }
        removedFile?.let { atomicWrite(it, renderRemoved(removedMap)) } // line 349

        val conf = spec.conf                                          // line 352
        val confText: String = if (conf.exists()) {                   // line 353-355
            try {
                conf.readText()
            } catch (t: Throwable) {
                ""
            }
        } else {
            ""
        }

        val existing: HashSet<String> = parsePackages(confText).toHashSet() // line 356
        val toAppend = toRestore.filter { !existing.contains(it) }    // line 357

        if (toAppend.isNotEmpty()) {                                  // line 358
            val newText = rebuild(spec, confText, existing, toAppend).first // line 359
            atomicWrite(conf, newText)                                // line 360
        }

        return toRestore.toList()                                     // line 362
    }
}
/**
 * hmspush 目标列表：/data/adb/hmspush/app.conf
 *
 * smali 来源：HmspushTargetList.smali（class 行 390，spec 行 405-417）
 */
object HmspushTargetList {

    const val CONF_NAME = "app.conf"
    const val KNOWN_NAME = "app.known"
    const val REMOVED_NAME = "app.removed"
    const val WHITELIST_NAME = "app.whitelist"

    /** smali line 403，正则原文逐字一致 */
    private val EXCLUDE_PATTERN = Regex(
        "com\\.tencent\\.mm|youqu\\.android\\.todesk|wallet|oplus|oppo|xiaomi|hyperos"
    )

    /** smali 行 405-417：seed 走默认空列表（构造器掩码 0x40） */
    val spec = IncrementalTargetList.Spec(
        dir = File("/data/adb/hmspush"),
        confName = CONF_NAME,
        knownName = KNOWN_NAME,
        removedName = REMOVED_NAME,
        whitelistName = WHITELIST_NAME,
        header = listOf(
            "# hmspush 目标列表：一行一个包名，由 ZhangSystemDex 自动维护。",
            "# 手动删除某一行 = 不需要伪装该应用，此后不再自动补入。",
            "# 手动新增某一行 = 立即生效，轮询不会改动它。"
        ),
        tag = "HmspushTargetList"
    )

    /** smali line 423：用内置 spec */
    fun sync(candidates: Collection<String>, installedPackages: Collection<String>) =
        IncrementalTargetList.sync(spec, candidates, installedPackages)

    /** smali line 426 */
    fun readWhitelist(rootDir: File): Set<String> =
        IncrementalTargetList.readWhitelist(specFor(rootDir))

    /**
     * smali line 429：这里**不走 specFor**，而是硬编码 app.conf。
     * 这是母版既有行为，不要"顺手修正"。
     */
    fun readPackages(rootDir: File): List<String> =
        IncrementalTargetList.readPackages(File(rootDir, CONF_NAME))

    /** smali line 432 */
    fun writeWhitelist(rootDir: File, packages: List<String>): Boolean =
        IncrementalTargetList.writeWhitelist(specFor(rootDir), packages)

    /** smali line 435：copy$default 掩码 0xfe，只替换 dir */
    fun specFor(rootDir: File): IncrementalTargetList.Spec = spec.copy(dir = rootDir)

    /** smali line 439 */
    fun readRemoved(rootDir: File): List<String> =
        IncrementalTargetList.readRemovedPackages(specFor(rootDir))

    /** smali line 443 */
    fun restoreRemoved(rootDir: File, packages: Collection<String>): List<String> =
        IncrementalTargetList.restoreRemoved(specFor(rootDir), packages)

    /** smali line 447 */
    fun clearRemoved(rootDir: File): List<String> =
        IncrementalTargetList.clearRemoved(specFor(rootDir))

    /** smali line 449 */
    fun isExcluded(pkg: String): Boolean = EXCLUDE_PATTERN.containsMatchIn(pkg)
}

/**
 * tricky_store 目标列表：/data/adb/tricky_store/target.txt
 *
 * smali 来源：TrickyStoreTargetList.smali（class 行 458，spec 行 464-475）
 */
object TrickyStoreTargetList {

    const val CONF_NAME = "target.txt"
    const val KNOWN_NAME = "target.known"
    const val REMOVED_NAME = "target.removed"

    /** smali 行 464-475：whitelistName 为 null；seed 固定 gms */
    val spec = IncrementalTargetList.Spec(
        dir = File("/data/adb/tricky_store"),
        confName = CONF_NAME,
        knownName = KNOWN_NAME,
        removedName = REMOVED_NAME,
        whitelistName = null,
        header = listOf(
            "# tricky_store 目标列表：一行一个包名，由 ZhangSystemDex 自动维护。",
            "# 手动删除某一行后，该包不再被自动补入。"
        ),
        seed = listOf("com.google.android.gms"),
        tag = "TrickyStoreTargetList"
    )

    /**
     * smali line 480：把 candidates 同时当作 candidates 与 installedPackages 传入。
     * 母版就这么写，tricky_store 侧不区分"已安装"与"候选"。
     */
    fun sync(candidates: Collection<String>) =
        IncrementalTargetList.sync(spec, candidates, candidates)

    /** smali line 482：copy$default 掩码 0xfe，只替换 dir */
    fun specFor(rootDir: File): IncrementalTargetList.Spec = spec.copy(dir = rootDir)
}