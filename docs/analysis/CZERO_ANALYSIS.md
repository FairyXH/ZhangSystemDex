# CZero (com.web.czero) 逆向分析报告

> 分析对象：`/data/media/0/Download/Files/com.web.czero/com.web.czero.apk`（v1.2.9 / versionCode 10209）
> 与配套模块包 `CZero_Mod.zip`；模块已装于 `/data/adb/modules/CZero`。
> 分析目的：1) 判定是否存在「捐赠模式 + 联网验证」；2) 学习其清理方式、规则系统设计，作为 ZhangProtect 垃圾清理模块的参考。
> 分析时间：2026-10-05。JADX 输出：`com.web.czero/_jadx/`（4062 类，全量混淆为 `defpackage/*.java`）。

---

## 一、结论速览

| 问题 | 结论 |
|---|---|
| 是否捐赠模式？ | **是**。存在完整的「捐赠者(Supporter)」等级：捐赠页、爱发电跳转、金额统计。 |
| 是否联网验证？ | **是**。捐赠解锁走 `https://verify.czeropage.top` 云端校验订单/激活码，绑定设备，token 落盘。 |
| 验证强度 | 中高。订单号/激活码正则校验 + 云端换 token + 设备指纹绑定 + 离线缓存 + 吊销(revoked)回收。 |
| 是否硬门槛 | 否。免费版可用基本清理；捐赠解锁「云规则同步、GC 回收、精细化压制、后台压制」等进阶项。 |
| 规则系统 | 数据驱动：模块侧 JSON 规则文件 + 原生 ELF 执行器；App 侧负责编辑/云同步/下发。 |

---

## 二、捐赠 / 授权（联网验证）机制

### 2.1 网络端点

```
https://verify.czeropage.top        # 捐赠/激活码校验主服务（POST，JSON，Bearer=<token>）
https://app.czeropage.top/api/app   # 云规则集拉取 + 本机规则上报（POST）
https://app.czeropage.top/api/messages   # 站内消息/公告
https://update.czeropage.top/api/version # 检查更新
https://czeropage.top/download-home      # 下载页
https://ifdian.net/a/xocio               # 爱发电捐赠页（作者 Xocio）
https://github.com/Xocio/CZero           # 开源地址
```

关键代码：
- `defpackage/yb0.java`  `verify.czeropage.top` 的 HTTP 客户端（`e()` 方法）与多 IP 重试（`d()`）。
- `defpackage/yb0.b()` → 换 token，返回 `{"ok":true,"token":"...","amount":N}`。
- `defpackage/yb0.c()` → `/api/devices/unbind`（`xd.java` case 2 调用），设备解绑，`{"devices":[{handle,model,bound_at,current}],"max":N,"unbind_cooldown_left":L}`。

### 2.2 激活码 / 订单号校验

`defpackage/fc0.java`：
```java
public static final l82 b = new l82("^\\d{10,40}$");           // 纯数字订单号（10~40 位）
public static final l82 c = new l82("^CZERO-[2-9A-Z]{5}-[2-9A-Z]{5}$"); // 形如 CZERO-XXXXX-XXXXX 的激活码
public static boolean d(String s){           // 本地格式预校验，避免无效请求
    s = s.trim();
    return b.d(s) || c.d(s.toUpperCase(Locale.ROOT));
}
```
- `fc0.b()`：校验成功后将 `token` 写入 `SharedPreferences("czero_prefs").license_token`，并清 `license_revoked`；金额写入 `donation_records`。
- `yb0.f(str)`：区分输入类型 —— 以 `CZERO` 开头当作 `code`，否则当作 `order_no`。

### 2.3 设备指纹与多设备绑定

`defpackage/k80.java`：
- 优先 `getprop ro.serialno` / `ro.boot.serialno`（可 `su` 提权读取），次选 `Settings.Secure android_id`，再兜底随机 UUID。
- 一律 `SHA-256` 后存 `SharedPreferences`：`device_id_serial_v2` + `device_id_source`（`serial`/`verified`/`fallback`）。
- 请求携带 `device_id`，服务端 `max` 限制绑定设备数，`unbind` 有冷却时间。

### 2.4 授权落盘位置（root 级，防普通清数据绕过）

- SharedPreferences：`/data/data/com.web.czero/shared_prefs/czero_prefs.xml`（`license_token`、`license_revoked`、`donation_records`、`device_id_*`）。
- **双保险文件**（`defpackage/xa2.java` 的 root shell 读写，`wa1.java` / `f5.java`）：
  - `/data/adb/czero/license`
  - `/data/system/czero_license`
  - 读取顺序：SP.token → 与设备指纹比对；否则读 `/data/adb/czero/license`，为空再读 `/data/system/czero_license`；命中则回写 SP 并（若 adb 侧为空）复制到 `/data/adb/czero/license`。
  - 吊销时机：云端返回 `error == "license_revoked"` 时（`fc0.f()`）删除 token、置 `license_revoked=true`、并清空 `/data/system/czero_license`（`f5.java` case 1）。
- token 校验逻辑 `fc0.c()`：token 与当前设备指纹 `tm.c0(token, deviceId)` 比对成功才算解锁。

### 2.5 捐赠者特权（免费 vs 捐赠对照）

`R.string.donate_row_*`（见 `qc0.java`）与源码页面对照：

| 功能 | 免费 | 捐赠(Supporter) |
|---|---|---|
| 基础清理/Basic | ✅ | ✅ |
| 规则(Rules) | 受限 | ✅ 云规则同步 |
| GC 回收(Gc) | ❌ | ✅ |
| 后台压制(Bg) | ❌ | ✅ |
| 精细化压制(FineSuppress) | ❌ | ✅ |
| Zero 模式(Zero) | ❌ | ✅ |
| 应用缓存(AppCache) | ❌ | ✅ |
| 更新(Update)、趋势(Trend)、提示(Prompt) | 部分 | ✅ |

UI 埋点：`s2.java`（`donate_status_unlocked` / `dev_status_locked`）、`tf.java`（`donate_entry_title` / `donate_status_unlocked`）、`lc0.java`（对比表 `donate_th_supporter`）。

### 2.6 评价

- **属于典型的「免费功能 + 捐赠解锁进阶」模式**，而非强制付费墙。
- 联网验证为**必须**（token 来自云端 `verify.czeropage.top`），且做了：格式预校验、设备指纹绑定、多设备上限、解绑冷却、离线缓存、吊销回收 —— 设计相对完整。
- 弱点：`/data/system/czero_license`、`/data/adb/czero/license` 为明文 token 文件，root 环境下可被直接读取/复制（作者显然是「防小白」而非「防逆向」）。
- 云端规则同样走 Bearer token，未授权则 `ff.c()` 直接放弃同步。

---

## 三、模块结构与清理引擎

### 3.1 模块目录布局（`/data/adb/modules/CZero`）

```
CZero/
├── module.prop              # id/version(author=Github@Xocio)
├── config.json              # 主配置（清理开关、定时、阈值）
├── service.sh / post-fs-data.sh / customize.sh / action.sh
├── service                  # ELF：常驻守护
├── cron/timer_daemon        # ELF：定时器守护
├── status / log/            # 运行状态与日志
└── list/                    # ★ 规则与引擎目录
    ├── customize            # ELF：通用清理引擎
    ├── zero                 # ELF：Zero 模式
    ├── clean_paths.json     # ★ 清理路径规则（分组+路径+通配）
    ├── clean_whitelist.json # ★ 清理白名单（保护路径）
    ├── Tencent/             # 应用专项引擎
    │   ├── tencentmm  mobileqq  ugcaweme  check   # ELF
    ├── Emptyfolder/
    │   ├── emptyfolder       # ELF：空目录清理
    │   ├── directories.json  # 扫描根
    │   └── emptyfolder_white.json # 空目录白名单
    ├── GCclean/GCclean1      # ELF：fstrim / 脏段回收
    ├── filesort/
    │   ├── filesort          # ELF：文件整理
    │   └── sources.json      # 整理源目录
    ├── suppress/
    │   ├── suppress          # ELF：后台进程压制
    │   ├── apps.json         # 压制名单
    │   └── state / lock
    └── backup/               # 规则默认备份
```

所有 `list/*` 无扩展名文件均为 **ELF 64-bit arm64 动态库/可执行**（`file` 确认，stripped）；即清理核心用 **原生代码** 实现，App 只做编排，不直接删文件。

### 3.2 config.json（主配置，节选）

```json
{
  "general": {"auto_clean": true, "log": false, "temporal_barrier_days": 3},
  "app_clean": {"detect_schedule": {"every": "PT5M"}, "min_interval_hours": 12,
     "wechat": {"enabled": true}, "qq": {"enabled": true}, "douyin": {"enabled": true},
     "other": {"enabled": true, "schedule": {"every": "P1D", "at": "03:00"}}},
  "suppress": {"enabled": true, "detect_schedule": {"every": "PT1M"}},
  "gc": {"enabled": true, "free_percent": 95, "target_percent": 98,
     "min_interval_hours": 6, "require_charging": false, "min_battery": 40,
     "max_battery_temp": 400, "schedule": {"every": "PT12H"},
     "script": "/data/adb/modules/CZero/list/GCclean/GCclean1",
     "wait_screen_off_timeout": 30, "max_runtime_sec": 420},
  "trim": {"enabled": true, "schedule": {"every": "P7D", "at": "04:00"}},
  "empty_folder": {"enabled": true, "schedule": {"every": "P1D", "at": "04:00"}},
  "file_sort": {"enabled": false, "root": "/storage/emulated/0/CZero", "quiet_sec": 60,
     "depth": 3, "duplicates": true, "apk_rename": false}
}
```
要点（可借鉴）：
- **ISO-8601 时长表达调度**：`every: "PT5M"/"P1D"/"P7D"` + 可选 `at: "03:00"`（时刻）。
- **触发条件**：GC 需电量/充电/温度门限（`min_battery`、`require_charging`、`max_battery_temp` 单位 0.1℃=400→40℃）。
- **前屏保护**：`wait_screen_off_timeout`、`max_runtime_sec` 限定清理仅在息屏且不超时。
- **间隔节流**：`min_interval_hours` 防抖。
- **时间屏障**：`temporal_barrier_days: 3` —— 只清 3 天前的垃圾。

---

## 四、规则系统设计（重点学习）

### 4.1 规则文件 schema（通用）

`PathListDoc`（`bz1.java`）序列化格式：
```json
{
  "version": 1,
  "groups": [
    {"id": <可选long>, "name": "分组名", "enabled": true,
     "paths": ["/abs/path", "/abs/path/*", "/abs/*/cache/*"],
     "source": "可选来源标识", "readonly": false}
  ],
  "sources": [ ... ]   // 仅 filesort 使用：文件整理源
}
```
- `yy1`（`PathGroup`，`yy1.java`）：`id`/`name`/`enabled`/`paths`/`source`/`readonly`。
- 解析器 `uo1.L()`、序列化 `uo1.Q()`（缩进 2 空格）；`dx1.L()` 读 `list/filesort/sources.json`。
- **路径通配**：用 shell glob（`*` 段匹配），例：`/data/data/*/cache/*`、`/data/media/0/Android/data/*/cache/*`。
- **readonly**：标记只读分组（如云端下发），用户不可本地改。

### 4.2 各规则文件职责

| 文件 | 作用 | 引擎 |
|---|---|---|
| `clean_paths.json` | **要清理的路径**（黑名单式），约 60+ 应用分组：系统缓存、微信/QQ、B站、淘宝、夸克、Chrome、抖音… | `list/customize` |
| `clean_whitelist.json` | **受保护路径**（白名单），如 DCIM、Pictures、Download、各游戏存档、银行 App 目录、`Android/data/<游戏>` 等 | `list/customize` |
| `Emptyfolder/directories.json` | 空目录扫描根（默认 `/storage/emulated/0/`） | `list/Emptyfolder/emptyfolder` |
| `Emptyfolder/emptyfolder_white.json` | 空目录白名单（QQ/微信/抖音/网盘 data 目录） | 同上 |
| `filesort/sources.json` | 文件整理源目录（默认 Download） | `list/filesort/filesort` |
| `suppress/apps.json` | 后台压制应用及进程名清单 | `list/suppress/suppress` |
| `list/Tencent/{tencentmm,mobileqq,ugcaweme}` | 微信/QQ/抖音**专项**清理（数据库外缓存、下载残留等） | 各自 ELF |

### 4.3 App 侧规则引擎架构

- 内存模型：`bz1`=PathListDoc(groups + sources)；`yy1`=PathGroup。
- 读写：通过 root shell 读写 `/data/adb/modules/CZero/list/*.json`（`xa2.a.I(path)` 读文件，`xa2.f0(path,content)` 写文件）。
- 保存前 **JSON 合法性 + 括号平衡** 校验（`customize.sh` 的 `merge_config` 用 awk 做 `{`/`}` 配平与 `"gc"` 存在性检查）。
- 安装升级时 `merge_groups`：**只增不删**，把新版默认白名单里用户没有的分组补进去，保护用户自定义内容。
- 旧格式迁移：`prop_to_json()` 把旧的 `#分组名` + 逐行路径 `.prop` 转成 JSON。

### 4.4 云规则同步（捐赠特性）

`ff.java`：
- 拉取：`POST https://app.czeropage.top/api/app`，携带 `client_version`，返回 `CloudAppRulesSnapshot(version, rules, recalled)`。
- 本机扫描到的应用缓存目录 `b43{package,name,path,size}` 按包名分组，每 300 条一批上报（`apps` 数组 + `paths`），服务端返回 `submitted/skipped`。
- 结果落 SP：`app_rules_local_count`、`app_rules_cloud_count`、`app_rules_cloud_total`、`app_rules_cloud_version`、`app_rules_upload_state`。
- 开关：`app_rules_sharing_enabled`（默认 true）；上报前对规则名做 `^[A-Za-z0-9._]+$` 合法性校验，腾讯系(`com.tencent.mm/mobileqq`)、抖音加入 **不上报黑名单** `ff.b`。
- 云规则命中本机时走 `ff.f()` 过滤（只保留本地真实存在的包）。

### 4.5 扫描 / 清理执行方式（App 侧）

`ud.java` 展示了「应用缓存探测」的 shell 扫描（可借鉴的 **CZ| 协议**）：
```sh
for base in "/data/data/$p" "/data/user_de/0/$p" "/storage/emulated/0/Android/data/$p"; do
  [ -d "$base" ] || continue
  printf '%s\n' "$base/cache" "$base/code_cache" "$base/files/cache"
  find "$base" -mindepth 1 -maxdepth 6 \( -type d \( -iname '*cache*' -o -iname '*log' -o -iname 'log_*' -o -iname 'logs' -o -iname 'crash*' -o -iname 'exception*' -o -iname 'tombstone*' -o -iname 'anr' -o -iname 'temp' -o -iname 'tmp' -o -iname '.temp' -o -iname '.tmp' -o -iname '*thumb*' \) \) -o \( -type f \( -iname '*.log' -o -iname '*.log.*' -o -iname '*.tmp' -o -iname '*.hprof' -o -iname '*.dmp' -o -iname '*.mdmp' -o -iname '*.anr' \) \) 2>/dev/null
done | sort -u | while IFS= read -r d; do
  [ -e "$d" ] || continue
  s=$(du -sk "$d" 2>/dev/null | cut -f1)
  echo "CZ|${s:-0}|$d"
done
```
- 输出 `CZ|<KB>|<path>`，逐行解析（`me`/`b43`）；父目录去重（子路径以父路径+"/"开头则跳过）。
- 文件排序(filesort) 用 `CZS|/CZSUM|/CZUSUM|` 协议行；扫描脚本可自带 `scan` 子命令（`xa2.Q()` 用 `CZERO_SCAN_V1` 魔数嗅探判断）。
- 执行统一入口 `xa2.L(path,timeout)`：`CZERO_NO_NOTIFY=1 '<script>' force; echo __RC=$?`，解析退出码，非 0 记「清理程序执行失败」并以 `CZERO_` 前缀行取错误详情。
- 清理动作汇总 `xa2.M()`：依次跑 `Tencent/{tencentmm,mobileqq,ugcaweme}`、`list/customize`、`Emptyfolder/emptyfolder`，返回 `done/no_change/failed`。
- **清理后用 MediaScanner 刷新**（`xa2.V()`），删除文件后避免相册残留缩略图/幽灵条目。

### 4.6 与 AI/自动化集成

`a62.java` 内置 MCP 风格工具：`browse_directory`、`add_clean_rule`、`remove_clean_rule`、`list_clean_rules`、`read_config`、`write_config`、`restart_daemon`、`set_suppress_apps` 等；返回字段 `rule_id/path`，配合确认卡片（`declined_by_user`）后才执行。可作为「AI 助手操作清理」的接口范式。

---

## 五、可借鉴点（对照 ZhangProtect 现有实现）

> 现状：ZhangProtect 规则集中在 `RubbishRuleSet.kt`（编译期 Kotlin 常量表），扫描在 `RubbishCleaner/ParallelScanner`，守护 `safeDelete` 唯一入口 + `RubbishGuard/UserGuardRules` 违禁词/违禁路径 + `AuditLog`。

| # | CZero 做法 | 我们的现状 | 建议 |
|---|---|---|---|
| 1 | 规则 **数据驱动**（JSON 文件，App/root 可改，安装时 merge 只增不删） | 规则硬编码在 Kotlin | 可考虑外置 `rubbish_rules.json`，保留 Kotlin 默认表作为 fallback，支持用户分组与导入导出 |
| 2 | 黑名单 `clean_paths.json` + 白名单 `clean_whitelist.json` **双文件、分组化** | 单表 + keep 列表 + UserGuardRules | 引入显式「保护白名单文件」，优先级高于规则（保护 wins） |
| 3 | 路径 **glob 通配**（`*` 段） | 已有 `<u>`/`*` 抽象 | 统一通配语义并做文档化 |
| 4 | 清理前 **JSON 合法性/括号配平** 校验（只增不删合并） | 写 config 已有 JSON 构建器 | 规则文件写入前做同等级校验 |
| 5 | **ISO-8601 调度** + `at` 时刻 + `min_interval_hours` 节流 | 已有定时清理 | 统一时间为 ISO-8601 语义，支持抖动/节流 |
| 6 | **触发门限**：电量/充电/温度/息屏/最长运行时间 | 已有基础判断 | 补齐温度、息屏等待、max_runtime |
| 7 | **时间屏障** `temporal_barrier_days`：只清 N 天前 | 未实现 | 建议加，避免误删刚产生的临时文件 |
| 8 | 清理后 **MediaScanner 刷新** | 未确认 | 删除媒体后触发媒体库刷新 |
| 9 | **CZ| 文本协议** 结构化返回（size|path） | 自有协议 | 可参考其紧凑性与健壮性 |
| 10 | **Cloud 规则同步 + 不上报黑名单 + 包名合法性校验** | 无云同步 | 若做云规则，务必带隐私黑名单与字段校验 |
| 11 | 专项引擎隔离（微信/QQ/抖音各独立二进制） | 单模块 | 高价值：可把微信/QQ 专项规则独立成可替换单元 |
| 12 | 授权 **双落盘 + 吊销回收** 思路（若我们要做授权） | 无授权 | 若需授权可借鉴，但注意明文 token 风险 |

### 安全对比（重要差异）

- CZero：清理逻辑在 **root 原生 ELF** 中，App 只用 shell 调用；保护靠 `clean_whitelist.json` + 引擎内置判断。**没有**集中的「删除审查」抽象外露。
- ZhangProtect：**唯一入口 `safeDelete`**（路径规范化、禁穿越、禁根、白名单、递归子路径校验）+ `UserGuardRules` 可自定义违禁词/违禁路径 + `AuditLog` 审计。
- 结论：**我们的删除审查机制更完善，应保留**；CZero 的**规则数据化 + 双名单(黑/白) + 调度/门限模型**值得吸收。

---

## 六、待办 / 后续可选动作

- [ ] （可选）在 ZhangProtect 增加 `rubbish_rules.json` 外置规则加载与「只增不删」合并，与现有 `RubbishRuleSet` 默认表合并；默认表作为 fallback。
- [ ] （可选）增加独立「保护白名单文件」，语义优先于清理规则。
- [ ] （可选）引入 `temporal_barrier_days`（默认 3）与清理后 MediaScanner 刷新。
- [ ] （可选）调度统一 ISO-8601 + `at` 时刻 + `min_interval_hours` 节流。
- [ ] （未决）是否需要捐赠/授权体系（若需要，参考第二章，并务必用签名 token 而非明文文件）。
