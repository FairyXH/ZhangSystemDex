package io.github.fairyxh.zhangsystemdex.core.rubbish

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 文件内容识别工具。
 *
 * 用于揪出「没有 .apk 后缀」的安装包、判断文件真实类型、以及为重复文件
 * 计算内容哈希。所有方法只读，绝不修改文件。
 */
object FileIdentifier {

    /** ZIP 本地文件头魔数（APK/AAB/JAR 均为 ZIP 容器）。 */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** ZIP 空归档（EOCD）——不是有效 APK，但也是 ZIP。 */
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    /** APK 内的入口文件名（小写比对）。 */
    private const val MANIFEST = "androidmanifest.xml"
    private const val CLASSES_DEX = "classes.dex"
    private const val RESOURCES_ARSC = "resources.arsc"

    /** 大类识别结果。 */
    enum class Kind { APK, ZIP, OTHER }

    /**
     * 判断文件是否为 APK。
     *
     * 判定（全部满足）：
     *  1. 文件头为 ZIP 本地头 `PK\x03\x04`；
     *  2. ZIP 中央目录中存在 `AndroidManifest.xml`（二进制 AXML）。
     *
     * 不依赖扩展名，因此可发现被改名的 APK（如 `xxx.tmp`、无后缀、
     * 微信 `TPCFile/<md5>_<ts>_1` 这类）。
     */
    fun isApk(f: File): Boolean {
        if (!f.isFile) return false
        if (!hasZipMagic(f)) return false
        return zipContains(f, MANIFEST)
    }

    /** 粗分类。 */
    fun kind(f: File): Kind = when {
        isApk(f) -> Kind.APK
        hasZipMagic(f) -> Kind.ZIP
        else -> Kind.OTHER
    }

    /** 读取文件头前 4 字节判断是否为 ZIP 容器（不区分文件后缀）。 */
    fun hasZipMagic(f: File): Boolean {
        val head = readHead(f, 4) ?: return false
        if (head.size < 4) return false
        if (matches(head, ZIP_MAGIC)) return true
        // 空归档也算 ZIP（用于重复检测分类，不算 APK）。
        return matches(head, ZIP_EMPTY)
    }

    /**
     * 在 ZIP 中央目录中查找条目（大小写不敏感，只比对 basename）。
     *
     * 实现：从文件尾部向前扫描 EOCD/中央目录签名，解析条目文件名。
     * 对于损坏或超大归档，限制扫描窗口以保证性能。
     */
    fun zipContains(f: File, entryLowerCase: String): Boolean {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len < 22) return false
            // EOCD 最快可在最后 66KB 内找到
            val scan = minOf(len, 66560L)
            val buf = ByteArray(scan.toInt())
            raf.seek(len - scan)
            raf.readFully(buf)
            val eocd = findEocd(buf)
            if (eocd < 0) {
                // 无 EOCD：退化为顺序扫描（限前 256KB，覆盖典型 APK 头部中央目录）
                return sequentialContains(f, entryLowerCase, 256 * 1024)
            }
            val cdSize = readInt32LE(buf, eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = readInt32LE(buf, eocd + 16).toLong() and 0xFFFFFFFFL
            if (cdSize <= 0 || cdOffset <= 0 || cdOffset > len) {
                return sequentialContains(f, entryLowerCase, 256 * 1024)
            }
            val toRead = minOf(cdSize, 4L * 1024 * 1024).toInt()
            val cd = ByteArray(toRead)
            raf.seek(cdOffset)
            val got = raf.read(cd)
            if (got <= 0) return false
            return scanCentralDirectory(cd, got, entryLowerCase)
        }
    }

    /**
     * 计算文件内容哈希（SHA-256，前 [maxBytes] 字节用于快速比对；
     * 0 表示全文件）。用于重复文件判定：先比尺寸，再比内容。
     */
    fun contentHash(f: File, maxBytes: Int = 0): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    total += n
                    if (maxBytes > 0 && total >= maxBytes) break
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun readHead(f: File, n: Int): ByteArray? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < n) return null
            val b = ByteArray(n)
            raf.readFully(b)
            b
        }
    } catch (_: Throwable) {
        null
    }

    private fun matches(data: ByteArray, magic: ByteArray): Boolean {
        if (data.size < magic.size) return false
        for (i in magic.indices) if (data[i] != magic[i]) return false
        return true
    }

    private fun findEocd(buf: ByteArray): Int {
        // EOCD 签名 0x06054b50，从后向前找
        for (i in buf.size - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4B.toByte() &&
                buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readInt16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** 解析中央目录，查找条目名（只比 basename，小写）。 */
    private fun scanCentralDirectory(cd: ByteArray, size: Int, entryLowerCase: String): Boolean {
        var off = 0
        while (off + 46 <= size) {
            // 中央目录头签名 0x02014b50
            if (readInt32LE(cd, off) != 0x02014b50) break
            val nameLen = readInt16LE(cd, off + 28)
            val extraLen = readInt16LE(cd, off + 30)
            val commentLen = readInt16LE(cd, off + 32)
            if (off + 46 + nameLen > size) break
            val name = String(cd, off + 46, nameLen, Charsets.UTF_8)
            val base = name.substringAfterLast('/').lowercase()
            if (base == entryLowerCase) return true
            off += 46 + nameLen + extraLen + commentLen
        }
        return false
    }

    /** 无 EOCD 时的退化方案：在文件头部窗口内顺序扫描条目名。 */
    private fun sequentialContains(f: File, entryLowerCase: String, window: Int): Boolean {
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val n = minOf(raf.length(), window.toLong()).toInt()
                val b = ByteArray(n)
                raf.readFully(b)
                val needle = entryLowerCase.toByteArray(Charsets.UTF_8)
                indexOfBytes(b, needle) >= 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}