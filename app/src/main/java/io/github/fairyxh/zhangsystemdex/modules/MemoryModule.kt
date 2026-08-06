package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import java.io.File

/**
 * Memory management: page-cache drops every 300s plus low-memory background
 * process force-stop. Replaces CleanMemory.sh and MemoryClean.sh.
 */
class MemoryModule(ctx: DexContext) : DaemonLoop(ctx, 3_000L) {
    private var dropTick = 0
    private var lastDropLog = 0L

    override fun onStart() {
        if (!ctx.config.switch("memory_clean_enable")) {
            Logger.i(name, "memory module disabled by switch")
            stop()
            return
        }
        Logger.i(name, "threshold=5% free memory, drop_caches every 300s")
    }

    override fun tick() {
        dropTick++
        if (dropTick >= 100) {
            dropTick = 0
            dropCaches()
        }
        val freePercent = ProcessUtils.memFreePercent()
        if (freePercent < 5) {
            Logger.i(name, "low memory: $freePercent% free, force-stopping background apps")
            forceStopBackground()
            sleepSafe(30_000)
        } else if (now() - lastDropLog > 600_000) {
            Logger.i(name, "memory free: $freePercent%")
            lastDropLog = now()
        }
    }

    fun runOnce() {
        dropCaches()
        Logger.i(name, "memory free: ${ProcessUtils.memFreePercent()}%")
    }

    private fun dropCaches() {
        Logger.i(name, "dropping page caches")
        ShellExecutor.run("sync")
        for (i in 1..3) {
            ProcessUtils.writeFile("/proc/sys/vm/drop_caches", "3")
        }
    }

    private fun forceStopBackground() {
        val whitelist = readWhitelist()
        val exclude = buildString {
            append("bin.mt.plus|android|miui|system|mojang")
            for (pkg in whitelist) append('|').append(Regex.escape(pkg))
        }
        val out = ShellExecutor.run("pm list packages -3 | grep -vE '$exclude'") ?: return
        val focus = ProcessUtils.focusedPackage()
        for (line in out.lineSequence()) {
            val pkg = line.trim().removePrefix("package:").trim()
            if (pkg.isEmpty()) continue
            if (pkg == focus) continue
            Logger.i(name, "force-stop $pkg")
            ShellExecutor.run("am force-stop $pkg")
        }
    }

    private fun readWhitelist(): List<String> {
        val f = File(ctx.config.rootDir, "doze.conf")
        if (!f.exists()) return emptyList()
        return f.readLines()
            .map { it.trim().removePrefix("+") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun now(): Long = System.currentTimeMillis()
}
