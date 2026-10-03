package io.github.fairyxh.zhangsystemdex.core.rubbish

/** 风险等级。LOW 默认开，MEDIUM/HIGH 默认关、由用户勾选。 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** 匹配模式。 */
enum class MatchMode {
    /** 清空目录内容，保留目录本身。 */
    DIR_CONTENT,

    /** 删除整个路径（目录或文件）。 */
    DIR_SELF,

    /** 在 roots 下递归匹配 pattern（glob，支持 * 与 ?）。 */
    GLOB,

    /** 删除 roots 下（含子层）的空目录与 0 字节文件。 */
    EMPTY_DIR,

    /** 在 roots 下递归匹配，且最后修改时间早于 ageDays 天。 */
    OLDER_THAN,
}

/** 功能分组，用于 WebUI 分区展示。 */
enum class RuleGroup(val key: String, val title: String) {
    GENERAL("general", "通用清理"),
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
) {
    fun expandRoots(userId: Int): List<String> = roots.map { it.replace("<u>", userId.toString()) }
}