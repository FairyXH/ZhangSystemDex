package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * File helpers for root-level operations. Everything possible uses the File API;
 * chattr/chmod helpers fall back to shell because they have no Java equivalent.
 */
object FileUtils {
    fun deleteRecursive(f: File) {
        if (!f.exists()) return
        try {
            if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
            f.delete()
        } catch (_: Throwable) {
        }
    }

    fun copyFile(src: File, dst: File): Boolean {
        return try {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            true
        } catch (t: Throwable) {
            Logger.w("FileUtils", "copy ${src.path} -> ${dst.path} failed: ${t.message}")
            false
        }
    }

    fun touch(path: String) {
        try {
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeText("")
        } catch (_: Throwable) {
        }
    }

    fun chmod(path: String, mode: String) {
        ShellExecutor.run("chmod $mode '$path'")
    }

    fun chattr(path: String, flags: String) {
        ShellExecutor.run("chattr $flags '$path'")
    }

    fun rmQuoted(path: String) {
        ShellExecutor.run("rm -rf '$path'")
    }

    fun mvQuoted(src: String, dst: String) {
        ShellExecutor.run("mv -f '$src' '$dst'")
    }

    fun mkdirs(path: String) {
        try {
            File(path).mkdirs()
        } catch (_: Throwable) {
        }
    }
}
