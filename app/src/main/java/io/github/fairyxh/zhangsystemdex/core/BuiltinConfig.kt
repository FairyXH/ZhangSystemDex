package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * 内置应用「守护 / OOM」逐应用配置（`builtin_guard.conf`）。
 *
 * ## 背景（用户需求 2026-10-06）
 *
 * 历史上模块内置应用（`system/app/`）被**无条件强制**纳入 Doze 白名单、
 * 多任务 Lock、通知/无障碍保活与 OOM 保护，用户无法按应用关闭。
 *
 * 现改为：
 *   - **内置守护**（Doze + 多任务 Lock + 通知/无障碍保活）可按应用独立开关（默认开）；
 *   - **OOM 保护**做成可选：
 *       * 若该内置应用**声明了无障碍服务**或**通知监听服务** → **强制** OOM 保护（不可关）；
 *       * 其余内置应用 → 可选（复选框，默认不勾）。
 *
 * ## 文件格式
 *
 * 一行一个内置应用：`<pkg>=<guard>,<oom>`
 *   - `guard`：1/0，是否参与内置守护（Doze/多任务Lock/保活）。默认 1。
 *   - `oom`：1/0，是否参与 OOM 保护。默认 0（仅强制集合默认 1）。
 *
 * 未出现在文件中的应用视为 `guard=1, oom=0`。
 * 文件不存在时全部走默认值（保持与旧版一致的 guard 全开）。
 */
object BuiltinConfig {
    const val FILE_NAME = "builtin_guard.conf"
    fun file(rootDir: File): File = File(rootDir, FILE_NAME)

    private data class Entry(val guard: Boolean, val oom: Boolean)

    @Volatile private var cachedEntries: Map<String, Entry> = emptyMap()
    @Volatile private var cachedMtime: Long = -1L
    @Volatile private var cachedRoot: String = ""

    private fun entries(rootDir: File): Map<String, Entry> {
        val f = file(rootDir)
        val mtime = if (f.exists()) f.lastModified() else 0L
        if (cachedRoot == rootDir.path && cachedMtime == mtime) return cachedEntries
        val out = HashMap<String, Entry>()
        try {
            if (f.exists()) {
                for (raw in f.readText(Charsets.UTF_8).lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val body = line.substringBefore('#').trim()
                    if (!body.contains('=')) continue
                    val pkg = body.substringBefore('=').trim()
                    if (!OomProtectList.isValidPackage(pkg)) continue
                    val rhs = body.substringAfter('=').trim()
                    val parts = rhs.split(',')
                    val g = parts.getOrNull(0)?.trim()?.let { it == "1" || it.equals("true", true) } ?: true
                    val o = parts.getOrNull(1)?.trim()?.let { it == "1" || it.equals("true", true) } ?: false
                    out[pkg] = Entry(g, o)
                }
            }
        } catch (t: Throwable) {
            Logger.w("BuiltinConfig", "读取 ${FILE_NAME} 失败: ${t.message}")
        }
        cachedEntries = out
        cachedMtime = mtime
        cachedRoot = rootDir.path
        return out
    }

    /** 某内置应用的守护开关（默认 true）。 */
    fun isGuardEnabled(rootDir: File, pkg: String): Boolean = entries(rootDir)[pkg]?.guard ?: true

    /** 某内置应用的 OOM 勾选（默认 false，不含强制判定）。 */
    fun isOomChecked(rootDir: File, pkg: String): Boolean = entries(rootDir)[pkg]?.oom ?: false

    /**
     * 含「无障碍 / 通知」组件的内置应用 → OOM **强制**保护（不可关）。
     *
     * ## 重要（2026-10-06 软重启事故后重写）
     *
     * 旧实现对本方法直接 `probeHasNotifOrA11y(pkg)`，会 fork `cmd package dump <pkg>`。
     * 由于本方法在 daemon 启动/每轮 tick 中会对**全部内置应用**（`builtin_apps.conf`，
     * 实测 45 个）逐个调用，等于**串行 45 次 `cmd package dump`**，最终在 system_server
     * 内执行，**严重阻塞 system_server → Watchdog 超时杀死 system_server → 软重启**。
     *
     * 现改为：
     *   1. **只读缓存**（内存 + 落盘 `builtin_forced.conf`），本方法**永不阻塞**；
     *   2. 缓存未命中时返回 `false`（保守：不强制），并**排入后台探测队列**；
     *   3. 后台单线程**串行、限速**（每次探测间隔 [PROBE_INTERVAL_MS]）地跑
     *      `cmd package dump`，结果落盘，下一轮 tick 即可读到（最终一致）。
     */
    fun isForcedOom(pkg: String): Boolean {
        forcedCache[pkg]?.let { return it }
        pendingProbe.add(pkg)
        return false
    }

    private val forcedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 待后台探测的包名（去重、有序）。 */
    private val pendingProbe = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** 后台探测线程是否已启动。 */
    @Volatile private var probeThreadStarted = false

    /** 落盘缓存文件名（`<pkg>=1/0`）。 */
    const val FORCED_FILE_NAME = "builtin_forced.conf"

    /** 两次后台探测之间的最小间隔（毫秒），避免连续冲击 system_server。 */
    private const val PROBE_INTERVAL_MS = 250L

    private fun forcedCacheFile(rootDir: File): File = File(rootDir, FORCED_FILE_NAME)

    /** 从落盘缓存加载（进程启动时调用一次即可）。 */
    fun loadForcedCache(rootDir: File) {
        val f = forcedCacheFile(rootDir)
        if (!f.exists()) return
        try {
            for (raw in f.readText(Charsets.UTF_8).lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val body = line.substringBefore('#').trim()
                val i = body.indexOf('=')
                if (i <= 0) continue
                val pkg = body.substring(0, i).trim()
                val v = body.substring(i + 1).trim()
                if (pkg.isNotEmpty()) forcedCache[pkg] = v == "1" || v.equals("true", true)
            }
            Logger.i("BuiltinConfig", "已加载强制 OOM 缓存（${forcedCache.size} 项）")
        } catch (t: Throwable) {
            Logger.w("BuiltinConfig", "读取 $FORCED_FILE_NAME 失败: ${t.message}")
        }
    }

    private fun saveForcedCache(rootDir: File) {
        try {
            val f = forcedCacheFile(rootDir)
            val text = buildString {
                append("# 内置应用「强制 OOM」探测结果缓存（由后台探测线程维护，勿手改）\n")
                append("# 一行：<pkg>=1/0。1=含无障碍/通知组件，OOM 保护强制生效。\n")
                for ((p, v) in forcedCache) append(p).append('=').append(if (v) "1" else "0").append('\n')
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 启动后台探测线程（幂等）。**串行、限速**地消费 [pendingProbe]，
     * 每完成一个包即增量落盘，避免丢失。
     */
    fun startProbeWorker(rootDir: File) {
        if (probeThreadStarted) return
        synchronized(this) {
            if (probeThreadStarted) return
            probeThreadStarted = true
        }
        val t = Thread({
            var dirty = false
            while (true) {
                val pkg = pendingProbe.poll()
                if (pkg == null) {
                    if (dirty) { saveForcedCache(rootDir); dirty = false }
                    try { Thread.sleep(1500L) } catch (_: InterruptedException) { return@Thread }
                    continue
                }
                if (forcedCache.containsKey(pkg)) continue
                val v = probeHasNotifOrA11y(pkg)
                forcedCache[pkg] = v
                dirty = true
                try { Thread.sleep(PROBE_INTERVAL_MS) } catch (_: InterruptedException) { saveForcedCache(rootDir); return@Thread }
            }
        }, "builtin-forced-probe")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
        Logger.i("BuiltinConfig", "组件探测后台线程已启动（串行限速 ${PROBE_INTERVAL_MS}ms/个）")
    }

    private fun probeHasNotifOrA11y(pkg: String): Boolean {
        return try {
            AccessibilityGrant.probeComponents(pkg).isNotEmpty() ||
                NotificationGrant.probeComponents(pkg).isNotEmpty()
        } catch (t: Throwable) {
            Logger.w("BuiltinConfig", "探测 $pkg 通知/无障碍组件失败: ${t.message}")
            false
        }
    }

    /** 内置应用是否参与 OOM 保护（强制 ∪ 用户勾选）。 */
    fun isOomEnabled(rootDir: File, pkg: String): Boolean =
        isForcedOom(pkg) || isOomChecked(rootDir, pkg)

    /** 参与「内置守护」的内置应用集合。 */
    fun guardPackages(rootDir: File): List<String> =
        BuiltinApps.packagesFromRoot(rootDir).filter { isGuardEnabled(rootDir, it) }

    /** 参与 OOM 保护的内置应用集合（强制 ∪ 勾选）。 */
    fun oomPackages(rootDir: File): List<String> =
        BuiltinApps.packagesFromRoot(rootDir).filter { isOomEnabled(rootDir, it) }

    /** 写入某内置应用的配置（保留其它条目）。 */
    fun set(rootDir: File, pkg: String, guard: Boolean?, oom: Boolean?): Boolean {
        if (!OomProtectList.isValidPackage(pkg)) return false
        val current = LinkedHashMap<String, Entry>()
        BuiltinApps.packagesFromRoot(rootDir).forEach { p ->
            current[p] = entries(rootDir)[p]
                ?: Entry(isGuardEnabled(rootDir, p), isOomChecked(rootDir, p))
        }
        val old = current[pkg] ?: Entry(true, false)
        current[pkg] = Entry(guard ?: old.guard, oom ?: old.oom)
        return writeAll(rootDir, current)
    }

    private fun writeAll(rootDir: File, map: Map<String, Entry>): Boolean {
        val f = file(rootDir)
        return try {
            f.parentFile?.mkdirs()
            val text = buildString {
                append("# 内置应用守护 / OOM 逐应用开关（一行：pkg=guard,oom）\n")
                append("# guard=1/0 是否参与内置守护（Doze+多任务Lock+通知/无障碍保活），默认 1\n")
                append("# oom=1/0   是否参与 OOM 保护（用户勾选），默认 0\n")
                append("# 注意：声明了无障碍服务或通知监听服务的应用，OOM 保护强制生效（忽略此处的 oom=0）。\n")
                for ((p, e) in map) append(p).append('=').append(if (e.guard) "1" else "0")
                    .append(',').append(if (e.oom) "1" else "0").append('\n')
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
            invalidate()
            Logger.i("BuiltinConfig", "$FILE_NAME 已写入 ${map.size} 项")
            true
        } catch (t: Throwable) {
            Logger.w("BuiltinConfig", "写入 $FILE_NAME 失败: ${t.message}")
            false
        }
    }

    fun invalidate() {
        cachedMtime = -1L
        cachedRoot = ""
        cachedEntries = emptyMap()
        // 注意：forcedCache 是「探测结果」落盘缓存，**不随配置变更清空**（探测成本高，
        // 且组件声明不随用户开关变化）。如需重探，删除 `builtin_forced.conf` 即可。
    }
}
