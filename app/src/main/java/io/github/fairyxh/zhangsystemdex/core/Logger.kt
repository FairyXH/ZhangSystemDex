package io.github.fairyxh.zhangsystemdex.core

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified logger. Writes to terminal (app_process stdout, redirected by the Magisk
 * service launcher to daemon.log) and to /data/adb/Zhang/log/zhang.log with rotation.
 * The whole logger can be disabled through config.conf `log_enabled`.
 */
object Logger {
    enum class Level(val tag: String) { INFO("INFO"), WARN("WARN"), ERROR("ERROR") }

    @Volatile
    private var enabled = true

    @Volatile
    private var logDir: File = File("/data/adb/Zhang/log")

    private val lock = Any()
    private const val MAX_SIZE = 1024L * 1024L

    fun init(dir: File) {
        synchronized(lock) {
            logDir = dir
            try {
                dir.mkdirs()
            } catch (_: Throwable) {
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun i(module: String, msg: String) = log(Level.INFO, module, msg, null)

    fun w(module: String, msg: String) = log(Level.WARN, module, msg, null)

    fun e(module: String, msg: String, t: Throwable? = null) = log(Level.ERROR, module, msg, t)

    private fun log(level: Level, module: String, msg: String, t: Throwable?) {
        if (!enabled) return
        val text = buildString {
            append(timestamp())
            append(" [").append(level.tag).append("] [").append(module).append("] ").append(msg)
            if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append('\n').append(sw)
            }
        }
        synchronized(lock) {
            try {
                println(text)
            } catch (_: Throwable) {
            }
            writeFile(text)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun writeFile(text: String) {
        try {
            logDir.mkdirs()
            val f = File(logDir, "zhang.log")
            if (f.exists() && f.length() > MAX_SIZE) {
                val b = File(logDir, "zhang.log.1")
                if (b.exists()) b.delete()
                f.renameTo(b)
            }
            FileOutputStream(f, true).use { it.write((text + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {
        }
    }
}
