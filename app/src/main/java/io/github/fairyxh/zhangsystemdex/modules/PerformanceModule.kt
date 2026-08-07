package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File

/**
 * CPU/GPU performance: max frequencies, cpuset partition (big cores for
 * foreground, little cores for background) and Qualcomm GPU max frequency.
 * Replaces maxcpu.sh. Controlled by config.json `max_cpu_flag` and invoked
 * from SystemTuningModule.
 */
class PerformanceModule(private val ctx: DexContext) {

    fun applyMaxCpu() {
        try {
            val cpuDir = File("/sys/devices/system/cpu")
            val cores = cpuDir.listFiles { f ->
                f.isDirectory && Regex("^cpu\\d+$").matches(f.name)
            } ?: return
            val coreNames = cores.map { it.name }
            val lastIndex = coreNames.size - 1
            if (lastIndex < 0) return

            for (core in cores) {
                ProcessUtils.writeFile(File(core, "online").path, "1")
                val govFile = File(core, "cpufreq/scaling_governor")
                ProcessUtils.writeFile(govFile.path, "performance")
                val freqs = ProcessUtils.readFile(File(core, "cpufreq/scaling_available_frequencies").path)
                val maxFreq = freqs?.trim()?.split(' ')?.lastOrNull()
                if (!maxFreq.isNullOrEmpty()) {
                    ProcessUtils.writeFile(File(core, "cpufreq/scaling_max_freq").path, maxFreq)
                    ProcessUtils.writeFile(File(core, "cpufreq/scaling_min_freq").path, maxFreq)
                }
            }

            val mid = lastIndex / 2
            val topNames = setOf("top-app", "foreground", "multisence-focus")
            val backNames = setOf("background", "system-background", "multisence-unfocus", "restricted")
            val cpusetDir = File("/dev/cpuset")
            cpusetDir.walkTopDown().forEach { dir ->
                val cpusFile = File(dir, "cpus")
                if (!cpusFile.isFile) return@forEach
                val setName = dir.name
                val range = when {
                    setName in topNames -> "${mid + 1}-$lastIndex"
                    setName in backNames -> "0-$mid"
                    else -> "0-$lastIndex"
                }
                ProcessUtils.writeFile(cpusFile.path, range)
            }

            val gpuFreq = ProcessUtils.readFile("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies")
            val maxGpu = gpuFreq?.trim()?.split(' ')?.firstOrNull()
            if (!maxGpu.isNullOrEmpty()) {
                ProcessUtils.writeFile("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", maxGpu)
                ProcessUtils.writeFile("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq", maxGpu)
            }
            Logger.i("Performance", "已在 ${coreNames.size} 个核心上应用最大 CPU/GPU 频率")
        } catch (t: Throwable) {
            Logger.w("Performance", "设置最大 CPU 频率失败: ${t.message}")
        }
    }
}
