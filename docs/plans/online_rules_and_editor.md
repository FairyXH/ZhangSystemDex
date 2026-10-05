# 计划：在线规则（多源直链 + 定期拉取）+ 规则编辑器（导入/导出 JSON）

> 需求（2026-10-05）：既然有在线规则，我们的在线规则怎么制定？
> - 有专门界面让用户手动输入**规则链接直链**；
> - 直链**定期自动拉取**，支持**多套**在线规则；
> - 另有**规则编辑器界面**，可**导出 JSON 规则**。

## 一、总体设计

### 1.1 存储布局（`{rootDir}=/data/adb/Zhang`）
```
online_rules/
  index.json                # 订阅源索引（顺序、总数）
  <sourceId>/
    meta.json               # {id,name,url,enabled,intervalHours,lastFetchMs,lastStatus,lastError,ruleCount,sha256}
    rules.json              # 最近一次成功拉取的规则缓存（原始 JSON）
user_rules.json             # 用户本地自定义规则（编辑器保存）
```
- `sourceId`：`src_<8位随机hex>`，稳定不变，作为目录名与索引键。
- 规则文件 schema 与 CZero 对齐、并适配我们现有 `CleanRule`（见 1.3）。

### 1.2 拉取与合并
- **多源**：任意数量订阅源，每个独立 `enabled` 与 `intervalHours`（默认 24h）。
- **定期拉取**：由 daemon 新增的 `OnlineRuleModule`（DaemonLoop）每分钟 tick，检查到期源；
  或用 HttpBackend 的按需触发 + daemon 周期。**采用 DaemonLoop 周期**，符合项目既有模块化范式。
- **拉取方式**：`java.net.HttpURLConnection` GET 直链；超时 10s；限制响应体大小（如 2MB）。
- **校验**：JSON 解析 + schema 基本校验（groups[].paths 必须绝对路径且无 `..`）；
  计算 sha256 存 meta；失败**保留旧缓存**并记录 lastStatus/lastError。
- **合并语义（安全第一）**：在线规则与用户规则仅**新增**规则，绝不覆盖/删除内建
  `RubbishRuleSet`；所有路径在扫描/删除时仍经 `RubbishGuard` 审查。
- **只增不删**（借鉴 CZero）：重拉只更新该源自身规则，不清空其他源。

### 1.3 规则文件 schema（在线/用户/导出 通用）
```json
{
  "version": 1,
  "name": "示例规则集",
  "author": "someone",
  "description": "可选说明",
  "groups": [
    {
      "name": "浏览器缓存",
      "enabled": true,
      "mode": "DIR_CONTENT",       // DIR_CONTENT|DIR_SELF|GLOB|EMPTY_DIR|OLDER_THAN|APK_SCAN|BIG_FILE_SCAN|DUP_CONTENT|DUP_SAME_SIZE|JUNK_SCAN|UNINSTALLED_SCAN
      "risk": "LOW",               // LOW|MEDIUM|HIGH
      "defaultOn": true,
      "roots": ["/data/media/<u>/Android/data/*/cache"],
      "pattern": "",               // GLOB 模式必填
      "ageDays": 0,
      "keep": ["databases","shared_prefs"],
      "note": "清理浏览器缓存"
    }
  ]
}
```
- `<u>` 占位符保留，执行时替换用户 id。
- 导出即为该 schema；导入时可来自本文件、剪贴板或 URL。

### 1.4 与现有 `CleanRule` / `RubbishRuleSet` 的桥接
- 新增 `RuleDoc`（JSON 模型）与 `RuleDocCodec`（解析/序列化 + 校验）。
- 新增 `OnlineRuleStore`：读写 `online_rules/` 与 `user_rules.json`，产出 `List<CleanRule>`。
- `RubbishCleaner.selectRules()` 改为合并三来源：
  `RubbishRuleSet.ALL (内建) + OnlineRuleStore.allRules()`；
  并以 `online:true` / `user:true` 标记来源，供 UI 区分。
- 在线/用户规则 id 前缀：`ol_<srcId>_<n>` / `ur_<n>`，避免与内建冲突。

### 1.5 后端 API（HttpBackend）
```
GET  /api/rules/online/list                 列出所有订阅源（meta）
POST /api/rules/online/add    {name,url,intervalHours,enabled}
POST /api/rules/online/update {id,enabled?,intervalHours?,url?,name?}
POST /api/rules/online/remove {id}
POST /api/rules/online/fetch  {id}         立即拉取（阻塞或异步）
GET  /api/rules/online/content?id=          取该源缓存原文（编辑器导入）
POST /api/rules/validate      {json}       校验规则 JSON，返回规范化结果或错误
GET  /api/rules/user/get                    读 user_rules.json
POST /api/rules/user/set      {json}       保存用户规则（校验后）
GET  /api/rules/user/export                 导出（内建+在线+用户合并的可编辑子集）
POST /api/rules/import        {json,source}导入（保存为用户规则或在线源）
```

### 1.6 WebUI
- 清理 Tab `cleanhead` 增加按钮：**「在线规则」**、**「规则编辑器」**。
- **在线规则面板**：源列表（名称/URL/状态/上次拉取/规则数）+ 启用开关 + 立即拉取 + 删除；
  顶部「新增源」输入（名称 + 直链 + 间隔）。
- **规则编辑器面板**：
  - 分组列表（可增删），每组可编辑 name/mode/risk/roots/pattern/keep/note；
  - 「从 URL 导入」「导入 JSON 文本」「导出 JSON（复制 / 下载）」「保存到用户规则」；
  - 校验错误即时提示。

## 二、实施阶段

1. **数据层**：`RuleDoc` + `RuleDocCodec`（schema 校验）+ 单元自检。
2. **存储/拉取**：`OnlineRuleStore`（读写/合并/导入导出）+ `OnlineRuleFetcher`（HTTP+缓存）。
3. **合并进扫描**：改 `RubbishCleaner.selectRules()` 汇入在线/用户规则；`CleanRule` 增
   `source` 字段（INTERNAL/ONLINE/USER）。
4. **Daemon 周期**：`OnlineRuleModule`（DaemonLoop）到点拉取。
5. **后端 API**：HttpBackend 增 1.5 各端点。
6. **WebUI**：新增面板与编辑器。
7. **自检**：SelfTest 增 `在线规则.*`、`规则编辑器.*` 用例。
8. **文档 + 构建 + 部署 + Git**。

## 三、安全红线（不可违背）
- 在线/用户规则的路径同样经 `RubbishGuard`（违禁路径/违禁词）与 `JunkPatterns.isProtectedPath`。
- 拉取内容不执行、不落可执行；仅存 JSON。
- 校验拒绝：非绝对路径、含 `..`、根目录、长度异常路径、非白名单根（沿用审查白名单）。
- 源大小上限、超时、失败保留旧缓存；不因拉取失败影响主清理流程。
