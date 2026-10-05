package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 分片并行目录扫描器（IDM 风格）。
 *
 * ## 核心模型
 * 1. **分片（shard）**：把每个扫描根的**直接子项**切成一个独立分片
 *    （如 `/data/user/0/<pkg>` 全私有目录 = 674 个分片）。
 * 2. **多线程**：若干工作线程从无锁队列取分片并发遍历，IO/CPU 重叠。
 * 3. **分片级缓存（A/B）**：每个分片遍历结束后，把它整棵子树的
 *    「命中数/字节 + 根目录 mtime」写回索引。下次扫描时若**分片根 mtime 未变**，
 *    整片直接复用聚合值、**不遍历**（IDM 分片断点续传语义）。
 *    —— 分片数上限 = 顶层子项数（几百到几千），索引体积有界、可控。
 * 4. **确定性**：无论冷/暖扫、几线程，**结果只取决于文件系统**，
 *    由「分片根聚合 == 全子树总量」的数学保证，不依赖并发写入顺序。
 *
 * ## 计数正确性
 * 分片总数 = 分片根聚合。聚合自底向上后序汇总；被复用的分片以缓存聚合参与。
 * 因此每个目标恰好计一次，冷/暖扫结果必然一致。
 *
 * ## 安全
 * 只读遍历；删除仍全部经 [RubbishGuard]，缓存不构成信任边界。
 */
internal class ParallelScanner(
    private val threads: Int = DEFAULT_THREADS,
) {

    companion object {
        /** 默认并发度（兼顾吞吐与存储随机读压力）。 */
        const val DEFAULT_THREADS = 4

        /** 每完成 N 个分片落盘一次已完成分片的聚合（增量、可中断）。 */
        private const val FLUSH_EVERY_SHARDS = 64
    }

    /** 分片遍历回调（同一分片内串行调用，实现方无需加锁）。 */
    interface Visitor {
        /**
         * 文件判定：返回 true 表示命中目标。
         * 实现方可通过 [shard] 写入索引条目（[ScanCache.Shard.addEntry]）。
         */
        fun onFile(file: File, parentDirName: String, shard: ScanCache.Shard): Boolean

        /** 是否跳过该子项（keep/禁止路径/受保护路径/超深等）。 */
        fun shouldSkip(child: File, parent: File): Boolean
    }

    /** 扫描结果。 */
    data class Result(
        val hits: Int,
        val bytes: Long,
        val samples: List<String>,
        /** 复用（未遍历）的分片数，用于日志/诊断。 */
        val reusedShards: Int,
    )

    /**
     * 执行一次分片并行扫描。
     *
     * @param shards       分片根列表（0 层，每个将独立遍历/复用）
     * @param idx          索引（分片聚合 + 文件条目）
     * @param cache        缓存（落盘）
     * @param ruleId       规则 id
     * @param visitor      访问器
     * @param maxSamples   采样上限
     * @param onProgress   进度回调（完成分片, 总分片）
     */
    fun scan(
        shards: List<File>,
        idx: ScanCache.Index,
        cache: ScanCache,
        ruleId: String,
        visitor: Visitor,
        maxSamples: Int = 5,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Result {
        val total = shards.size
        if (total == 0) return Result(0, 0L, emptyList(), 0)

        val queue = ConcurrentLinkedQueue(shards)
        val lock = Any()
        val done = AtomicInteger(0)
        val reused = AtomicInteger(0)
        val hits = AtomicLong(0)
        val bytes = AtomicLong(0)
        val samplesBox = java.util.Collections.synchronizedList(ArrayList<String>())
        // ★ 本次扫描涉及的「全部分片」路径（初始即预填，与扫描进度无关）。
        //   用于扫描结束时安全地剔除「真正已消失」的分片缓存。
        //   注意：绝不能在「增量落盘」时用它做 retain——那时它尚不完整，
        //   会把还没扫到的分片缓存整片删掉，导致暖扫命中率极低（历史 bug）。
        val aliveShards: MutableSet<String> =
            java.util.Collections.synchronizedSet(HashSet<String>())
        for (s in shards) aliveShards.add(s.path)

        val nThreads = minOf(threads, total).coerceAtLeast(1)
        val latch = CountDownLatch(nThreads)
        Logger.i("ParallelScanner", "扫描开始: 分片=$total 载入聚合=${idx.shardAggs.size} 载入条目=${idx.entries.size} 线程=$nThreads")

        for (t in 0 until nThreads) {
            Thread({
                val shard = ScanCache.Shard()
                try {
                    while (true) {
                        val root = queue.poll() ?: break
                        var f = 0
                        var b = 0L
                        try {
                            val m = if (root.isDirectory) root.lastModified() else -1L
                            // shardAggs 是普通 HashMap：读/写都必须持锁，避免并发 put 丢数据。
                            val cached = if (m > 0) synchronized(lock) { idx.shardAgg(root.path, m) } else null
                            if (cached != null) {
                                // ★ 分片级复用：mtime 未变 → 整片跳过遍历。
                                f = cached.junkFiles
                                b = cached.junkBytes
                                reused.incrementAndGet()
                                // 记一个样本，便于 UI 展示「已复用」
                                synchronized(samplesBox) {
                                    if (samplesBox.size < maxSamples) samplesBox.add("[缓存] " + root.path)
                                }
                            } else {
                                val agg = walk(root, visitor, shard, maxSamples, samplesBox)
                                f = agg[0].toInt()
                                b = agg[1]
                                // 本片遍历产生的文件条目与分片聚合一起在锁内并入索引。
                                synchronized(lock) {
                                    if (m > 0) idx.putShardAgg(root.path, f, b, m)
                                    cache.mergeInto(idx, shard, lock)
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.w("ParallelScanner", "分片失败 ${root.path}: ${e.message}")
                        }
                        shard.entries.clear()
                        hits.addAndGet(f.toLong())
                        bytes.addAndGet(b)
                        val d = done.incrementAndGet()
                        if (onProgress != null) {
                            try { onProgress(d, total) } catch (_: Throwable) {}
                        }
                        // 增量落盘（B）：已完成分片可随时中断续扫。
                        // ★ 此处「不」做 retainShards——扫描进行中 aliveShards 尚不完整，
                        //   一旦 retain 会把还没扫到的分片缓存删掉（历史 bug：暖扫命中率骤降）。
                        //   清理已消失分片统一放到扫描结束后的那一处。
                        if (d % FLUSH_EVERY_SHARDS == 0) {
                            synchronized(lock) { cache.save(ruleId, idx) }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }, "zsd-scan-$t").apply { isDaemon = true }.start()
        }

        latch.await()
        synchronized(lock) {
            idx.retainShards(aliveShards)
            cache.save(ruleId, idx)
        }
        val samples = synchronized(samplesBox) { ArrayList(samplesBox).take(maxSamples) }
        return Result(hits.get().toInt(), bytes.get(), samples, reused.get())
    }

    /**
     * 遍历一个分片子树，返回其聚合 [junkFiles, junkBytes]（后序汇总）。
     * 只统计「命中目标」的项：文件由 [Visitor.onFile] 判定，空目录计 1。
     */
    private fun walk(
        root: File,
        visitor: Visitor,
        shard: ScanCache.Shard,
        maxSamples: Int,
        samplesBox: MutableList<String>,
    ): LongArray {
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        // 路径 -> 子树聚合 [files, bytes]
        val agg = HashMap<String, LongArray>()
        // 路径 -> 展开时实际入栈的子项（避免二次 listFiles 期间目录变动导致子聚合丢失）
        val kids = HashMap<String, List<File>>()

        while (stack.isNotEmpty()) {
            val (cur, expanded) = stack.removeLast()
            val path = cur.path

            if (!expanded) {
                if (visitor.shouldSkip(cur, cur.parentFile ?: cur)) continue
                if (cur.isDirectory) {
                    val children = cur.listFiles()
                    if (children == null || children.isEmpty()) {
                        agg[path] = longArrayOf(1L, 0L) // 空目录 = 1 个目标
                        continue
                    }
                    // 先固定本目录「实际参与」的子项快照，展开阶段据此汇总。
                    val kept = ArrayList<File>(children.size)
                    for (c in children) {
                        if (visitor.shouldSkip(c, cur)) continue
                        kept.add(c)
                    }
                    if (kept.isEmpty()) {
                        // 全部被跳过 → 不构成空目录（有内容只是都被忽略），计 0。
                        agg[path] = longArrayOf(0L, 0L)
                        continue
                    }
                    kids[path] = kept
                    stack.addLast(cur to true)
                    for (c in kept) stack.addLast(c to false)
                } else if (cur.isFile) {
                    val hit = try {
                        visitor.onFile(cur, cur.parentFile?.name ?: "", shard)
                    } catch (_: Throwable) { false }
                    if (hit) {
                        synchronized(samplesBox) {
                            if (samplesBox.size < maxSamples) samplesBox.add(cur.path)
                        }
                        agg[path] = longArrayOf(1L, cur.length())
                    } else {
                        agg[path] = longArrayOf(0L, 0L)
                    }
                }
            } else {
                var f = 0L
                var b = 0L
                kids.remove(path)?.forEach { c ->
                    val a = agg.remove(c.path)
                    if (a != null) { f += a[0]; b += a[1] }
                }
                agg[path] = longArrayOf(f, b)
            }
        }
        return agg[root.path] ?: longArrayOf(0L, 0L)
    }
}