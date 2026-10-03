package io.github.fairyxh.zhangsystemdex.core.rubbish

/**
 * 全量清理规则表。规则驱动 `RubbishCleaner` 的扫描与清理。
 *
 * 数据依据：2026-10-03 真机实测占用（见 `docs/RUBBISH_CLEAN_DESIGN.md` §2）。
 * 每条规则必须有唯一 [CleanRule.id] 与 [CleanRule.switchKey]。
 *
 * 红线：
 *  - 只使用真实路径（`/data/media/<u>`、`/data/user/<u>/<pkg>`），
 *    禁止 `/sdcard`、`/storage/emulated`、`/mnt/user`；
 *  - 绝不触碰聊天数据库（EnMicroMsg.db / SnsMicroMsg.db / FTS5Index* /
 *    AppBrandComm.db）、`databases/`、`shared_prefs`、QQ 收藏与核心库。
 */
object RubbishRuleSet {

    const val WX = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"

    /** 通用缓存规则绝不删除这些子目录（保数据库与配置）。 */
    private val GENERIC_KEEP = listOf(
        "databases", "shared_prefs", "lib", "code_cache", "app_webview",
    )

    /** 微信用户数据目录通配（/data/user/<u>/com.tencent.mm/MicroMsg/<32位hash>）。 */
    private const val WX_USER = "/data/user/<u>/com.tencent.mm/MicroMsg/*"

    /** QQ 外部数据根。 */
    private const val QQ_EXT = "/data/media/<u>/Android/data/com.tencent.mobileqq"

    /** 微信外部数据根。 */
    private const val WX_EXT = "/data/media/<u>/Android/data/com.tencent.mm"

    val ALL: List<CleanRule> = generalRules() + wechatRules() + qqRules()

    fun byId(id: String): CleanRule? = ALL.firstOrNull { it.id == id }

    fun bySwitchKey(key: String): CleanRule? = ALL.firstOrNull { it.switchKey == key }

    fun switchKeys(): List<String> = ALL.map { it.switchKey }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private fun generalRules(): List<CleanRule> = listOf(
        CleanRule(
            id = "app_cache",
            name = "应用缓存",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/*/cache",
                "/data/media/<u>/Android/data/*/cache",
            ),
            keep = GENERIC_KEEP,
            switchKey = "rubbish_rule_app_cache",
            note = "清理各应用 cache 目录内容，保留目录与配置",
        ),
        CleanRule(
            id = "thumbnails",
            name = "缩略图缓存",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_SELF,
            roots = listOf(
                "/data/media/<u>/DCIM/.thumbnails",
                "/data/media/<u>/Pictures/.thumbnails",
            ),
            switchKey = "rubbish_rule_thumbnails",
            note = "删除媒体缩略图缓存目录",
        ),
        CleanRule(
            id = "temp_files",
            name = "临时文件",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.GLOB,
            roots = listOf("/data/media/<u>/Download"),
            pattern = "*.tmp,*.temp,*.part,*.crdownload,*.partial",
            switchKey = "rubbish_rule_temp_files",
            note = "下载目录中的未完成/临时文件",
        ),
        CleanRule(
            id = "empty_dirs",
            name = "空目录与 0 字节文件",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.EMPTY_DIR,
            roots = listOf("/data/media/<u>/Download"),
            switchKey = "rubbish_rule_empty_dirs",
            note = "仅限下载目录，递归清理空目录与 0 字节文件",
        ),
        CleanRule(
            id = "system_crash_logs",
            name = "系统崩溃日志",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/anr",
                "/data/tombstones",
                "/data/system/dropbox",
            ),
            switchKey = "rubbish_rule_system_crash_logs",
            note = "ANR / tombstone / dropbox 崩溃记录",
        ),
        CleanRule(
            id = "app_logs",
            name = "应用日志目录",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.GLOB,
            roots = listOf("/data/media/<u>/Android/data/*"),
            pattern = "log,logs,crash,bugreport*",
            switchKey = "rubbish_rule_app_logs",
            note = "应用外部目录中的 log/logs/crash 目录",
        ),
        CleanRule(
            id = "apk_leftover",
            name = "APK 残留",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.GLOB,
            roots = listOf("/data/media/<u>/Download"),
            pattern = "*.apk,*.apks,*.xapk",
            switchKey = "rubbish_rule_apk_leftover",
            note = "删除下载目录中的 APK 安装包",
        ),
        CleanRule(
            id = "ad_cache",
            name = "广告缓存",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.GLOB,
            roots = listOf("/data/media/<u>/Android/data/*/cache"),
            pattern = "ad,ads,adcache,advert",
            switchKey = "rubbish_rule_ad_cache",
            note = "清理应用缓存中的广告 SDK 缓存目录",
        ),
        CleanRule(
            id = "uninstalled_leftover",
            name = "卸载残留",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.GLOB,
            roots = listOf("/data/media/<u>/Android/data"),
            pattern = "*",
            switchKey = "rubbish_rule_uninstalled_leftover",
            note = "已卸载应用的外部数据目录（运行时判定包是否仍安装）",
        ),
        CleanRule(
            id = "big_files_list",
            name = "大文件（仅列出）",
            group = RuleGroup.GENERAL,
            risk = RiskLevel.LOW,
            defaultOn = false,
            mode = MatchMode.OLDER_THAN,
            roots = listOf("/data/media/<u>/Download"),
            ageDays = 0,
            switchKey = "rubbish_rule_big_files_list",
            note = "扫描大文件用于人工判断，不自动删除",
        ),
        // ===== 深度扫描（文件头识别 + 缓存增量） =====
        CleanRule(
            id = "apk_scan_media",
            name = "全盘 APK 深度扫描",
            group = RuleGroup.DEEP,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.APK_SCAN,
            roots = listOf(
                "/data/media/<u>",
            ),
            minBytes = 512 * 1024L,  // 512KB 以下不可能是有效 APK
            // 排除应用正常功能的资源包（皮肤/主题/插件，虽为 APK 格式但被应用依赖）
            keep = listOf(
                "assets.apk", "mpay.pkg", "dark_theme.skin",
                "app_petal", "app_r1_webview_64", "app_tbs", "app_libs",
                "v8skin", "skin", "skins", "theme", "themes",
                "plugin", "plugins", "wxa", "miniapp",
            ),
            switchKey = "rubbish_rule_apk_scan_media",
            note = "全 media 目录递归扫描，按文件头识别被改名的 APK（.tmp/无后缀等），已排除应用资源包目录",
        ),
        CleanRule(
            id = "apk_scan_private",
            name = "私有目录 APK 深度扫描",
            group = RuleGroup.DEEP,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.APK_SCAN,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/cache/temp",
                "/data/user/<u>/com.tencent.mobileqq/files",
                "/data/user/<u>/com.baidu.netdisk/cache",
                "/data/user/<u>/com.ss.android.ugc.aweme/cache",
                "/data/user/<u>/com.taobao.taobao/cache",
            ),
            minBytes = 512 * 1024L,
            keep = listOf(
                "assets.apk", "app_petal", "app_r1_webview_64", "app_tbs",
                "plugin", "plugins", "skin", "theme",
            ),
            switchKey = "rubbish_rule_apk_scan_private",
            note = "扫描常见应用**缓存目录**中的安装包（含无后缀/改名，靠文件头识别）",
        ),
        CleanRule(
            id = "big_files_private",
            name = "私有目录大文件（仅列出）",
            group = RuleGroup.DEEP,
            risk = RiskLevel.LOW,
            defaultOn = false,
            mode = MatchMode.BIG_FILE_SCAN,
            roots = listOf(
                "/data/user/<u>",
            ),
            bigFileMb = 50,
            listOnly = true,
            switchKey = "rubbish_rule_big_files_private",
            note = "递归列出各应用私有目录中超过 50MB 的文件（仅列出不删），用于人工判断",
        ),
        CleanRule(
            id = "dup_files_media",
            name = "重复文件（media，按内容）",
            group = RuleGroup.DEEP,
            risk = RiskLevel.HIGH,
            defaultOn = false,
            mode = MatchMode.DUP_CONTENT,
            roots = listOf(
                "/data/media/<u>/Download",
                "/data/media/<u>/Documents",
                "/data/media/<u>/Pictures",
                "/data/media/<u>/DCIM",
            ),
            minBytes = 1024 * 1024L,  // 1MB 以上才纳入比对
            keepNewest = true,
            switchKey = "rubbish_rule_dup_files_media",
            note = "按「尺寸+内容哈希」找出完全相同的重复文件，每组保留最新一份（高风险，不可恢复）",
        ),
        CleanRule(
            id = "dup_wechat_tpc",
            name = "微信重复下载文件（TPCFile）",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DUP_SAME_SIZE,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/cache/temp/TPCFile",
            ),
            minBytes = 1024 * 1024L,
            keepNewest = true,
            switchKey = "rubbish_rule_dup_wechat_tpc",
            note = "微信临时目录中同名同尺寸的重复下载文件（实测 59 个相同 20MB 文件 = 1.1GB），保留最新一份",
            targetPackage = WX,
        ),
        // ===== 泛化垃圾扫描（覆盖全部 App + 整个 /data）=====
        CleanRule(
            id = "junk_all_apps",
            name = "全部应用垃圾扫描",
            group = RuleGroup.DEEP,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.JUNK_SCAN,
            roots = listOf("/data/user/<u>"),
            maxDepth = 12,
            switchKey = "rubbish_rule_junk_all_apps",
            note = "按特征（cache/logs/tmp/crash/零字节/hprof 等）扫描**全部应用**私有目录的垃圾，不限微信QQ",
        ),
        CleanRule(
            id = "junk_media_apps",
            name = "外部存储应用垃圾",
            group = RuleGroup.DEEP,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.JUNK_SCAN,
            roots = listOf("/data/media/<u>/Android/data"),
            maxDepth = 10,
            switchKey = "rubbish_rule_junk_media_apps",
            note = "扫描外部存储各应用目录中的缓存/日志/临时垃圾（全部 App）",
        ),
        CleanRule(
            id = "system_junk_data",
            name = "系统级垃圾（整个 /data）",
            group = RuleGroup.SYSTEM,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.JUNK_SCAN,
            roots = listOf(
                "/data/log",
                "/data/bootchart",
                "/data/debugging",
                "/data/dropbox",
                "/data/ss",
                "/data/resource-cache",
                "/data/ramdump",
                "/data/system/dropbox",
                "/data/system_ce/0/recent_images",
                "/data/system_ce/0/snapshots",
                "/data/misc",
                "/data/vendor/camera",
                "/data/vendor/camera_rus",
                "/data/vendor/qlog",
                "/data/vendor/tombstones",
                "/data/cache",
            ),
            maxDepth = 8,
            switchKey = "rubbish_rule_system_junk_data",
            note = "清理 /data 下系统级缓存/日志/崩溃转储（已排除 /data/adb、python 解释器、系统核心）",
        ),
        CleanRule(
            id = "system_bcc_csv",
            name = "内核追踪输出（BCC CSV）",
            group = RuleGroup.SYSTEM,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.GLOB,
            roots = listOf("/data"),
            pattern = "*_bcc.csv",
            maxDepth = 1,
            switchKey = "rubbish_rule_system_bcc_csv",
            note = "内核 BCC 追踪生成的 CSV（散落在 /data 根，实测 74 个 45MB）",
        ),
    )

    // ------------------------------------------------------------------
    // 微信专清
    // ------------------------------------------------------------------

    private fun wechatRules(): List<CleanRule> = listOf(
        CleanRule(
            id = "wx_logs",
            name = "微信日志（xlog）",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/files/xlog",
                "$WX_EXT/MicroMsg/xlog",
                "$WX_EXT/MicroMsg/.tmp",
                "/data/user/<u>/com.tencent.mm/MicroMsg/xlog",
            ),
            switchKey = "rubbish_rule_wx_logs",
            note = "微信运行日志，实测占 1GB+，可安全清理",
            targetPackage = WX,
        ),
        CleanRule(
            id = "wx_temp",
            name = "微信临时缓存（cache/temp）",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/cache/temp",
                "/data/user/<u>/com.tencent.mm/cache/*/c2c_temp",
            ),
            switchKey = "rubbish_rule_wx_temp",
            note = "微信临时目录，实测占 2.5GB，可安全清理",
            targetPackage = WX,
        ),
        CleanRule(
            id = "wx_webview_cache",
            name = "微信 WebView/小程序缓存",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/cache/xweb_cache",
                "/data/user/<u>/com.tencent.mm/cache/skyline_cache",
                "/data/user/<u>/com.tencent.mm/cache/appbrand",
                "/data/user/<u>/com.tencent.mm/cache/sns_ad_landingpages",
                "/data/user/<u>/com.tencent.mm/cache/webview_com_tencent_mm",
                "/data/user/<u>/com.tencent.mm/cache/DefaultWxaCacheManager",
            ),
            switchKey = "rubbish_rule_wx_webview_cache",
            note = "WebView 与小程序缓存，会自动重建",
            targetPackage = WX,
        ),
        CleanRule(
            id = "wx_media_cache",
            name = "微信媒体缓存（外置）",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "$WX_EXT/cache/Cache",
                "$WX_EXT/cache/ThumbVideoCache",
                "$WX_EXT/cache/wxacache",
                "$WX_EXT/cache/imgcache",
                "$WX_EXT/cache/image",
                "$WX_EXT/cache/videocache",
            ),
            switchKey = "rubbish_rule_wx_media_cache",
            note = "外置存储中的图片/视频缩略图缓存",
            targetPackage = WX,
        ),
        CleanRule(
            id = "wx_rebuildable",
            name = "微信可重建资源",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mm/MicroMsg/webview_tmpl",
                "/data/user/<u>/com.tencent.mm/MicroMsg/CheckResUpdate",
                "/data/user/<u>/com.tencent.mm/MicroMsg/AFFUDRPath",
                "/data/user/<u>/com.tencent.mm/MicroMsg/appbrand",
                "/data/user/<u>/com.tencent.mm/files/public",
                "/data/user/<u>/com.tencent.mm/files/liteapp",
            ),
            switchKey = "rubbish_rule_wx_rebuildable",
            note = "模板/资源缓存（约 1GB），清理后首次使用会重新下载",
            targetPackage = WX,
        ),
        CleanRule(
            id = "wx_chat_media",
            name = "微信聊天媒体（按时间）",
            group = RuleGroup.WECHAT,
            risk = RiskLevel.HIGH,
            defaultOn = false,
            mode = MatchMode.OLDER_THAN,
            roots = listOf(
                "$WX_USER/message/media",
                "$WX_USER/image2",
                "$WX_USER/video",
                "$WX_USER/voice2",
                "$WX_USER/attachment",
            ),
            ageDays = 30,
            switchKey = "rubbish_rule_wx_chat_media",
            note = "聊天图片/视频/语音，仅删除超过设定天数的（默认 30 天），不可恢复",
            targetPackage = WX,
        ),
    )

    // ------------------------------------------------------------------
    // QQ 专清
    // ------------------------------------------------------------------

    private fun qqRules(): List<CleanRule> = listOf(
        CleanRule(
            id = "qq_logs",
            name = "QQ 日志",
            group = RuleGroup.QQ,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mobileqq/files/onelog",
                "/data/user/<u>/com.tencent.mobileqq/files/commonlog",
                "/data/user/<u>/com.tencent.mobileqq/files/tencent",
            ),
            switchKey = "rubbish_rule_qq_logs",
            note = "QQ 运行日志与埋点记录",
            targetPackage = QQ,
        ),
        CleanRule(
            id = "qq_cache",
            name = "QQ 缓存（含 XWalk/WebView）",
            group = RuleGroup.QQ,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "/data/user/<u>/com.tencent.mobileqq/cache",
                "/data/user/<u>/com.tencent.mobileqq/app_xwalk_*",
                "/data/user/<u>/com.tencent.mobileqq/app_webview_*",
                "$QQ_EXT/cache",
            ),
            switchKey = "rubbish_rule_qq_cache",
            note = "QQ 缓存与内置浏览器缓存（XWalk 约 180MB），会自动重建",
            targetPackage = QQ,
        ),
        CleanRule(
            id = "qq_media_cache",
            name = "QQ 媒体缓存（外置）",
            group = RuleGroup.QQ,
            risk = RiskLevel.LOW,
            defaultOn = true,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "$QQ_EXT/Tencent/MobileQQ/shortvideo",
                "$QQ_EXT/Tencent/MobileQQ/diskcache",
                "$QQ_EXT/files/flash_transfer_cache",
            ),
            switchKey = "rubbish_rule_qq_media_cache",
            note = "短视频与磁盘缓存，实测约 80MB",
            targetPackage = QQ,
        ),
        CleanRule(
            id = "qq_miniapp",
            name = "QQ 小程序缓存",
            group = RuleGroup.QQ,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "$QQ_EXT/Tencent/wxminiapp",
                "$QQ_EXT/Tencent/mini",
                "/data/user/<u>/com.tencent.mobileqq/app_tbs",
            ),
            switchKey = "rubbish_rule_qq_miniapp",
            note = "小程序与 TBS 内核缓存，清理后首次打开会重新下载",
            targetPackage = QQ,
        ),
        CleanRule(
            id = "qq_file_recv",
            name = "QQ 接收文件",
            group = RuleGroup.QQ,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "$QQ_EXT/Tencent/QQfile_recv",
            ),
            switchKey = "rubbish_rule_qq_file_recv",
            note = "通过 QQ 接收的文件（约 33MB），删除后不可恢复",
            targetPackage = QQ,
        ),
        CleanRule(
            id = "qq_chatpic",
            name = "QQ 聊天图片缓存",
            group = RuleGroup.QQ,
            risk = RiskLevel.MEDIUM,
            defaultOn = false,
            mode = MatchMode.DIR_CONTENT,
            roots = listOf(
                "$QQ_EXT/Tencent/MobileQQ/chatpic",
                "$QQ_EXT/Tencent/MobileQQ/photo",
            ),
            switchKey = "rubbish_rule_qq_chatpic",
            note = "聊天图片临时文件（约 55MB）",
            targetPackage = QQ,
        ),
    )
}