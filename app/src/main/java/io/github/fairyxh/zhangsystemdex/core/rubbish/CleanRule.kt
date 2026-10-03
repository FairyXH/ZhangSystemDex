package io.github.fairyxh.zhangsystemdex.core.rubbish

/** 风险等级。LOW 默认开，MEDIUM/HIGH 默认关、由用户勾选。 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * 匹配模式。
 *
 * 说明：`APK_SCAN` / `BIG_FILE_SCAN` / `DUP_SAME_SIZE` / `DUP_CONTENT` 属于
 * 「深度扫描」模式，需要读取文件内容或跨目录比对，代价较高，因此配套
 * [ScanCache] 缓存（首次全扫、之后仅增量）。
 */
enum class MatchMode {
    /** 清空目录内容，保留目录本身。 */
    DIR_CONTENT,

    /** 删除整个路径（目录或文件）。 */
    DIR_SELF,

    /** 在 roots 下递归匹配 pattern（glob，支持 * 与 ?），只匹配 root 的直接子项。 */
    GLOB,

    /** 删除 roots 下（含子层）的空目录与 0 字节文件。 */
    EMPTY_DIR,

    /** 在 roots 下递归匹配，且最后修改时间早于 ageDays 天。 */
    OLDER_THAN,

    /** 递归扫描 roots，识别文件头为 ZIP 且内含 AndroidManifest.xml 的 APK（无论后缀）。 */
    APK_SCAN,

    /** 递归扫描 roots，列出超过 bigFileMb 的大文件（仅列出，不删）。 */
    BIG_FILE_SCAN,

    /** 在 roots 下递归查找「同尺寸」的文件组（候选重复，轻量预筛）。 */
    DUP_SAME_SIZE,

    /** 在 roots 下递归查找「同尺寸 + 同内容哈希」的重复文件（保留每个组最新一份）。 */
    DUP_CONTENT,

    /**
     * 泛化垃圾扫描：按「文件名模式 + 目录名模式 + 文件头特征」识别所有 App 的潜在垃圾。
     *
     * 覆盖：缓存、日志、临时、崩溃转储、零字节、空目录、缩略图、广告缓存等。
     * 与 APK_SCAN 的区别：不限定类型，靠多组特征联合判定，适用范围覆盖全部 App。
     */
    JUNK_SCAN,
}

/** 功能分组，用于 WebUI 分区展示。 */
enum class RuleGroup(val key: String, val title: String) {
    GENERAL("general", "通用清理"),
    DEEP("deep", "深度扫描"),
    SYSTEM("system", "系统级清理"),
    WECHAT("wechat", "微信专清"),
    QQ("qq", "QQ 专清"),
}

/**
 * 一条清理规则。
 *
 * [roots] 支持 `<u>` 占位符，执行时替换为用户 id（如 `/data/user/<u>` -> `/data/user/0`）。
 * 所有路径必须是**真实路径**（`/data/media/<u>`、`/data/user/<u>/<pkg>`），
 * 禁止使用 `/sdcard`、`/storage/emulated`、`/mnt/user` 等挂载别名。
 */
data class CleanRule(
    val id: String,
    val name: String,
    val group: RuleGroup,
    val risk: RiskLevel,
    val defaultOn: Boolean,
    val mode: MatchMode,
    val roots: List<String>,
    val pattern: String = "",
    val ageDays: Int = 0,
    /** 命中这些名称的子项（文件名/目录名，精确匹配）不删除。 */
    val keep: List<String> = emptyList(),
    /** 对应 switches.conf 的键；空则不可单独开关（仅随组）。 */
    val switchKey: String = "",
    /** WebUI 提示文案。 */
    val note: String = "",
    /** 该规则作用的目标包（用于「包运行中跳过」判定）；空则不判定。 */
    val targetPackage: String = "",
    /** 深度扫描最小文件尺寸（字节）；0 表示不限制。用于 APK_SCAN / BIG_FILE_SCAN / DUP_*。 */
    val minBytes: Long = 0L,
    /** BIG_FILE_SCAN 的大文件阈值（MB）。 */
    val bigFileMb: Int = 0,
    /** 递归深度上限（0=不限）；用于限制深层目录遍历代价。 */
    val maxDepth: Int = 0,
    /** 是否仅列出不删除（如 BIG_FILE_SCAN）。 */
    val listOnly: Boolean = false,
    /** DUP_* 模式：保留策略（保留每组最新一份，删除其余）。 */
    val keepNewest: Boolean = true,
) {
    fun expandRoots(userId: Int): List<String> = roots.map { it.replace("<u>", userId.toString()) }
}