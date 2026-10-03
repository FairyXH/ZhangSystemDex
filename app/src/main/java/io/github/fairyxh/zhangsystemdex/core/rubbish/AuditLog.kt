package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 垃圾清理审计日志（`log/rubbish_clean.log`）。
 *
 * 记录每一次放行（DELETE）与拒绝（REJECT/FAIL），含时间、规则、路径、字节数、
 * 备注。用于事后追溯「到底删了什么、为什么拒绝」。文件超过 [MAX_SIZE] 自动滚动
 * 一份 `.1` 备份。
 *
 * 与统一日志 [Logger] 分离：审计日志**不**受 `log_enabled` 总开关控制，
 * 因为删除行为的可追溯性属于安全要求，必须始终可查。
 */
class AuditLog {
    @Volatile
    private var dir: File = File("/data/adb/Zhang/log")

    private val lock = Any()

    fun setDir(d: File) {
        synchronized(lock) { dir = d }
    }

    fun log(ruleId: String, action: String, path: String, bytes: Long, note: String) {
        val line = buildString {
            append(timestamp())
            append("\t").append(action)
            append("\t[").append(ruleId).append("]")
            append("\t").append(bytes).append("B")
            append("\t").append(path)
            if (note.isNotEmpty()) append("\t# ").append(note)
        }
        synchronized(lock) {
            try {
                dir.mkdirs()
                val f = File(dir, FILE_NAME)
                if (f.exists() && f.length() > MAX_SIZE) {
                    val bak = File(dir, "$FILE_NAME.1")
                    if (bak.exists()) bak.delete()
                    f.renameTo(bak)
                }
                f.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
        // 同时给统一日志一条摘要（受 log_enabled 控制，可能静默）。
        if (action != "DELETE") {
            Logger.w("RubbishAudit", "$action [$ruleId] $path ($note)")
        }
    }

    /** 记录一次清理会话的开始/结束汇总。 */
    fun logSession(ruleIds: List<String>, files: Int, bytes: Long, rejected: Int) {
        val line = buildString {
            append(timestamp())
            append("\t").append("SESSION")
            append("\trules=").append(ruleIds.joinToString(","))
            append("\tfiles=").append(files)
            append("\tbytes=").append(bytes)
            append("\trejected=").append(rejected)
        }
        synchronized(lock) {
            try {
                dir.mkdirs()
                File(dir, FILE_NAME).appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
        Logger.i("RubbishAudit", "清理会话: rules=${ruleIds.joinToString(",")} files=$files bytes=$bytes rejected=$rejected")
    }

    /** 读取最后 [lines] 行（供 WebUI 历史查看）。 */
    fun tail(lines: Int): List<String> {
        val f = File(dir, FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            val all = f.readLines()
            if (all.size <= lines) all else all.subList(all.size - lines, all.size)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun filePath(): String = File(dir, FILE_NAME).path

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        const val FILE_NAME = "rubbish_clean.log"
        private const val MAX_SIZE = 2L * 1024 * 1024
    }
}