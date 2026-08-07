package io.github.fairyxh.zhangsystemdex.core

import android.system.Os
import java.io.File

/**
 * File helpers for root-level operations. Everything possible uses the File
 * API; chattr and the fallback paths of chmod/chown/rm/mv use shell because
 * they have no (or restricted) Java equivalents.
 */
object FileUtils {
    private val warned = HashSet<String>()

    private fun warnOnce(key: String, msg: String) {
        synchronized(warned) {
            if (warned.add(key)) Logger.w("FileUtils", "$msg (logged once)")
        }
    }

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
        val bits = mode.toIntOrNull(8)
        if (bits != null) {
            try {
                Os.chmod(path, bits)
                return
            } catch (t: Throwable) {
                warnOnce("chmod_$path", "Os.chmod failed, fallback shell: ${t.message}")
            }
        }
        ShellExecutor.run("chmod $mode '$path'")
    }

    fun chown(path: String, uid: Int, gid: Int) {
        try {
            Os.chown(path, uid, gid)
            return
        } catch (t: Throwable) {
            warnOnce("chown_$path", "Os.chown failed, fallback shell: ${t.message}")
        }
        ShellExecutor.run("chown $uid:$gid '$path'")
    }

    fun chattr(path: String, flags: String) {
        ShellExecutor.run("chattr $flags '$path'")
    }

    fun rmQuoted(path: String) {
        try {
            val f = File(path)
            if (f.isDirectory) deleteRecursive(f) else f.delete()
            return
        } catch (t: Throwable) {
            warnOnce("rm_$path", "file delete failed, fallback shell: ${t.message}")
        }
        ShellExecutor.run("rm -rf '$path'")
    }

    fun mvQuoted(src: String, dst: String) {
        try {
            val f = File(src)
            if (f.exists() && f.renameTo(File(dst))) return
        } catch (t: Throwable) {
            warnOnce("mv_$src", "rename failed, fallback shell: ${t.message}")
        }
        ShellExecutor.run("mv -f '$src' '$dst'")
    }

    fun mkdirs(path: String) {
        try {
            File(path).mkdirs()
        } catch (_: Throwable) {
        }
    }

    /** Copy every regular file from src into dst (files only, non-destructive). */
    fun syncDir(src: File, dst: File) {
        if (!src.exists()) return
        try {
            dst.mkdirs()
            src.listFiles()?.forEach { f ->
                if (f.isFile) copyFile(f, File(dst, f.name))
            }
        } catch (t: Throwable) {
            warnOnce("sync_${src.path}", "syncDir ${src.path} failed: ${t.message}")
        }
    }
}
