package io.github.fairyxh.zhangsystemdex.core.rubbish

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 垃圾扫描/清理任务的**实时进度**（进程内单例）。
 *
 * 背景：完整扫描要遍历几十万个文件、持续数十秒到数分钟。此前
 * `/api/rubbish/scan` 是同步阻塞调用——WebUI 只能「干等」到请求返回，
 * 期间没有任何反馈。现在扫描改为后台任务：HTTP 立即返回，前端轮询
 * `/api/rubbish/progress` 拿到实时状态（当前规则、当前扫描路径、
 * 规则进度、分片进度、累计命中数与字节），结束后再取 `/api/rubbish/result`。
 *
 * 线程模型：扫描线程写、HTTP 工作线程读；所有字段用原子类型 / volatile，
 * 保证跨线程可见，且读侧永远不阻塞写侧。
 */
object RubbishProgress {

    enum class Phase { IDLE, SCANNING, CLEANING, DONE, ERROR }

    /** 一次任务的快照（供 JSON 序列化，纯数据、无锁）。 */
    data class Snapshot(
        val running: Boolean,
        val phase: Phase,
        /** 当前正在扫描的规则：id/name。 */
        val ruleId: String,
        val ruleName: String,
        /** 当前正在遍历的路径（分片根或目标），用于「正在扫描 xxx」。 */
        val currentPath: String,
        /** 规则进度（已完成规则数, 总规则数）。 */
        val rulesDone: Int,
        val rulesTotal: Int,
        /** 当前规则内的分片进度（已完成分片, 总分片）。 */
        val shardDone: Int,
        val shardTotal: Int,
        /** 累计命中（文件数 / 字节），实时递增。 */
        val hitFiles: Long,
        val hitBytes: Long,
        val startedAtMs: Long,
        val elapsedMs: Long,
        /** 任务类型：scan（仅统计）或 clean（实际删除）。 */
        val dryRun: Boolean,
        val message: String,
    )

    @Volatile private var phase: Phase = Phase.IDLE
    @Volatile private var ruleId: String = ""
    @Volatile private var ruleName: String = ""
    @Volatile private var currentPath: String = ""
    @Volatile private var dryRun: Boolean = true
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var message: String = ""

    private val rulesDone = AtomicInteger(0)
    private val rulesTotal = AtomicInteger(0)
    private val shardDone = AtomicInteger(0)
    private val shardTotal = AtomicInteger(0)
    private val hitFiles = AtomicLong(0)
    private val hitBytes = AtomicLong(0)

    /** 最近一次任务的完整结果 JSON（供 /api/rubbish/result 取用）。 */
    private val lastResult = AtomicReference<String>("")

    /** 当前任务是否在运行中。 */
    @Volatile private var running: Boolean = false

    fun isRunning(): Boolean = running

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 开始一次任务（scan/clean）。若已有任务在跑则返回 false。 */
    fun begin(dryRun: Boolean, totalRules: Int): Boolean {
        if (running) return false
        running = true
        this.dryRun = dryRun
        phase = if (dryRun) Phase.SCANNING else Phase.CLEANING
        ruleId = ""
        ruleName = ""
        currentPath = ""
        message = ""
        startedAtMs = System.currentTimeMillis()
        rulesDone.set(0)
        rulesTotal.set(totalRules)
        shardDone.set(0)
        shardTotal.set(0)
        hitFiles.set(0)
        hitBytes.set(0)
        return true
    }

    /** 进入某条规则。 */
    fun enterRule(id: String, name: String) {
        ruleId = id
        ruleName = name
        currentPath = ""
        shardDone.set(0)
        shardTotal.set(0)
    }

    /** 当前正在遍历的分片/目标路径。 */
    fun onPath(path: String) {
        currentPath = path
    }

    /** 当前规则内的分片进度。 */
    fun onShard(done: Int, total: Int) {
        shardDone.set(done)
        shardTotal.set(total)
    }

    /** 累计命中更新（调用方传入增量）。 */
    fun addHits(files: Long, bytes: Long) {
        hitFiles.addAndGet(files)
        hitBytes.addAndGet(bytes)
    }

    /** 完成一条规则。 */
    fun finishRule() {
        rulesDone.incrementAndGet()
        currentPath = ""
        shardDone.set(0)
        shardTotal.set(0)
    }

    /** 任务成功结束，保存结果 JSON。 */
    fun finish(resultJson: String) {
        lastResult.set(resultJson)
        phase = Phase.DONE
        currentPath = ""
        running = false
        message = "完成"
    }

    /** 任务失败。 */
    fun fail(msg: String) {
        phase = Phase.ERROR
        currentPath = ""
        running = false
        message = msg
    }

    /** 取最近一次结果 JSON（可能为空）。 */
    fun resultJson(): String = lastResult.get()

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    fun snapshot(): Snapshot {
        val started = startedAtMs
        return Snapshot(
            running = running,
            phase = phase,
            ruleId = ruleId,
            ruleName = ruleName,
            currentPath = currentPath,
            rulesDone = rulesDone.get(),
            rulesTotal = rulesTotal.get(),
            shardDone = shardDone.get(),
            shardTotal = shardTotal.get(),
            hitFiles = hitFiles.get(),
            hitBytes = hitBytes.get(),
            startedAtMs = started,
            elapsedMs = if (started > 0) System.currentTimeMillis() - started else 0L,
            dryRun = dryRun,
            message = message,
        )
    }
}
