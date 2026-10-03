package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 深度扫描结果缓存（首次全扫，之后仅扫变动位置）。
 *
 * 设计：为每条深度扫描规则保存一份「文件索引」快照，记录
 * `路径 \t 大小 \t mtime \t 判定结果`。下次扫描时：
 *  - 目录自身 mtime 未变的子目录 → 直接复用缓存条目；
 *  - 新增/变更的文件 → 重新判定（读文件头/哈希）；
 *  - 已消失的路径 → 从缓存剔除。
 *
 * 存储：`<rootDir>/cache/rubbish_index/<ruleId>.idx`。
 * 格式为 UTF-8 文本，每行：`kind\tpath\tsize\tmtime`，kind ∈ {A=APK, Z=ZIP, O=OTHER, D=重复保留组}。
 *
 * 注意：缓存只是「加速」手段，**任何删除仍必须经 [RubbishGuard]**，
 * 且删除前会重新校验路径合法性（缓存不构成信任边界）。
 */
class ScanCache(private val rootDir: File) {

    /** 单条索引项。 */
    data class Entry(
        val kind: Char,
        val path: String,
        val size: Long,
        val mtime: Long,
    )

    /** 一条规则的索引。 */
    class Index {
        val entries = LinkedHashMap<String, Entry>()

        /** 目录 mtime 快照（相对路径 -> mtime），用于目录级增量判定。 */
        val dirStamps = HashMap<String, Long>()

        fun put(e: Entry) { entries[e.path] = e }

        fun remove(path: String) { entries.remove(path) }

        fun get(path: String): Entry? = entries[path]

        /** 是否与磁盘现状一致（size + mtime 均未变）。 */
        fun isFresh(path: String, size: Long, mtime: Long): Boolean {
            val e = entries[path] ?: return false
            return e.size == size && e.mtime == mtime
        }

        fun dirFresh(dirPath: String, mtime: Long): Boolean =
            dirStamps[dirPath] == mtime

        fun stampDir(dirPath: String, mtime: Long) { dirStamps[dirPath] = mtime }
    }

    private val dir: File = File(rootDir, "rubbish_index")

    fun indexPath(ruleId: String): File = File(dir, "$ruleId.idx")

    /** 载入索引；不存在或损坏时返回空索引。 */
    fun load(ruleId: String): Index {
        val idx = Index()
        val f = indexPath(ruleId)
        if (!f.exists()) return idx
        try {
            f.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val kind = parts[0].firstOrNull() ?: 'O'
                    val size = parts[2].toLongOrNull() ?: 0L
                    val mtime = parts[3].toLongOrNull() ?: 0L
                    if (parts[1].startsWith("DIR:")) {
                        idx.dirStamps[parts[1].substring(4)] = mtime
                    } else {
                        idx.put(Entry(kind, parts[1], size, mtime))
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w("ScanCache", "索引载入失败($ruleId): ${t.message}")
        }
        return idx
    }

    /** 保存索引（原子替换）。 */
    fun save(ruleId: String, idx: Index) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$ruleId.idx.tmp")
            val sb = StringBuilder()
            for (d in idx.dirStamps) {
                sb.append("D\tDIR:").append(d.key).append('\t').append(0).append('\t').append(d.value).append('\n')
            }
            for (e in idx.entries.values) {
                sb.append(e.kind).append('\t').append(e.path).append('\t')
                    .append(e.size).append('\t').append(e.mtime).append('\n')
            }
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            val target = indexPath(ruleId)
            if (!tmp.renameTo(target)) {
                target.writeText(sb.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Logger.w("ScanCache", "索引保存失败($ruleId): ${t.message}")
        }
    }

    /** 清空某条规则的索引（强制下次全扫）。 */
    fun invalidate(ruleId: String) {
        try { indexPath(ruleId).delete() } catch (_: Throwable) {}
    }

    /** 清空全部索引。 */
    fun invalidateAll() {
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    fun sizeBytes(): Long =
        try { dir.listFiles()?.sumOf { it.length() } ?: 0L } catch (_: Throwable) { 0L }

    /** 便捷：读取文件 size/mtime。 */
    fun stat(f: File): LongArray = longArrayOf(f.length(), f.lastModified())
}