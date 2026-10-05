package io.github.fairyxh.zhangsystemdex.core.rubbish

import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则文档模型（在线规则 / 用户规则 / 编辑器导入导出的统一 schema）。
 *
 * 与 CZero 的 `PathListDoc` 思路一致：**数据驱动**——规则以 JSON 文件形式存在，
 * 可由用户编辑、从 URL 直链导入、或导出分享。核心区别：
 *
 *  - 我们的每条规则带 [RuleDoc.Group.mode]（匹配模式）与 [RuleDoc.Group.risk]，
 *    对齐现有 [CleanRule] 的完整能力，而非 CZero 的纯「路径列表」；
 *  - 所有规则在扫描/删除时**统一经 [RubbishGuard] 审查**，数据驱动不降低安全性。
 *
 * 运行时由 [RuleDocCodec] 解析为 [CleanRule] 列表，合入 [RubbishRuleSet] 之外的新增规则。
 */
object RuleDoc {

    /** 当前 schema 版本。 */
    const val VERSION = 1

    /** 一个规则集文档。 */
    data class Doc(
        val name: String = "未命名规则集",
        val author: String = "",
        val description: String = "",
        val version: Int = VERSION,
        val groups: List<Group> = emptyList(),
    )

    /** 一个规则分组（= 一条 [CleanRule]）。 */
    data class Group(
        val name: String,
        val enabled: Boolean = true,
        val mode: MatchMode = MatchMode.DIR_CONTENT,
        val risk: RiskLevel = RiskLevel.LOW,
        val defaultOn: Boolean = true,
        val roots: List<String> = emptyList(),
        val pattern: String = "",
        val ageDays: Int = 0,
        val keep: List<String> = emptyList(),
        val note: String = "",
        /** 该分组固定归属的 UI 分组（默认 GENERAL；在线规则通常并入 GENERAL）。 */
        val uiGroup: RuleGroup = RuleGroup.GENERAL,
    )

    /** 校验结果。 */
    sealed class Result {
        data class Ok(val doc: Doc) : Result()
        data class Err(val message: String) : Result()
    }
}

/**
 * [RuleDoc] 的解析 / 序列化 / 校验。
 *
 * 校验红线（与 [RubbishGuard] 一致，宁可拒绝也不放过）：
 *  - roots 必须为**绝对路径**（以 `/` 开头）；
 *  - 禁止 `..` 段（防穿越）；
 *  - 禁止根目录与过短路径；
 *  - GLOB 模式必须有 pattern；
 *  - roots 允许含 `*` 通配段与 `<u>` 占位符（在执行期展开）。
 */
object RuleDocCodec {

    /** 单条路径的词法校验。返回 null 表示通过，否则返回错误原因。 */
    fun validatePath(raw: String): String? {
        val p = raw.trim()
        if (p.isEmpty()) return "路径为空"
        if (!p.startsWith("/")) return "必须是绝对路径: $p"
        if (p.contains("..")) return "路径不得包含 .. : $p"
        if (p == "/") return "不得为根目录"
        // 段数（忽略 `*` 与 `<u>` 段）至少 2 段，避免 "/data" 之类。
        val segs = p.split('/').filter { it.isNotEmpty() }.filter { it != "*" && it != "<u>" }
        if (segs.size < 1) return "路径层级过浅: $p"
        if (p.length > 400) return "路径过长: $p"
        return null
    }

    private fun parseMode(s: String): MatchMode? =
        MatchMode.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }

    private fun parseRisk(s: String): RiskLevel? =
        RiskLevel.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }

    private fun parseUiGroup(s: String): RuleGroup =
        RuleGroup.entries.firstOrNull { it.key.equals(s.trim(), ignoreCase = true) } ?: RuleGroup.GENERAL

    /**
     * 解析并校验 JSON 文本。
     *
     * 严格模式（[strict]=true，用于导入/保存/拉取）下，任何一条非法 group 都会
     * 导致整体失败并返回精确原因；宽松模式（[strict]=false）下跳过非法项并继续
     * （用于容错读取已有缓存）。
     */
    fun parse(text: String, strict: Boolean = true): RuleDoc.Result {
        if (text.isBlank()) return RuleDoc.Result.Err("内容为空")
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return RuleDoc.Result.Err("JSON 解析失败: ${t.message}")
        }
        val groupsArr = root.optJSONArray("groups")
            ?: return RuleDoc.Result.Err("缺少 groups 数组")

        val groups = ArrayList<RuleDoc.Group>()
        for (i in 0 until groupsArr.length()) {
            val g = groupsArr.optJSONObject(i) ?: run {
                if (strict) return RuleDoc.Result.Err("groups[$i] 不是对象")
                continue
            }
            val name = g.optString("name", "").ifBlank { "分组$i" }

            val modeStr = g.optString("mode", "DIR_CONTENT")
            val mode = parseMode(modeStr) ?: run {
                if (strict) return RuleDoc.Result.Err("groups[$i] 未知 mode: $modeStr")
                continue
            }
            val riskStr = g.optString("risk", "LOW")
            val risk = parseRisk(riskStr) ?: run {
                if (strict) return RuleDoc.Result.Err("groups[$i] 未知 risk: $riskStr")
                continue
            }

            val roots = ArrayList<String>()
            val rootsArr = g.optJSONArray("roots")
            if (rootsArr != null) {
                for (j in 0 until rootsArr.length()) {
                    val rp = rootsArr.optString(j, "").trim()
                    if (rp.isEmpty()) continue
                    val err = validatePath(rp)
                    if (err != null) {
                        if (strict) return RuleDoc.Result.Err("groups[$i].roots[$j] $err")
                        continue
                    }
                    roots += rp
                }
            }
            if (roots.isEmpty()) {
                if (strict) return RuleDoc.Result.Err("groups[$i] 无有效 roots")
                continue
            }

            val pattern = g.optString("pattern", "")
            if (mode == MatchMode.GLOB && pattern.isBlank()) {
                if (strict) return RuleDoc.Result.Err("groups[$i] GLOB 模式必须提供 pattern")
                continue
            }

            val keep = ArrayList<String>()
            g.optJSONArray("keep")?.let { ka ->
                for (j in 0 until ka.length()) {
                    val k = ka.optString(j, "").trim()
                    if (k.isNotEmpty()) keep += k
                }
            }

            groups += RuleDoc.Group(
                name = name,
                enabled = g.optBoolean("enabled", true),
                mode = mode,
                risk = risk,
                defaultOn = g.optBoolean("defaultOn", risk == RiskLevel.LOW),
                roots = roots,
                pattern = pattern,
                ageDays = g.optInt("ageDays", 0).coerceAtLeast(0),
                keep = keep,
                note = g.optString("note", ""),
                uiGroup = parseUiGroup(g.optString("uiGroup", "")),
            )
        }

        if (groups.isEmpty()) return RuleDoc.Result.Err("没有任何有效分组")
        return RuleDoc.Result.Ok(
            RuleDoc.Doc(
                name = root.optString("name", "未命名规则集").ifBlank { "未命名规则集" },
                author = root.optString("author", ""),
                description = root.optString("description", ""),
                version = root.optInt("version", RuleDoc.VERSION),
                groups = groups,
            )
        )
    }

    /** 序列化为带缩进的 JSON（导出用）。 */
    fun encode(doc: RuleDoc.Doc): String {
        val root = JSONObject()
        root.put("version", doc.version)
        root.put("name", doc.name)
        if (doc.author.isNotBlank()) root.put("author", doc.author)
        if (doc.description.isNotBlank()) root.put("description", doc.description)
        val arr = JSONArray()
        for (g in doc.groups) {
            val o = JSONObject()
            o.put("name", g.name)
            o.put("enabled", g.enabled)
            o.put("mode", g.mode.name)
            o.put("risk", g.risk.name)
            o.put("defaultOn", g.defaultOn)
            o.put("roots", JSONArray(g.roots))
            if (g.pattern.isNotBlank()) o.put("pattern", g.pattern)
            if (g.ageDays > 0) o.put("ageDays", g.ageDays)
            if (g.keep.isNotEmpty()) o.put("keep", JSONArray(g.keep))
            if (g.note.isNotBlank()) o.put("note", g.note)
            if (g.uiGroup != RuleGroup.GENERAL) o.put("uiGroup", g.uiGroup.key)
            arr.put(o)
        }
        root.put("groups", arr)
        return root.toString(2)
    }

    /**
     * 把 [RuleDoc.Group] 转为运行期 [CleanRule]。
     *
     * [idPrefix] 用于避免与内建规则 id 冲突（如 `ol_<srcId>_` 或 `ur_`）。
     * [switchKey] 决定该规则是否可经 switches.conf 单独开关：
     *  - 在线/用户规则默认**随组开关**（switchKey 留空 → 仅由「总开关」控制），
     *    以便在没有预置 switch 键的情况下也能生效；
     *  - 若调用方提供了 switchKey（如导入到已知规则），则使用之。
     */
    fun toCleanRule(
        g: RuleDoc.Group,
        idPrefix: String,
        index: Int,
        switchKey: String = "",
    ): CleanRule = CleanRule(
        id = "$idPrefix$index",
        name = g.name,
        group = g.uiGroup,
        risk = g.risk,
        defaultOn = g.defaultOn,
        mode = g.mode,
        roots = g.roots,
        pattern = g.pattern,
        ageDays = g.ageDays,
        keep = g.keep,
        switchKey = switchKey,
        note = g.note,
    )
}
