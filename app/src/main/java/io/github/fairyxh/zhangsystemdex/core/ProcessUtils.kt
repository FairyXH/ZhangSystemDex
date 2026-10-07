package io.github.fairyxh.zhangsystemdex.core
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
/**
 * Process, sysfs and cgroup helpers. Prefers /proc and sysfs File access;
 * renice/chrt/pgrep are shell-only operations (setpriority/sched syscalls have
 * no Java API) but are wrapped semantically.
 */
object ProcessUtils {
    @Volatile
    private var setpriorityWarned = false
    fun writeFile(path: String, value: String): Boolean {
        try {
            File(path).writeText(value)
            return true
        } catch (_: Throwable) {
            // app_process runs in the zygote SELinux domain after exec, which is
            // denied for some sysfs paths; retry through su (shell/su domain).
        }
        val rc = ShellExecutor.runExit("su -c 'echo $value > $path'")
        if (rc == 0) return true
        Logger.w("ProcessUtils", "写入失败（文件+su 返回码=$rc）: $path")
        return false
    }
    fun appendCgroup(pid: Int, path: String): Boolean {
        try {
            File(path).appendText("$pid\n")
            return true
        } catch (_: Throwable) {
        }
        return ShellExecutor.runExit("su -c 'echo $pid > $path'") == 0
    }
    fun readFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (_: Throwable) {
            null
        }
    }
    /**
     * 流式读取 procfs 文件（**不要**用 [File.readBytes] / `Files.readAllBytes`）。
     *
     * procfs 中 `cmdline` / `status` / `stat` / `oom_score_adj` 等文件的
     * `st_size` 恒为 **0**，而 `File.readBytes()` 内部按 `length()` 预分配
     * 缓冲区，结果**只读到 0 字节**。此类文件必须走流式读取
     * （[FileInputStream] + 循环 read），与 `File.readText()` 行为一致。
     *
     * 2026-10-07 修复：此前 [pidsOf] 用 `readBytes()` 读 `/proc/<pid>/cmdline`
     * 恒得空串，导致**任何进程都匹配不到** —— Shizuku 保活因此永远误判
     * 「服务端不在」并每 30s 重启一次（restartCount 涨到 137）。
     *
     * @param maxBytes 上限，避免 cgroup 等无边界文件读爆内存。
     * @return 文件内容；读取失败返回 null。
     */
    fun readProcText(f: File, maxBytes: Int = 8192): String? {
        return try {
            FileInputStream(f).use { input ->
                val bos = ByteArrayOutputStream(256)
                val buf = ByteArray(512)
                var total = 0
                while (total < maxBytes) {
                    val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                    if (n <= 0) break
                    bos.write(buf, 0, n)
                    total += n
                }
                String(bos.toByteArray(), Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            null
        }
    }
    /**
     * 按「进程即目标」语义查找 pid。
     *
     * ## 匹配规则（2026-10-06 收紧，修复误伤）
     *
     * 旧实现用 `cmdline.contains(pattern)`，会把**任何命令行中出现该串**的无关进程
     * 也算进来——实测把模块自己的 `cmd package dump com.box.app`、`grep versionCode`
     * 等 shell 子进程误判为「受保护对象」并改了 `oom_score_adj`。
     *
     * 现改为只看 **arg0（可执行名/包名）**：
     *   - `arg0 == pattern`；
     *   - `arg0` 以 `pattern:` 开头（Android 包名进程，如 `com.foo.bar:svc`）；
     *   - `arg0` 以 `"/" + pattern` 结尾（系统二进制，如 `/system/bin/init`）。
     *
     * 这覆盖了包名（`com.omarea.vtools`）、进程名（`frpc`）、系统服务（`surfaceflinger`
     * → `/system/bin/surfaceflinger`），同时**排除**「命令行里恰好含该串」的进程。
     *
     * 注意：读取必须用 [readProcText]（procfs 的 `st_size` 恒为 0）。
     */
    fun pidsOf(pattern: String): List<Int> {
        if (pattern.isEmpty()) return emptyList()
        val result = ArrayList<Int>()
        val proc = File("/proc")
        val dirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return result
        val suffix = "/$pattern"
        for (dir in dirs) {
            try {
                val pid = dir.name.toInt()
                val cmdline = readProcText(File(dir, "cmdline")) ?: continue
                // arg0 = cmdline 的首个 NUL 分隔段（不 trim，保留原形）。
                val arg0 = cmdline.substringBefore('\u0000').trim()
                if (arg0 == pattern ||
                    arg0.startsWith("$pattern:") ||
                    arg0.endsWith(suffix)
                ) {
                    result.add(pid)
                }
            } catch (_: Throwable) {
            }
        }
        return result
    }
    /**
     * 读取指定 pid 的**进程名**（`/proc/<pid>/cmdline` 的 arg0）。
     *
     * 与 [pidsOf] 同口径：只取首个 NUL 段并 trim，读不到返回空串。
     * 必须用 [readProcText]（procfs 的 `st_size` 恒为 0）。
     *
     * 2026-10-07 新增：Shizuku 保活改用「进程名」而非 uid 区分主/服务端
     * （Shizuku 转系统应用后主应用 uid 会是 99910335，与 root 服务端 uid 形态撞车）。
     */
    fun procName(pid: Int): String {
        val cmdline = readProcText(File("/proc/$pid/cmdline")) ?: return ""
        return cmdline.substringBefore('\u0000').trim()
    }

    fun renice(pid: Int, niceness: Int) {
        try {
            // Os.setpriority is not exposed in the SDK stub; reflect it
            // (PRIO_PROCESS = 0).
            val osClass = Class.forName("android.system.Os")
            val m = osClass.getMethod(
                "setpriority",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            m.invoke(null, 0, pid, niceness)
            return
        } catch (t: Throwable) {
            if (!setpriorityWarned) {
                setpriorityWarned = true
                Logger.w("ProcessUtils", "setpriority 失败，降级 shell（仅记录一次）: ${t.message}")
            }
        }
        ShellExecutor.run("renice -n $niceness -p $pid")
    }
    fun chrt(pid: Int, policy: String, priority: Int) {
        ShellExecutor.run("chrt -$policy -p $priority $pid")
    }
    /** Parse the focused application package from dumpsys window displays. */
    fun focusedPackage(): String? {
        val out = ShellExecutor.run("dumpsys window displays | grep mFocusedApp | grep -v 'mFocusedApp=null'") ?: return null
        val line = out.lineSequence().firstOrNull() ?: return null
        val idx = line.indexOf('=')
        if (idx < 0) return null
        val rest = line.substring(idx + 1).trim()
        val parts = rest.split('/')
        return parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }
    /** Mirror dumpsys deviceidle get screen: "true" when screen is on. */
    fun isScreenOn(): Boolean {
        val pm = SystemContext.get()?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null) {
            try {
                return pm.isInteractive
            } catch (t: Throwable) {
                Logger.w("ProcessUtils", "PowerManager.isInteractive 失败: ${t.message}")
            }
        }
        return ShellExecutor.run("dumpsys deviceidle get screen")?.trim() == "true"
    }
    fun memFreePercent(): Int {
        val meminfo = readFile("/proc/meminfo") ?: return 100
        var total = 0L
        var free = 0L
        var lineIdx = 0
        for (line in meminfo.lineSequence()) {
            val num = line.substringAfter(':').trim().removeSuffix(" kB").trim().toLongOrNull() ?: 0L
            when (lineIdx) {
                0 -> total = num
                2 -> free = num
            }
            lineIdx++
            if (lineIdx > 2) break
        }
        if (total <= 0) return 100
        return ((free * 100) / total).toInt()
    }
    /**
     * 物理内存「已用」百分比（0..100）。
     *
     * 用 `MemTotal - MemAvailable` 计算：比 `free` 列更贴近真实压力
     * （`free` 不含可回收的 page cache，会严重高估压力）。
     * 解析失败返回 0（视为「正常」，避免误触发看门狗）。
     */
    fun memUsedPercent(): Int {
        val meminfo = readFile("/proc/meminfo") ?: return 0
        var total = 0L
        var available = -1L
        var free = 0L
        for (line in meminfo.lineSequence()) {
            val v = line.substringAfter(':').trim().removeSuffix("kB").trim().toLongOrNull() ?: continue
            when {
                line.startsWith("MemTotal:") -> total = v
                line.startsWith("MemAvailable:") -> available = v
                line.startsWith("MemFree:") -> free = v
            }
        }
        if (total <= 0) return 0
        val avail = if (available >= 0) available else free
        return (((total - avail) * 100) / total).toInt().coerceIn(0, 100)
    }
    /** 进程的物理内存占用（VmRSS，单位 kB）；读取失败返回 0。 */
    fun rssKbOf(pid: Int): Long {
        val status = readFile("/proc/$pid/status") ?: return 0L
        for (line in status.lineSequence()) {
            if (line.startsWith("VmRSS:")) {
                return line.substringAfter(':').trim().removeSuffix("kB").trim().toLongOrNull() ?: 0L
            }
        }
        return 0L
    }
    /** 物理内存总量（MemTotal，单位 kB）；读取失败返回 0。 */
    fun memTotalKb(): Long {
        val meminfo = readFile("/proc/meminfo") ?: return 0L
        for (line in meminfo.lineSequence()) {
            if (line.startsWith("MemTotal:")) {
                return line.substringAfter(':').trim().removeSuffix("kB").trim().toLongOrNull() ?: 0L
            }
        }
        return 0L
    }
    /** This daemon's own pid (falls back to -1 if the runtime can't provide it). */
    fun selfPid(): Int = try {
        android.os.Process.myPid()
    } catch (_: Throwable) {
        try {
            ProcessHandle.current().pid().toInt()
        } catch (_: Throwable) {
            -1
        }
    }
    /** Number of running processes (rows in /proc whose name is all digits). */
    fun processCount(): Int = try {
        File("/proc").listFiles()?.count { it.isDirectory && it.name.all { c -> c.isDigit() } } ?: -1
    } catch (_: Throwable) {
        -1
    }
}