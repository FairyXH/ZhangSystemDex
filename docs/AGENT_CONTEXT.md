# ZhangSystemDex — Agent 接力上下文（压缩版）

> 目的：让新接手的 Agent 快速恢复工程状态，无需从头扫描。
> 维护原则：只保留能帮助继续工作的关键事实；删除无意义探索过程。

## 1. 项目定位

- 包名：`io.github.fairyxh.zhangsystemdex`
- 形态：KernelSU / Magisk 模块（`id=Zhang`, `name=ZhangProtect`, `versionCode=521`）。
- 核心机制：
  - 模块通过读取模块目录 `webroot/index.html`（+ WebUI X 宿主清单 `webroot/config.json`）渲染 **模块 WebUI**。
  - WebUI 的数据由 `Main.dex` 内常驻的本地 HTTP 后端（`HttpBackend`，仅监听 `127.0.0.1`）提供。
  - 端口取自 `config.conf` 的 `http_port`，默认 `DEFAULT_HTTP_PORT=26437`（当前 `config.conf` 未显式配置，即用默认 26437）。

## 2. 仓库与远程

- 远程：`https://github.com/FairyXH/ZhangSystemDex.git`（main 分支）。
- **本地构建工程即 git 工作区**：`/home/projects/ZhangSystemDex`（2026-10-03 起已 `git init`
  并绑定 origin；gh CLI 已登录 FairyXH，凭证存于 `~/.git-credentials`）。
  - 提交身份：`git config user.name FairyXH` / `user.email fairyxh@users.noreply.github.com`。
  - 直接 `git push origin main` 即可（credential.helper=store）。
- 打包源目录（设备）：`/data/media/0/Download/Files/ZhangProtect-Android/`（= `/sdcard/Download/Files/ZhangProtect-Android/`）。
- 安装后对应：`/data/adb/modules/Zhang/`；运行目录：`/data/adb/Zhang/`。
- 注意：打包母版目录**不是** git 仓（也不应 init）；只有 Ubuntu 源码工程受版本控制。

## 3. 关键产物与正确校验值（务必使用带 HttpBackend 的新版）

| 产物 | 位置 | md5 | 说明 |
| --- | --- | --- | --- |
| `Main.dex`（**当前，含垃圾清理**）| 模块目录/运行目录/仓库 | `4669cac99679dc41475917edf973cdba` | 2442020B，含 RubbishGuard/Cleaner/RuleSet/AuditLog/JsonBuilder |
| `Main.dex`（上版，含电源子系统）| 备份 `_backup_clean_*/Main.dex.mod` | `8224b53e578ddda00bb08df900c28829` | 2358752B，含 HttpBackend |
| `Main.dex`（旧，禁用）| 备份名 `Main.dex.bak.1791001188` | `7460aaa69e082d3c8084854bbf269655` | 0处 HttpBackend，2306316B |
| `webroot/index.html`（**当前，五 Tab + 清理**）| 打包目录/模块目录/仓库 | `802216c5a95d6efd242ca3a3178e7e49` | 54611B，五 Tab iOS 底栏 UI（概览/开关/电源/清理/设置） |
| `webroot/index.html`（四 Tab，上一版）| 历史 | `e91f05a8fb2f280d1fefa4e422869f44` | 41302B |
| `webroot/config.json` | 打包目录/模块目录 | `d01d5fc7f95f27ed10a33d1a16c1c255` | 449B，WebUI X 宿主清单 |

> 切勿用旧值：`Main.dex` 若为 `7460aaa6...`、或 webroot 用 `4643...` / `a8cb7f03...` 系列即旧包。
> 设备备份目录：`/data/adb/Zhang/_backup_clean_20261003-140001/`（含旧 dex/webroot/switches.conf）。

## 4. 服务与部署机制

- `service.sh`：启动时把模块 `Main.dex` 复制到 `/data/adb/Zhang/Main.dex`，再 `app_process -Djava.class.path=... --nice-name=zhangsystemdex Main "${MODDIR}"` 启动，写 `daemon.pid`，含防重复启动与 `bt_offload_fix.sh` 钩子。
- `重启Dex.sh` = `停止Dex.sh` + `service.sh`（按 `daemon.pid` / `pkill` 停旧进程）。
- `HttpBackend extends DaemonLoop`：`onStart()` 绑 `127.0.0.1:port`。

## 5. 环境约束（重要，决定操作路径）

- **Android shell 无 `zip`**：仅 `/system/bin/unzip`、busybox 1.36.1.1（无 zip applet）、toybox 仅 gzip/gunzip → `pack.sh` 设备端打包会 `rc=127` 失败。
- **Ubuntu 侧有 `/usr/bin/zip|unzip|sha256sum`，且能经 `/sdcard` 读打包目录** → **打包在 Ubuntu 执行**。
- 跨环境路径隔离：
  - Ubuntu 可读 `/sdcard`(=`/storage/emulated/0`)，**读不到** `/data/adb`。
  - Android shell 反之（可读 `/data/adb`，读不到 `/home/projects`）。
  - dex 部署中转区：`/sdcard/Download/Files/_zsd_deploy/`（Ubuntu 写入 → Android shell 读取）。
- media 层写入缓存延迟：shell 侧写 `/data/media/...` 后，Ubuntu 侧 `ls` 大小/时间可能短暂滞后，但 `md5sum` 已更新。

## 6. 打包/构建流水线（已加固，防回归）

- `构建MainDex.bat`：抽取 `classes.dex` → `Main.dex`；校验 大小≥1MB + 含 `SkipMountGuardModule` + **含 `HttpBackend`**，否则 exit 1。
- `构建WebUI.bat`：**同时**抽取并校验 `assets/webroot/index.html` + `assets/webroot/config.json`。
- `pack.sh`：`precheck()` 要求存在 `webroot/index.html` + `webroot/config.json` + `Main.dex` 且 dex 含 `HttpBackend`，否则拒绝打包；打包后解压并逐文件 SHA256 自检比对（最多 3 次重试）。

## 6.1 WebUI 重构（iOS 风格四 Tab 底栏，已完成）

- 需求：重构 `webroot/index.html` → ①按功能大类分类；②加底栏；③iOS 页面设计。
- 信息架构：四个底栏 Tab — **概览(home) / 开关(switch) / 电源(power) / 设置(settings)**。
- 功能大类 `GROUPS`（展示层分组，重划为）：
  - `special`（特殊默认开启，源自 `SPECIAL_DEFAULT_TRUE`）
  - `core`（核心与系统调优）、`perf`（性能与加速）
  - `privacy`（隐私与应用管控）、`misc`（其它与开机行为）
- 兼容性硬保证：**48 个配置 key 逐字保留（零增删）**；全部既有 API 继续调用
  （`/api/read|write|powerstatus|paths|reload|bglist/read|bglist/write`）；
  `parseLine/readValue/setValue/originalValue` 的 **last-wins** 语义与 daemon `ConfigManager.loadSwitches` 一致。
- 视觉：大标题导航栏、圆角分组列表、iOS 开关(.sw)、毛玻璃底栏(backdrop-filter blur)、
  `env(safe-area-inset-*)` 安全区、`color-scheme:light dark` 亮暗自适应、Toast、底栏徽标。
- 构建硬约束（新 UI 必须满足）：含 `KSU` 串（保留 `const KSU="webui-x://http-backend"`，实测 4 处）、
  以 `</html>` 结尾、>1000B（实测 41302B）、`config.json` 含 `title`。
- 源码分片位置：`/tmp/zsd_new/part1..6c`（合并脚本：`cat part1.html part2.html part3.html
  part4.html part5.html part6a.js part6b.js part6c.js > index.html`）。
- 静态校验全绿：`node --check`(SYNTAX OK)、key 覆盖 48=48 零差异、38 个 HTML id × 24 个 JS 引用零缺失、
  32 个函数定义/调用自洽。
- commit：`d6a9530`（feat(webui)，已完成，待 push）。

## 7. 已完成与验证结论

- [x] 根因① webroot 缺失 → 已补齐（打包目录 + 模块目录，md5 一致）。
- [x] 根因② 设备运行旧 dex → 已部署新 dex（`8224b53e...`）并重启守护进程（PID 4726→4802）。
- [x] HTTP 后端端到端验证通过：`ss` 显示 `[::ffff:127.0.0.1]:26437` LISTEN(pid=4802)；`/api/ping`→`{"ok":true,"code":0,"result":"pong"}`；`/api/powerstatus` 实时数据；`/`→HTTP 200；`/api/paths`→`port:26437,pid:4802`。
- [x] 三脚本加固并提交：commit `284518f`（已 push）。
- [x] Ubuntu 重打包成功（**旧版 UI**）：zip SHA256 `5e802df0...`（对照用，已被下述新版取代）。
- [x] **WebUI iOS 重构完成并落地两处**（仓库 assets + `/sdcard` 打包目录），md5 均为 `e91f05a8fb2f280d1fefa4e422869f44`；commit `d6a9530`。
- [x] **Ubuntu 重打包成功（新版 UI）**：`/sdcard/Download/Files/ZhangProtect-Android.zip`
  （**345,764,874 字节**），含新 `webroot/index.html`(41302B) + `config.json` + 新 `Main.dex`。
  - `unzip -t` 无错误；zip 内 `webroot/index.html` md5 = 源 md5 = `e91f05a8...`；
  - **逐文件 SHA256 全量比对（88/88 文件）IDENTICAL**（等价于 `pack.sh` 自检通过）；
  - **zip SHA256：`e30d649dd71c461e2dd6de5796492a18a975c5b966b1a808f49e4ca919336183`**。

## 7.1 无头运行时验证（Node + linkedom，已完成，13/13 PASS）

将静态校验升级为**真实执行页面 JS** 的验证，确认渲染管线真能工作。

- 环境：`node v24.20.0`；`jsdom`/`linkedom` 均未预装 → `npm install linkedom`（纯 JS，20 包，约 7s）。
- 测试资产在 `/tmp/zsd_new`（**不在 git 仓内**，勿提交）：`harness.mjs`（顶层执行 + 四 Tab 切换）、
  `harness2.mjs`（13 项精细断言）、`probe2.mjs`（bgList 单点探针）、`node_modules/linkedom`。
- `harness.mjs`：**EXIT=0**，四 Tab 切换全部 OK（需 stub `window.scrollTo` 与 `window.matchMedia`）。
- `harness2.mjs`：**13/13 PASS，EXIT=0**。实测结论（可直接作为回归基线）：
  - `GROUPS` 是 **数组**，长度 5，id = `special,core,perf,privacy,misc`；
  - `TAB_TITLES = {home:概览, switch:开关, power:电源, settings:设置}`；
  - `load()` 真实发起 3 次调用：`/api/paths`、`/api/paths`、`/api/read?path=...`；
  - 渲染结果：**5 sections / 42 个 switch cell（== GROUPS 各 keys 之和，无遗漏）**、
    **8 个 power cell（== POWER_KEYS.length）**、settings 的 tuning=3 / extcmd=2 / danger=6、
    tabbar 4 项；四 Tab 切换无异常。
- **Mock 响应形状必须与后端逐字一致**（本次踩坑两次，均为测试脚本问题、非产物缺陷）：
  - `/api/read` 与 `/api/bglist/read` → 返回体取 **`content`**（`{ok:true,content:"..."}`）；
  - `/api/paths`、`/api/powerstatus` → 返回体取 **`result`**；
  - `api()` 直接 `return res.json()`，故 mock 的 `json()` 要给**对象本身**，不要二次 `JSON.stringify`。
- HTML 元素级细节：`#bgList` 是 `<textarea>`，`loadBgList()` 写的是 **`.value`**，
  断言须读 `.value`（`.textContent` 恒为空）。
- `RENDER` 等标识符并不存在 → 用 `new Function(...code+';return {A,B,C};')` 注入时必须只用真实符号；
  **完整真实函数名清单**：`api, apiBase, bind, currentBool, detectPaths, esc, isBoolKey, load,
  loadBgList, markDirty, originalValue, parseLine, readValue, refreshPowerStatus, renderAll, renderCell,
  renderEmptyHome, renderHome, renderPowerTab, renderSettingsTab, renderSwitchTab, revert, save,
  saveBgList, setNavBusy, setStatus, setValue, shq, switchTab, toast, updateDirtyCount, updatePortPid`
  （无 `renderSwitches` / `KEYS` / `tabbar` 之类）。

## 8. 待办 / 下一步

- [ ] 用新 zip（SHA256 `e30d649d...`）重刷设备，做 KernelSU/WebUI 最终端到端确认（四 Tab 渲染 + 数据写入）。
- [x] push commit `d6a9530` 到 origin/main（已在 `27ddbf8` 前序完成并同步；`git status -sb` = `## main...origin/main`）。
- [ ] 回滚：用 `Main.dex.bak.1791001188` 恢复旧 dex；UI 回滚用 `/tmp/zsd_new/index.old.bak.html`。
- [ ] 可选：`pack.sh` 目前“打包当前目录所有文件，无忽略”→ 产物含 README/shfmt/aapt 等，体积偏大；如需精简可加排除清单（注意与全量 SHA256 校验逻辑相互影响）。

## 9. 注意事项 / 易踩的坑

- 打包**不要**在 Android shell 执行（无 zip）。
- 部署 dex 必须经中转区 `/sdcard/Download/Files/_zsd_deploy/` 跨环境桥接。
- `pack.sh` 长时间运行时终端工具可能截断缓冲输出（曾误以为脚本提前退出）；用 `sh -x` 或重定向日志确认实际执行完毕。
- 仓库 git 身份未全局配置，提交时需 `git config user.name/email`（用 `FairyXH` / `FairyXH@users.noreply.github.com`）。
- **⚠ 双 `/tmp` 命名空间隔离（极易误判，务必先读）**：
  - `create_file` / `edit_file` **默认写入的是 proot Linux 的 `/tmp`**，属主 root:root；
  - 而 `super_admin:terminal` 看到的是 **Android tmpfs 挂载的 `/tmp`**（`mount` 显示 `tmpfs on /tmp type tmpfs`）；
  - 两者**完全不同步**：文件工具写的文件，终端 `cat` 不到（或读到旧内容/乱码）。
  - **规避**：凡需与终端共享的文件，必须用 `create_file` / `edit_file` 的 **`environment:"linux"`** 参数；
    且系统禁止 apply 覆盖已存在文件 → 整文件改写须先 `delete_file` 再 `create_file`。
  - 症状速记：“编辑器里明明写好，终端却看不到 / 是旧版 / 是乱码” → 一律按此排查。
- 终端 **heredoc（`<<EOF`）写文件会被回显污染**（曾产生乱码 `harness.mjs`）→ 一律改用 `create_file`。
  shell 里 `sed`/`python3 -c` 处理含 `\n`、`"` 的字符串极易被多层转义吃掉 → 复杂改写优先走 `edit_file(environment:"linux")`。
- 终端长命令仍会截断/被杀；`&`/`nohup` 后台任务随 session 死亡 → `pack.sh` 之类长任务须用 `setsid` 分离。
- 含字面量 `"`/`&amp;` 的代码经文件工具传输会被写成裸 `"`（曾致 `esc` 语法错误）→
  用 `\x26` / `\u0022` 规避（现有 `esc()` 即为此写法）。

---

## 10. 垃圾清理子系统（2026-10-03 新增，已真机验证）

### 10.1 代码布局

```
core/rubbish/
  RubbishGuard.kt      ★ 中心化删除审查（唯一删除入口）
  UserGuardRules.kt    用户违禁词/违禁路径（rubbish_guard.conf，只增拒绝）
  AuditLog.kt          审计日志 log/rubbish_clean.log（不受 log_enabled 影响）
  CleanRule.kt         规则数据结构（RiskLevel / MatchMode / RuleGroup）
  RubbishRuleSet.kt    全量规则表（通用 10 + 深度 4 + 微信 7 + QQ 6 = 27 条）
  RubbishCleaner.kt    只读扫描 + 规则驱动删除 + 深度扫描 + JSON 序列化
  FileIdentifier.kt    ★ 文件头识别（APK/ZIP 魔数 + 中央目录）+ SHA-256
  ScanCache.kt         ★ 深度扫描索引（首次全扫，之后仅扫变动）
  JsonBuilder.kt       嵌套 JSON 构建（HttpBackend MiniJson 不支持嵌套）
modules/SystemTuningModule.heavyTick()  定时清理入口
DebugMenu 27/28/29    只读扫描 / 按开关清理 / 审查自检
SelfTest.rubbishChecks()  10 项只读回归
```

### 10.2 安全设计（核心，改动勿破坏）

- **唯一删除入口** `RubbishGuard.safeDelete()` / `safeCleanDirContents()`；
  其它模块**不得**直接 `File.delete()` 或 `rm -rf` 清理垃圾。
- 审查链（顺序）：空值 → `canonicalPath` 规范化 → 层级≥3 段 → 精确黑名单 →
  前缀黑名单 → 白名单根（`/data/media`、`/data/user`、`/data/data`、`/data/anr`、
  `/data/tombstones`、`/data/system/dropbox`）→ **`/data/media` 与 `/data/user` 之下
  必须紧跟数字用户目录** → 用户违禁路径 → 用户违禁词 → 递归逐项复检 → 软链不跟随。
- **路径策略**：一律用真实路径 `/data/media/<u>`、`/data/user/<u>/<pkg>`；
  **严禁** `/sdcard`、`/storage/emulated`、`/mnt/user`。
- 用户配置 `rubbish_guard.conf`：`deny_path=` 前缀匹配、`deny_word=` 子串匹配（忽略大小写）；
  **只增加拒绝**。WebUI 保存后调 `RubbishGuard.loadUserRules()` 立即生效。

### 10.3 配置键（switches.conf，经 appendRubbishMissing 只增不覆盖）

总开关 `rubbish_clean_enable=false`；参数 `rubbish_clean_screen_off_only=true`、
`rubbish_force_when_running=false`、`rubbish_big_file_mb=100`、`rubbish_wx_chat_media_days=30`；
23 条规则各有 `rubbish_rule_*` 键；**低风险默认 true**（已加入 `SPECIAL_DEFAULT_TRUE`），
中高风险默认 false。真机 migration 验证：switches.conf 65→95 行，纯追加、无新增重复键。

### 10.4 HTTP 端点（HttpBackend）

`/api/rubbish/rules|scan|clean|status|history|guard/read|guard/write`。
**WebUI 只传规则 id，绝不传文件路径**；scan/clean 均受 `rubbish_clean_enable` 门控。

### 10.5 真机实测结论（2026-10-03）

- 审查自检 **17/17 PASS**：`/`、`/data`、`/data/adb`、`/data/system/dropbox`、
  `/sdcard`、路径穿越（`/data/media/0/Download/../../adb` → 拒绝）、
  `EnMicroMsg.db`/`shared_prefs`（默认违禁词）→ 拒绝；合法清理路径 → 接受。
- **真实清理验证**：`qq_logs` 删除 29 文件 / 10,337,048 B，目录保留、拒绝 0，
  审计日志写入完整（含每文件 DELETE 行 + SESSION 汇总）。
- 多用户正确：设备有 userId `0` 与 `999`（工作资料），两处均被正确枚举。
- 目标包运行中跳过生效：`com.tencent.mm` 运行时其全部微信规则返回
  `跳过：目标应用运行中（com.tencent.mm，pid=731）`。
- WebUI 无头校验 15/15 PASS（Node + linkedom，`/tmp/wuicheck/clean_harness.mjs`）。

### 10.6 ⚠ 已修复的重大缺陷（真机发现，勿回归）

1. **GLOB 模式越界**：`roots=/data/media/<u>/Android/data/*` + `pattern=log,logs...`
   曾把「每个应用目录本身」当清理目标（app_logs 误报 93,260 文件 / 35GB），
   清理会误删整个应用数据。修复：GLOB 模式展开结果**仅作搜索根**，再按 pattern
   匹配其子项（`resolveTargets(rule, root)`）。
2. **EMPTY_DIR 落入 else 分支**走 `safeDelete`（整目录删除）。修复：新增
   `cleanEmpty()`（后序清理空目录与 0 字节文件）与对称的 `statEmpty()`。
3. **normalize 逃逸**：`/data/media/0/Download/../../adb` → `/data/media/adb` 曾被接受。
   修复：`/data/media`、`/data/user` 之下必须紧跟数字用户目录。

### 10.7 深度扫描（2026-10-03 新增，已真机验证）

针对「软件偷偷下载 APK」的需求，新增 4 种扫描模式 + 5 条规则。

**核心组件**

| 组件 | 职责 |
|---|---|
| `FileIdentifier` | 文件头识别：ZIP 魔数 `PK\x03\x04` + 中央目录含 `AndroidManifest.xml` → 判定 APK。**不看扩展名**，可发现 `.tmp`/无后缀/改名的安装包。另有 SHA-256 内容哈希 |
| `ScanCache` | 扫描索引存 `<rootDir>/rubbish_index/<ruleId>.idx`，格式 `kind\tpath\tsize\tmtime`。命中（size+mtime 未变）即复用，不重读文件头 |
| `RubbishCleaner.scanDeep/cleanDeep` | 深度模式扫描与删除；删除后 `cache.invalidate(ruleId)` 强制重扫 |

**规则表（DEEP 分组）**

| 规则 | 模式 | 风险 | 默认 | 说明 |
|---|---|---|---|---|
| `apk_scan_media` | APK_SCAN | MEDIUM | 关 | 全 media 递归，文件头识别 APK，已排除模块自身与资源包目录 |
| `apk_scan_private` | APK_SCAN | MEDIUM | **开** | 常见应用**缓存目录**中的安装包 |
| `big_files_private` | BIG_FILE_SCAN | LOW | 关 | 私有目录 >50MB 文件**仅列出不删**（`listOnly=true`） |
| `dup_files_media` | DUP_CONTENT | HIGH | 关 | 尺寸+哈希去重，每组保留最新 |
| `dup_wechat_tpc` | DUP_SAME_SIZE | LOW | **开** | 微信 `cache/temp/TPCFile` 重复下载（实测 59 个相同 20MB = 1.1GB） |

**安全加固（本轮）**

1. `RubbishGuard.isForbiddenPath()`：**扫描阶段预筛**，避免模块自身 APK/系统目录计入候选
   （原 `apk_scan_media` 误报 41 个含 37 个模块自身 APK → 修复后 1 个）
2. 模块自身路径加入默认违禁：`ZhangProtect-Android`、`ZhangSetting`、`appsearch.apk`
3. `FORBIDDEN_PREFIX` 改为**前缀匹配**（原仅精确匹配，拦不住子路径）
4. `ALLOWED_ROOTS` 豁免：`/data/anr`、`/data/tombstones`、`/data/system/dropbox` 的
   **内容**可清理，但 `/data/adb`、`/data/system` 其他部分仍拒绝
5. 移除 `.nomedia` 违禁词（媒体扫描标记，删除无害；曾误拒 32 次）

**真机实测（2026-10-03）**

- 审查自检 **17/17 PASS**（安全边界未回归）
- 文件头识别：手造 `_zhang_test_disguised.tmp`（10,627,230B，无 .apk 后缀）→ 正确识别为 APK
- **真实清理**：该伪装 APK 被删除，审计日志 `apk_scan_media 1/10627230 rejected=0`
- 增量缓存：索引 11,312 条目；全量扫描 2005ms，缓存命中即时
- 精准过滤：`apk_scan_media` 41 → 1 个（排除模块自身 37 个 + 资源包 3 个）
- 揪出的真实伪装文件样本：`点击助手_xxx.APK`（真 APK）、酷狗 `skin_*.ks`、
  百度网盘 `dark_theme.skin`、网易 `mpay.pkg`（后三者为应用资源包，已加入 keep 排除）

### 10.8 真机事故与安全收紧（2026-10-03，重要教训）

**事故现象**：执行 `system_junk_data` 真实清理（336 文件 / 442 MB）后，用户报告**系统界面黑屏数秒**。

**根因分析**（logcat 实证）：
1. `14:48:00` 清理 daemon 的 `HttpBackend` 线程 `SIGSEGV` 崩溃（pid 26187）
2. `14:49:31` Shizuku 服务进程 `FATAL EXCEPTION: UnsatisfiedLinkError`
3. SurfaceFlinger 显示层重连 → **黑屏**

**真正错误**：清理规则把**活系统运行时目录**当成了静态垃圾：
- `/data/vendor/camera/dump`（281 文件）— 相机 HAL **持有句柄、边写边用**
- `/data/misc/**` — WiFi/蓝牙/sensor/audio 运行时状态
- `/data/vendor/tombstones`、`/data/cache`、`/data/ss`、`/data/ramdump`

**三层防护（已实现，勿回退）**：

1. **`RubbishGuard.check()` 第 2b 步 — 活系统路径硬拒绝**
   ```
   /data/vendor/ /data/misc/ /data/system_ce/ /data/system_de/
   /data/ramdump /data/ss/ /data/dropbox/ /data/cache/
   ```
   命中即 `Reject("命中活系统运行时目录（真机事故防护）")`。
   `isForbiddenPath()` 同步实现（扫描预筛）。

2. **`JunkPatterns.PROTECT_PATH_CONTAINS` 扩展**
   新增 `/vendor/camera`、`/vendor/audio`、`/vendor/modem`、`/data/misc/`、
   `/system_ce/`、`/data/cache/` 等；并提供 `isLiveSystemPath()`。

3. **`CleanRule.ageDays` 时效过滤**
   - `system_junk_data`：`ageDays=7`（只清 7 天前的静态日志）
   - `junk_media_apps`：`ageDays=3`（避免碰到活跃缓存）
   - 实现于 `RubbishCleaner.olderThan()`，在 `scanJunk`/`collectJunkVictims` 生效。

**`system_junk_data` 收紧结果**：
- `listOnly = true`（**只读扫描，永不删除**）
- roots 缩减为 7 个**真静态**目录：`/data/log`、`/data/bootchart`、
  `/data/system/dropbox`、`/data/anr`、`/data/tombstones`、`/data/debugging`、
  `/data/resource-cache`
- 真机复测：候选从 **2362 文件 / 463 MB → 4 文件 / 698 KB**（`/data/vendor/camera`
  的 6610 个文件已完全排除，目录内容完好）

**结论**：**系统级目录默认只读**。用户如需清理，必须显式开启且仅限静态日志。

### 10.9 待办 / 可选增强

- [ ] 中高风险规则（微信聊天媒体按时间、QQfile_recv 等）尚未在真机做真实删除验证。
- [ ] `rubbish_rule_big_files_list` 目前复用 OLDER_THAN（ageDays=0），语义上应改为
      「按大小阈值列出」，当前仅作占位。
- [ ] `uninstalled_leftover` 规则未接 `findUninstalledLeftovers()`（该方法已实现但未接线）。
- [ ] WebUI 清理 Tab 的真机可视化确认（本机无 WebView 环境，仅做了无头校验）。
