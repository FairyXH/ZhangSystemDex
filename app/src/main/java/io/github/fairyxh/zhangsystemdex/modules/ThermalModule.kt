package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File

/**
 * Thermal config masking inside the module overlay. Replaces the generated
 * thermaldel.sh. stdltm=true overlays 0-byte files over thermal configs;
 * stdltm=false keeps the original (no-op) behavior.
 */
class ThermalModule(private val ctx: DexContext) {

    fun applyMask() {
        try {
            val overlayRoot = File(ctx.modDir)
            val stdltm = ctx.config.getBool("stdltm", false)
            var count = 0
            for (rootName in listOf("/system", "/vendor")) {
                val root = File(rootName)
                if (!root.exists()) continue
                val files = findThermalFiles(root)
                for (rel in files) {
                    val target = File(overlayRoot, rel)
                    target.parentFile?.mkdirs()
                    if (stdltm) {
                        FileUtils.touch(target.path)
                    } else {
                        target.delete()
                    }
                    count++
                }
            }
            val perfDir = File("/system/vendor/etc/perf")
            if (perfDir.exists()) {
                perfDir.listFiles()?.forEach { f ->
                    if (!f.name.contains("android")) {
                        val target = File(overlayRoot, "system/vendor/etc/perf/${f.name}")
                        target.parentFile?.mkdirs()
                        if (stdltm) FileUtils.touch(target.path) else target.delete()
                        count++
                    }
                }
            }
            Logger.i("Thermal", "thermal mask applied ($count files, stdltm=$stdltm)")
        } catch (t: Throwable) {
            Logger.w("Thermal", "thermal mask failed: ${t.message}")
        }
    }

    private fun findThermalFiles(root: File): List<String> {
        val result = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                visited++
                if (child.isDirectory) {
                    if (visited < 20000) stack.addLast(child)
                } else if (child.isFile && child.name.contains("thermal") && !child.name.contains("android")) {
                    result.add(child.path)
                }
            }
        }
        return result
    }
}
