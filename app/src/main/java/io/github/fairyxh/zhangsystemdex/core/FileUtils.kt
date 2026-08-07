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
            if (warned.add(key)) Logger.w("FileUtils", "$msg（仅记录一次）")
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
        // Ensure the parent exists first; /data/media (FUSE) can reject
        // Java mkdirs, so fall back to a shell mkdir -p.
        val parent = dst.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
            if (!parent.exists()) ShellExecutor.run("mkdir -p '${parent.path}'")
        }
        return try {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            true
        } catch (t: Throwable) {
            // Java File API can fail on FUSE mounts; retry with shell cp.
            val rc = ShellExecutor.runExit("cp -f '${src.path}' '${dst.path}'")
            if (rc == 0) {
                true
            } else {
                Logger.w("FileUtils", "复制 ${src.path} -> ${dst.path} 失败: ${t.message} (shell rc=$rc)")
                false
            }
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

    /**
     * Release a module folder to a target: recursive copy with overwrite,
     * creating the target directory when missing. Equivalent to
     * `cp -rf <src>/. <dst>/` for the src content. Returns the number of
     * files copied, or -1 when the source does not exist (a pruned module is
     * allowed to omit the folder entirely).
     */
    fun copyDirRecursive(src: File, dst: File): Int {
        if (!src.exists() || !src.isDirectory) return -1
        var copied = 0
        try {
            if (!dst.exists()) {
                dst.mkdirs()
                if (!dst.exists()) ShellExecutor.run("mkdir -p '${dst.path}'")
            }
            src.listFiles()?.forEach { f ->
                val target = File(dst, f.name)
                if (f.isDirectory) {
                    copied += copyDirRecursive(f, target)
                } else if (f.isFile) {
                    if (copyFile(f, target)) copied++
                }
            }
        } catch (t: Throwable) {
            warnOnce("copyDir_${src.path}", "复制目录 ${src.path} 失败: ${t.message}")
        }
        return copied
    }
}
