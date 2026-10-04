package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * 深度扫描结果缓存（IDM 风格的**分片级**缓存）。
 *
 * ## 存什么
 * - **分片聚合** `s`：每个「分片根目录」的子树总量（命中数/字节）与其 mtime。
 *   分片数上限 = 各扫描根的直接子项数（几百~几千），**体积有界**。
 * - **文件条目** `J/O`：上次判定过的文件类型（垃圾/其他）+size+mtime，
 *   仅对**本次实际遍历过**的分片保留，用于文件级增量（避免重复读文件头）。
 *
 * ## 增量语义
 * 1. **分片级**：分片根 mtime 未变 → 整片跳过遍历，直接用聚合（快）；
 * 2. **文件级**：变动的分片内，逐文件按 size+mtime 判定，未变则复用类型。
 *
 * ## 文件格式（UTF-8，行）
 * - `s \t <shardRoot> \t <hits> \t <bytes> \t <mtime>`
 * - `<kind> \t <path> \t <size> \t <mtime>`（kind ∈ {J=垃圾, O=其他, A=APK, Z=ZIP}）
 *
 * ## 安全
 * 缓存只是加速手段；任何删除仍必须经 [RubbishGuard]，且删除前会重新校验路径。
 */
class ScanCache(private val rootDir: File) {

    /** 单条文件索引项。 */
    data class Entry(
        val kind: Char,
        val path: String,
        val size: Long,
        val mtime: Long,
    )

    /** 分片聚合：分片根子树的总量与 mtime。 */
    data class ShardAgg(
        val root: String,
        val junkFiles: Int,
        val junkBytes: Long,
        val mtime: Long,
    )

    /** 一条规则的索引。 */
    class Index {
        val entries = LinkedHashMap<String, Entry>()
        val shardAggs = HashMap<String, ShardAgg>()

        fun put(e: Entry) { entries[e.path] = e }
        fun get(path: String): Entry? = entries[path]

        /** 文件是否与磁盘一致（size+mtime 均未变）。 */
        fun isFresh(path: String, size: Long, mtime: Long): Boolean {
            val e = entries[path] ?: return false
            return e.size == size && e.mtime == mtime
        }

        /** 分片聚合命中（mtime 匹配）则返回。 */
        fun shardAgg(rootPath: String, mtime: Long): ShardAgg? {
            val a = shardAggs[rootPath] ?: return null
            return if (a.mtime == mtime) a else null
        }

        fun putShardAgg(rootPath: String, files: Int, bytes: Long, mtime: Long) {
            shardAggs[rootPath] = ShardAgg(rootPath, files, bytes, mtime)
        }

        /** 只保留本次见过的分片聚合与其文件条目（剔除已消失分片）。 */
        fun retainShards(alive: Set<String>) {
            if (alive.isEmpty()) return
            val it = shardAggs.keys.iterator()
            while (it.hasNext()) {
                val k = it.next()
                if (k !in alive) it.remove()
            }
            val eit = entries.keys.iterator()
            while (eit.hasNext()) {
                val p = eit.next()
                // 文件条目：其所属分片（顶层祖先）不在 alive 中则剔除。
                if (!belongsToAny(p, alive)) eit.remove()
            }
        }

        private fun belongsToAny(path: String, alive: Set<String>): Boolean {
            var idx = path.length
            while (true) {
                val slash = path.lastIndexOf('/', idx - 1)
                if (slash <= 0) return false
                val prefix = path.substring(0, slash)
                if (prefix in alive) return true
                idx = slash
            }
        }
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
                if (parts.size >= 5 && parts[0] == "s") {
                    val root = unescape(parts[1])
                    val h = parts[2].toIntOrNull() ?: 0
                    val b = parts[3].toLongOrNull() ?: 0L
                    val m = parts[4].toLongOrNull() ?: 0L
                    idx.shardAggs[root] = ShardAgg(root, h, b, m)
                } else if (parts.size >= 4) {
                    val kind = parts[0].firstOrNull() ?: 'O'
                    val path = unescape(parts[1])
                    val size = parts[2].toLongOrNull() ?: 0L
                    val mtime = parts[3].toLongOrNull() ?: 0L
                    idx.put(Entry(kind, path, size, mtime))
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
            for (a in idx.shardAggs.values) {
                sb.append('s').append('\t').append(escape(a.root)).append('\t')
                    .append(a.junkFiles).append('\t').append(a.junkBytes).append('\t')
                    .append(a.mtime).append('\n')
            }
            for (e in idx.entries.values) {
                sb.append(e.kind).append('\t').append(escape(e.path)).append('\t')
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

    /**
     * 转义路径中的制表符/换行/反斜杠，保证「一行一条」不被文件名破坏。
     * （部分应用会在文件名里塞制表符，曾导致索引行错位、kind 列丢失。）
     */
    private fun escape(s: String): String {
        if (s.indexOf('\t') < 0 && s.indexOf('\n') < 0 && s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** [escape] 的逆操作。 */
    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
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

    // ------------------------------------------------------------------
    // 并发合并（分片扫描）
    // ------------------------------------------------------------------

    /** 一个分片遍历产生的文件条目（分片完成后一次性并入主索引）。 */
    class Shard {
        val entries = ArrayList<Entry>(128)
        fun addEntry(e: Entry) { entries.add(e) }
        fun clear() { entries.clear() }
    }

    /** 将分片条目合并进主索引（加锁，短临界区）。 */
    fun mergeInto(idx: Index, shard: Shard, lock: Any) {
        synchronized(lock) {
            for (e in shard.entries) idx.put(e)
        }
    }
}