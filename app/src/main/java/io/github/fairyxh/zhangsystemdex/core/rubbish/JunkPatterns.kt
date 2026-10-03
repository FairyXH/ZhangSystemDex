package io.github.fairyxh.zhangsystemdex.core.rubbish

/**
 * 泛化垃圾特征库（覆盖全部 App，不限微信/QQ）。
 *
 * 判定维度：
 *  1. 目录名命中 [DIR_JUNK]（如 cache / logs / tmp / crash）
 *  2. 文件名命中 [FILE_JUNK_SUFFIX]（如 .log / .tmp / .bak）或 [FILE_JUNK_NAME]
 *  3. 文件头命中 [MAGIC_JUNK]（如 Java hprof 堆转储、tombstone）
 *  4. 零字节文件 / 空目录
 *
 * 与之互补的是保护清单（[PROTECT_DIR] / [PROTECT_FILE] / [PROTECT_PATH_CONTAINS]）：
 * 即使命中特征也不清理，例如 `databases`、`shared_prefs`、`.nomedia` 等用户数据。
 *
 * 设计原则：**宁可漏判，不可误伤**。所有判定均为「特征联合命中」，单一弱特征不放行。
 */
object JunkPatterns {

    /** 目录名（小写比对）：这些目录下的内容视为可清理缓存。 */
    val DIR_JUNK: Set<String> = setOf(
        // 通用缓存
        "cache", "caches", ".cache", "cachedir", "cache2",
        "code_cache", "image_cache", "http_cache", "disk_cache",
        "volley", "picasso", "glide", "fresco", "okhttp", "okio",
        // 日志
        "log", "logs", "logfile", "logfiles", "onelog", "commonlog",
        "xlog", "tombstones", "crash", "crashes", "crashlog", "anr",
        // 临时
        "tmp", "temp", "temps", ".tmp", "cache_tmp", "tempfiles",
        // 缩略图 / 图片缓存
        "thumbnails", ".thumbnails", "thumbnail", "imagecache", "imgcache",
        "thumb", "thumbs", "thumbcache", "photocache",
        // 广告 / 埋点
        "adcache", "ad_cache", "ads", "advert", "advertising",
        "analytics", "tracking", "bugly", "umeng", "gtpush",
        // webview / 小程序
        "xweb_cache", "skyline_cache", "webview_cache", "app_webview",
        "app_tbs", "xwalk", "app_xwalk", "webcache",
        // 播放器 / 下载缓存
        "videocache", "video_cache", "shortvideo", "superplayer", "diskcache",
        "download_cache", "filecache", "acache",
        // 其他
        "dump", "dumps", "debug", "debugging", "traces", "trace",
    )

    /** 文件名后缀（小写比对）：命中即可清理。 */
    val FILE_JUNK_SUFFIX: List<String> = listOf(
        ".log", ".log.1", ".log.bak", ".log.old",
        ".tmp", ".temp", ".bak", ".old", ".orig", ".rej",
        ".dmp", ".dump", ".stacktrace", ".stack",
        ".part", ".crdownload", ".partial", ".download",
        ".trace", ".traces", ".hprof",
        ".weblog", ".alog", ".xlog", ".ullog",
    )

    /** 文件名（全等或包含，小写比对）：命中即可清理。 */
    val FILE_JUNK_NAME: List<String> = listOf(
        "thumbs.db", ".thumbdata", ".trash", "core", "core.txt",
        "hs_err_pid", "replay_pid",
    )

    /** 文件头魔数：Java 堆转储 `JAVA PROFILE`。 */
    private val MAGIC_HPROF = "JAVA PROFILE 1.0".toByteArray(Charsets.US_ASCII)

    /** 保护目录名：即使父目录命中 JUNK，这些子目录也绝不动。 */
    val PROTECT_DIR: Set<String> = setOf(
        "databases", "shared_prefs", "files", "lib", "code_cache_databases",
        "app_webview/Default", "sessions", "keychain", "keystore",
    )

    /** 保护文件名：绝不删除。 */
    val PROTECT_FILE: Set<String> = setOf(
        ".nomedia", ".nomedia.temp",
        "enmicromsg.db", "snsmicromsg.db", "appbrandcomm.db",
        "mm.sqlite", "prefs.xml", "install_id", "deviceid",
        "account", "account.xml", "com.tencent.mm_preferences.xml",
    )

    /** 保护路径片段（小写全路径包含即保护）。 */
    val PROTECT_PATH_CONTAINS: List<String> = listOf(
        "/databases/", "/shared_prefs/", "/keystore/", "/keychain/",
        "/microMsg/", "/mmkv/", "/.git/", "/node_modules/",
        "/python", "/debian", "/termux", "/adb/",
    )

    /**
     * 判断文件是否为垃圾。
     *
     * [relDir] 是文件所在目录名（用于目录特征），[name] 是文件名。
     */
    fun isJunk(name: String, parentDirName: String, file: java.io.File, size: Long): Boolean {
        val lower = name.lowercase()
        val dirLower = parentDirName.lowercase()

        // 保护优先
        if (lower in PROTECT_FILE) return false
        if (dirLower in PROTECT_DIR) return false

        // 零字节文件（除保护项）
        if (size == 0L) return true

        // 目录特征命中（父目录是缓存/日志目录）
        if (dirLower in DIR_JUNK) return true

        // 文件名后缀命中
        if (FILE_JUNK_SUFFIX.any { lower.endsWith(it) }) return true

        // 文件名特征命中
        if (FILE_JUNK_NAME.any { lower == it || lower.startsWith(it) }) return true

        // 文件头：Java hprof 堆转储
        if (lower.contains("hprof") || hasMagic(file, MAGIC_HPROF)) return true

        return false
    }

    /** 判断目录是否为空目录（可清理）。
     *
     * 保守策略：**只有明确命名如垃圾的空目录才清理**，其余一律跳过——
     *  - 保护目录（databases/shared_prefs/...）
     *  - 缓存/日志类目录（本身是清理目标，由文件级规则处理）
     *  - 包名形式目录（应用数据根）
     *  - `files` 目录及其直接子目录
     *  - 不含典型垃圾词的任意空目录（如 `android`、`DDC`、`Preset`）
     */
    fun isEmptyDir(dir: java.io.File): Boolean {
        val name = dir.name.lowercase()
        if (name in PROTECT_DIR || name in DIR_JUNK) return false
        if (looksLikePackageName(name)) return false
        if (name == "files") return false
        if (dir.parentFile?.name?.lowercase() == "files") return false
        // 仅当目录名本身具有明显的「临时/垃圾」语义时才清空目录。
        if (!looksTemporary(name)) return false
        val children = dir.listFiles() ?: return false
        return children.isEmpty()
    }

    /** 目录名是否具有明显的临时/垃圾语义（用于空目录判定）。 */
    private fun looksTemporary(name: String): Boolean {
        return name in DIR_JUNK ||
            name.startsWith("tmp") || name.startsWith("temp") ||
            name.startsWith("cache") || name.startsWith("log") ||
            name.endsWith("_tmp") || name.endsWith("_temp") ||
            name.endsWith("_cache") || name.endsWith("_log")
    }

    /**
     * 判断名称是否形如 Android 包名（`a.b.c`）或 Android/data 下的应用目录。
     * 这类目录即使为空也不清理。
     */
    fun looksLikePackageName(name: String): Boolean {
        if (name.startsWith(".")) return false
        val parts = name.split('.')
        if (parts.size < 2) return false
        // 每段必须是合法标识符（字母开头，仅含字母数字下划线）
        return parts.all { seg ->
            seg.isNotEmpty() && seg.first().isLetter() &&
                seg.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    /** 路径是否受保护（用于 scan/clean 预筛）。 */
    fun isProtectedPath(lowerPath: String): Boolean {
        return PROTECT_PATH_CONTAINS.any { lowerPath.contains(it) }
    }

    private fun hasMagic(f: java.io.File, magic: ByteArray): Boolean {
        if (!f.isFile || f.length() < magic.size) return false
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val b = ByteArray(magic.size)
                raf.readFully(b)
                b.contentEquals(magic)
            }
        } catch (_: Throwable) {
            false
        }
    }
}