package io.github.fairyxh.zhangsystemdex.core

import java.io.File

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
        Logger.w("ProcessUtils", "write failed (file+su rc=$rc): $path")
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

    fun pidsOf(pattern: String): List<Int> {
        val result = ArrayList<Int>()
        val proc = File("/proc")
        val dirs = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return result
        for (dir in dirs) {
            try {
                val pid = dir.name.toInt()
                val cmdline = File(dir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .trim()
                if (cmdline.contains(pattern)) result.add(pid)
            } catch (_: Throwable) {
            }
        }
        return result
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
                Logger.w("ProcessUtils", "setpriority failed, fallback shell (logged once): ${t.message}")
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
                Logger.w("ProcessUtils", "PowerManager.isInteractive failed: ${t.message}")
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
}
