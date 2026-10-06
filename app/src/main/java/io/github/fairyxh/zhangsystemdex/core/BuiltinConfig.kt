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
     * 判定：该包声明了 `AccessibilityService` 或 `NotificationListenerService` 组件。
     * 结果带缓存（探测需 fork dumpsys，较慢）。
     */
    fun isForcedOom(pkg: String): Boolean = forcedCache.getOrPut(pkg) { probeHasNotifOrA11y(pkg) }

    private val forcedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
        forcedCache.clear()
    }
}
