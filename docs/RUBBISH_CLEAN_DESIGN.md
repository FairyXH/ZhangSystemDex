# ZhangSystemDex 垃圾清理子系统 — 设计文档

> 状态：开发中（P1 起）
> 关联：`docs/AGENT_CONTEXT.md`、`TODO_PLAN.md`
> 需求来源：WebUI 底栏新增「清理」按钮，支持定时 + 手动清理；所有清理类型开关化，低风险默认选中。

---

## 1. 需求与约束（用户确认）

| 项 | 结论 |
|---|---|
| 清理范围 | 通用垃圾 + **微信专清** + **QQ专清**（各独立分组） |
| 删除方式 | **直接删除（unlink）**，无隔离区。原 `StorageIsolationModule.CleanedRubbish` 逻辑不动 |
| 开关粒度 | 每一类清理独立开关；**低风险默认开**，中高风险默认关由用户勾选 |
| 路径策略 | 外部存储一律用 **`/data/media/<userId>/...`**，禁止经 `/sdcard`、`/storage/emulated`、`/mnt/user` |
| 安全审查 | **必须有中心化删除审查函数**，防路径穿越/误删（如删到根目录）；支持用户自定义**违禁词 + 违禁路径** |
| 定时 | 支持定时清理（默认息屏执行），受总开关 + 规则开关双重门控 |
| 手动 | WebUI 扫描 → 勾选 → 执行；DebugMenu 提供入口 |

### 红线
- 不经 `/sdcard` 等挂载别名访问外置存储。
- 任何删除必须走 `RubbishGuard.safeDelete()` 唯一入口。
- 绝不触碰：微信 `EnMicroMsg.db`/`SnsMicroMsg.db`/`FTS5Index*`/`AppBrandComm.db`；QQ `databases/`；各包 `shared_prefs`、`databases`。
- 目标包运行中（进程存在）时跳过其私有目录清理（可配是否强制）。

---

## 2. 真机实测数据（2026-10-03，用于规则校准）

### 微信 `com.tencent.mm`
| 路径 | 占用 | 归类 |
|---|---|---|
| `/data/user/0/com.tencent.mm/cache/temp` | **2546 MB** | 低风险（临时） |
| `/data/user/0/com.tencent.mm/files/xlog` | **1044 MB** | 低风险（日志） |
| `/data/user/0/com.tencent.mm/files/public` | 375 MB | 中风险 |
| `/data/user/0/com.tencent.mm/MicroMsg/CheckResUpdate` | 250 MB | 中风险（可重建） |
| `/data/user/0/com.tencent.mm/MicroMsg/AFFUDRPath` | 239 MB | 中风险（可重建） |
| `/data/user/0/com.tencent.mm/MicroMsg/webview_tmpl` | 207 MB | 中风险（可重建） |
| `/data/user/0/com.tencent.mm/MicroMsg/appbrand` | 199 MB | 中风险（小程序缓存） |
| `/data/user/0/com.tencent.mm/cache/xweb_cache` | 139 MB | 低风险 |
| `/data/user/0/com.tencent.mm/cache/skyline_cache` | 33 MB | 低风险 |
| `/data/user/0/com.tencent.mm/cache/sns_ad_landingpages` | 20 MB | 低风险（广告） |
| `/data/user/0/com.tencent.mm/cache/appbrand` | 41 MB | 低风险 |
| `/data/media/0/Android/data/com.tencent.mm/MicroMsg/xlog` | 146 MB | 低风险（日志） |
| `/data/media/0/Android/data/com.tencent.mm/cache/Cache` | 47 MB | 低风险 |
| `/data/user/0/com.tencent.mm/MicroMsg/<hash>/c2c_temp` | 40 MB | 低风险 |
| `/data/user/0/com.tencent.mm/MicroMsg/<hash>/draft` | 27 MB | 低风险 |
| `/data/user/0/com.tencent.mm/MicroMsg/<hash>/mediaOpt` | 9 MB | 低风险 |
| `/data/user/0/com.tencent.mm/MicroMsg/<hash>/message/media` | 1380 MB | **高风险**（聊天媒体，仅按时间可选） |

### QQ `com.tencent.mobileqq`
| 路径 | 占用 | 归类 |
|---|---|---|
| `/data/user/0/com.tencent.mobileqq/files` | 1323 MB | 分类处理 |
| `/data/user/0/com.tencent.mobileqq/app_xwalk_*` | 181 MB | 低风险（可重建） |
| `/data/user/0/com.tencent.mobileqq/app_libs` / `app_lib` | 113+97 MB | **禁止**（核心库） |
| `/data/user/0/com.tencent.mobileqq/cache` | 50 MB | 低风险 |
| `/data/user/0/com.tencent.mobileqq/app_tbs` | 49 MB | 中风险 |
| `/data/user/0/com.tencent.mobileqq/app_webview_*` | 12+4×4 MB | 低风险 |
| `/data/user/0/com.tencent.mobileqq/files/onelog` | 日志 | 低风险 |
| `/data/user/0/com.tencent.mobileqq/files/commonlog` | 日志 | 低风险 |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/shortvideo` | 77 MB | 低风险（视频缓存） |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic` | 55 MB | 中风险 |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/diskcache` | 3.5 MB | 低风险 |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/QQ_Favorite` | 46 MB | **禁止**（收藏） |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv` | 33 MB | 中风险 |
| `/data/media/0/Android/data/com.tencent.mobileqq/Tencent/wxminiapp` | 46 MB | 中风险 |

---

## 3. 架构

```
core/rubbish/
  RubbishGuard.kt      # ★ 中心化删除审查（唯一删除入口）
  CleanRule.kt         # 规则数据结构 + 风险等级 + 匹配模式
  RubbishRuleSet.kt    # 全量规则表（通用/微信/QQ）
  RubbishCleaner.kt    # 扫描引擎 + 执行引擎（只读扫描与删除分离）
modules/
  RubbishCleanModule.kt  # DaemonLoop 定时承载（或并入 SystemTuningModule.heavyTick）
```

### 3.1 `RubbishGuard` —— 中心化删除审查（安全核心）

**唯一删除入口**，所有删除必须经此。审查链：

1. **规范化**：`path → File.canonicalFile`（解析 `..`、软链）
2. **长度与层级**：路径层级 < 3 层拒绝（如 `/data`）；空/根 `/` 拒绝
3. **允许根白名单**：必须落在以下之一（否则拒绝）
   - `/data/media/<数字>/...`
   - `/data/user/<数字>/...`
   - `/data/data/...`（仅限规则声明）
   - `/data/anr`、`/data/tombstones`、`/data/system/dropbox`（仅限对应规则）
4. **系统关键目录黑名单**：`/`、`/data`、`/data/adb`、`/data/system`（除 dropbox）、`/system`、`/vendor`、`/data/user/0`（包根本身）、`/data/media/0`（用户根本身）
5. **用户自定义违禁路径**：`rubbish_guard.conf` 的 `deny_path=` 逐条前缀匹配 → 命中拒绝
6. **用户自定义违禁词**：`rubbish_guard.conf` 的 `deny_word=` 对完整路径做子串匹配 → 命中拒绝
7. **递归校验**：删除目录前，遍历其下**每个**条目再次执行 1–6（防软链逃逸）
8. **审计**：所有放行/拒绝都写 `log/rubbish_clean.log`

```kotlin
object RubbishGuard {
    fun check(path: String, ruleId: String): Verdict          // ACCEPT / REJECT(reason)
    fun safeDelete(path: String, ruleId: String): DeleteResult // 唯一实际执行删除
}
```

### 3.2 规则数据结构

```kotlin
enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class MatchMode { DIR_CONTENT, DIR_SELF, GLOB, EMPTY_DIR, OLDER_THAN }

data class CleanRule(
    val id: String,
    val name: String,
    val group: String,            // general / wechat / qq
    val risk: RiskLevel,
    val defaultOn: Boolean,
    val mode: MatchMode,
    val roots: List<String>,      // 支持 <u> 占位（userId）
    val pattern: String = "",     // GLOB 用
    val ageDays: Int = 0,         // OLDER_THAN 用
    val keep: List<String> = emptyList(),
    val switchKey: String,        // switches.conf 键
    val note: String = ""
)
```

### 3.3 配置键（`switches.conf`，走 `ensureParamLines` 只增不覆盖）

```
# ===== 垃圾清理 =====
rubbish_clean_enable                # 总开关（默认 false）
rubbish_clean_screen_off_only       # 定时清理仅息屏（默认 true）

# 通用（低风险默认 true）
rubbish_rule_app_cache              # 应用缓存
rubbish_rule_thumbnails             # 缩略图
rubbish_rule_temp_files             # 临时文件
rubbish_rule_empty_dirs             # 空目录/0字节
rubbish_rule_system_crash_logs      # 崩溃日志
rubbish_rule_app_logs               # 应用日志目录
# 通用（中高风险默认 false）
rubbish_rule_apk_leftover           # APK 残留
rubbish_rule_ad_cache               # 广告缓存
rubbish_rule_uninstalled_leftover   # 卸载残留
rubbish_rule_big_files_list         # 大文件（仅列出）
rubbish_rule_duplicate_files        # 重复文件（高危）

# 微信专清（低风险默认 true）
rubbish_rule_wx_logs                # 日志（xlog）
rubbish_rule_wx_temp                # 临时（cache/temp）
rubbish_rule_wx_webview_cache       # webview/xweb/skyline 缓存
rubbish_rule_wx_media_cache         # 外置媒体缓存（Cache/ThumbVideoCache/wxacache）
# 微信专清（中高风险默认 false）
rubbish_rule_wx_rebuildable         # 可重建资源（webview_tmpl/CheckResUpdate/AFFUDRPath/appbrand）
rubbish_rule_wx_public              # files/public
rubbish_rule_wx_chat_media          # 聊天媒体按时间（ageDays 可配，默认 30）

# QQ专清（低风险默认 true）
rubbish_rule_qq_logs                # 日志
rubbish_rule_qq_cache               # 缓存（cache/app_xwalk/app_webview）
rubbish_rule_qq_media_cache         # 外置媒体缓存（shortvideo/diskcache）
# QQ专清（中高风险默认 false）
rubbish_rule_qq_file_recv           # 接收文件（QQfile_recv）
rubbish_rule_qq_miniapp             # 小程序缓存（wxminiapp/mini）
rubbish_rule_qq_chatpic             # chatpic

# 规则参数
rubbish_wx_chat_media_days=30
rubbish_big_file_mb=100
```

### 3.4 用户自定义审查配置 `rubbish_guard.conf`

```
# 违禁路径（前缀匹配，命中即拒绝删除）
deny_path=/data/adb
deny_path=/data/system

# 违禁词（路径子串匹配，命中即拒绝删除）
deny_word=EnMicroMsg.db
deny_word=shared_prefs
```

### 3.5 HTTP 端点

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/rubbish/rules` | GET | 规则表 + 开关状态 + 风险等级 |
| `/api/rubbish/scan` | POST | 异步扫描，返回 taskId |
| `/api/rubbish/scan/status` | GET | 扫描进度/结果 |
| `/api/rubbish/clean` | POST | 按 ruleIds 执行（含 dryRun） |
| `/api/rubbish/history` | GET | 审计日志尾部 |
| `/api/rubbish/guard` | GET/POST | 读取/保存违禁词与违禁路径 |

### 3.6 WebUI

底栏新增第 5 Tab「清理」（🧹）。`pane-clean` 内容：
- 顶部统计卡（扫描后可释放）
- 一键扫描 + 进度条
- 分组列表：通用低/中/高、微信专清、QQ专清（每项：名称 + 占用 + 风险标签 + iOS 开关）
- 清理选中项（高危二次确认）
- 历史 / 违禁配置编辑

---

## 4. 实施阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | Git 仓库绑定 + docs 基线 | ✅ 完成 |
| P1 | `RubbishGuard` + 规则表 + 只读扫描引擎 | 进行中 |
| P2 | 删除执行 + 审计日志 | 待办 |
| P3 | ConfigManager 配置接入 | 待办 |
| P4 | HttpBackend 端点 | 待办 |
| P5 | WebUI 清理 Tab | 待办 |
| P6 | heavyTick 定时接入 | 待办 |
| P7 | SelfTest / DebugMenu | 待办 |
| P8 | 构建部署与同步 | 待办 |

---

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 路径穿越误删 | `RubbishGuard` 规范化 + 白名单 + 递归逐项校验 |
| 软链逃逸 | 用 `canonicalFile` 解析真实路径后校验；删除时用 `NOFOLLOW` 语义 |
| 微信运行中删除异常 | 检测目标包进程，运行中跳过（可配强制） |
| 用户误配违禁项 | 配置为"追加拒绝"，只增不减，越配越安全 |
| 定时清理影响体验 | 默认仅息屏；受总开关门控 |
