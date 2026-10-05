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
| `Main.dex`（**当前，含电源/清理修复**）| 模块目录/运行目录/仓库/母版 | `d5c533e802a5fa6b93dbeb5e13ec3648` | 2502608B，含 HttpBackend + 完整电源读数 + listOnly 护栏 |
| `webroot/index.html`（**当前，五 Tab + 电源/清理完善**）| 模块目录/母版/仓库副本 | `3f750826766decc89d1caf50dbb36818` | 58014B，电源页 16 格 + 清理文案专业化 |
| `webroot/config.json` | 打包目录/模块目录 | `d01d5fc7f95f27ed10a33d1a16c1c255` | 449B，WebUI X 宿主清单 |
| `Main.dex`（上版，含电源子系统）| 备份 `_backup_powerfix_*/Main.dex` | `a6e91230ae8d7a3c0226930e9f173913` | 2494640B（仓库曾记录为 a6e91230） |

> 历史值：`Main.dex` 旧版 `4669cac9…`（含垃圾清理）、`8224b53e…`（含 HttpBackend）、
> `7460aaa6…`（旧，禁用）；webroot 旧版 `802216c5…`（五 Tab+清理）、`e91f05a8…`（四 Tab）。

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

## 6.1.1 ⚙ 规范构建流程（2026-10-03 确证，务必用这个）

**构建必须在 Ubuntu 终端用 `/opt/build.sh`，不能用 `./gradlew`。**

- 环境脚本 `/opt/zhangsys-env.sh` / `/opt/android-env.sh` 提供：
  `JAVA_HOME=/opt/jdk-21.0.12.1+1`（AGP 9.1.1 要求 JDK21）、
  `GRADLE_USER_HOME=/opt/gradle-home`（预置依赖缓存，离线可构建）、
  `/opt/gradle-9.3.1/bin/gradle`、`ANDROID_HOME=/opt/android-sdk`。
- `bash /opt/build.sh`（内部已 source android-env、带 `--init-script /opt/gradle-init.gradle`
  与 `-Pandroid.aapt2FromMavenOverride=/opt/aapt2-qemu/aapt2`）。一次约 45~100s。
- **坑**：直接 `sh ./gradlew :app:assembleRelease` 会卡在
  "single-use Daemon process will be forked" 后无限挂起（proot 下 fork JVM 失败），
  且 wrapper 默认 `DEFAULT_JVM_OPTS=-Xmx64m` 与 `org.gradle.jvmargs=-Xmx2048m`
  不一致必然触发 fork。**不要用 gradlew**。
- 产物 APK：`app/build/outputs/apk/release/app-release-unsigned.apk`。
- 取 Main.dex：`unzip -o -q <apk> classes.dex -d <dir>`（只有一个 classes.dex）。
- dex 校验标记：含 `HttpBackend`（14 处）、`SkipMountGuardModule`、`apiPowerStatus`、`PowerOptimizer`。

## 6.1.2 真机部署与自测（2026-10-03 新增）

**跨环境桥**：Ubuntu 写 `/sdcard/Download/Files/_zsd_deploy/`，Android shell 读同路径。

部署步骤（Android shell）：
```sh
D=/sdcard/Download/Files/_zsd_deploy
cp -f "$D/Main.dex.new" /data/adb/modules/Zhang/Main.dex   # 模块目录（开机源）
cp -f "$D/Main.dex.new" /data/adb/Zhang/Main.dex           # 运行目录（当前进程）
cp -f "$D/index.html.new" /data/adb/modules/Zhang/webroot/index.html
sh /data/adb/modules/Zhang/重启Dex.sh                       # 停旧 + service.sh 启新
```
- **WebUI 位置**：WebUI X 宿主读**模块目录** `/data/adb/modules/Zhang/webroot/index.html`；
  母版 `/sdcard/Download/Files/ZhangProtect-Android/webroot/` 为打包源，两处需同步。
- **自测（不启动 HTTP 后端，安全）**：
  `cd /data/adb/Zhang && /system/bin/app_process -Djava.class.path=/data/adb/Zhang/Main.dex /system/bin --nice-name=zhangselftest <MainClass> /data/adb/modules/Zhang selftest`
  （`selftest` 参数 → `SelfTest.run(ctx)` 后退出；含清理审查 17/17 等）。
- **实时后端**：`curl -s http://127.0.0.1:26437/api/powerstatus`。
- **真机 dumpsys 格式（OPLUS/ColorOS）**：无 `plugged:` 行（用 `AC/USB/Wireless/Dock powered`）、
  温度有 `PhoneTemp:` 与 `temperature:` 两行、电流为 `Battery current : <n>`（冒号前有空格）。
  解析必须兼容这些差异，见 `HttpBackend.intField/boolField`（允许冒号前可选空白）。

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

### 10.9 安全 review 与修复（2026-10-03 第二轮，清理功能全面加固）

按「清理极其敏感，安全 > 效率」原则对全部清理代码做了一轮系统性 review，
**发现并修复 7 处缺陷**（其中 2 处为严重误删漏洞）：

| # | 严重度 | 缺陷 | 修复 |
|---|---|---|---|
| 1 | 🔴 严重 | `uninstalled_leftover` 规则 `mode=GLOB` + `pattern="*"`，会匹配 `/data/media/<u>/Android/data` 下**全部 360 个应用目录并递归删除**（`findUninstalledLeftovers()` 从未被调用）。用户一旦勾选即毁数据 | 新增 `MatchMode.UNINSTALLED_SCAN`；`scan`/`clean` 均走 `findUninstalledLeftovers()`；删除前**二次校验包仍未安装**（防 TOCTOU）；排除 `com.android.*`/`android*`/`com.google.android.*` |
| 2 | 🔴 严重 | `JunkPatterns.DIR_JUNK` 含 `dump/debug/trace/crash` 等宽泛词，命中即**无条件删除该目录下所有文件**（不看类型）。用户 `debug/` 里的导出数据会被清 | 拆分 `DIR_JUNK_STRONG`（语义明确的缓存/日志目录才直通）；弱特征目录下文件仍需文件级特征联合判定 |
| 3 | 🔴 严重 | `FILE_JUNK_NAME` 用 `startsWith("core")` → 误删 `core_backup.json`、`core_data.db` | 改为**精确匹配**；仅 `hs_err_pid`/`replay_pid` 保留前缀语义 |
| 4 | 🟡 中 | 零字节文件**无条件**判为垃圾（未校验保护路径） | 加 `isProtectedPath` 二次校验 + 要求 `looksTemporary` 语义 |
| 5 | 🟡 中 | `DIR_CONTENT` 模式对目录自身跑完整 `check`，`/data/anr`（2 段）被「层级过浅」拒绝 → **整个目录内容清不掉**（`system_crash_logs` 长期 0 删除的真因） | 新增 `RubbishGuard.checkContainer()`「宽松容器审查」：不做 MIN_SEGMENTS、不做「允许根本身」限制，但保留活系统防护与黑名单 |
| 6 | 🟡 中 | `deleteOne()` 以 `File.delete()` 返回值判成败，FUSE/应用私有存储下常误报 FAIL | 最终判据改为「路径是否真的消失」；FAIL 前兜底重试 `rm -rf` |
| 7 | 🟢 低 | `check()` 每文件重建 `liveSystemPrefixes` list（`deleteTree` 高频调用） | 提为常量 `LIVE_SYSTEM_PREFIXES` + `isLiveSystem()` |
| 8 | 🟢 低 | `.nomedia` 被拒刷屏审计日志（34+ 条/次） | 新增 `isKnownProtectFile()`：已知保护文件**静默跳过**，不记审计、不计拒绝 |

**额外加固**：
- `app_cache` 规则改为 `filesOnly=true`（只删直接子文件，**不递归子目录**）——
  修复 AGC 相机（`com.agc.gcam84`）把 21 个滤镜配置放在 `cache/sdcard/AGC.8.4/configs/`
  被误删的问题。真机验证：配置**完好保留**。
- `system_crash_logs` 移除 `/data/system/dropbox`（受用户违禁规则 `deny_path=/data/system`
  保护，移除避免"规则宣称能清但实际清不掉"的误导）。
- `system_junk_data` 移除 `/data/system/dropbox`（同上，且系统级路径默认只读）。

**效率现状**（真机实测）：
- 全量扫描（含 JUNK_SCAN 深度扫描）：**7~9 秒**
- 全量清理（31 条规则）：**5~11 秒**
- 扫描缓存 `ScanCache` 使二次扫描仅增量判定变动文件。

**真机验证结果（最终）**：
- `rejected=0`、审计日志无 REJECT/FAIL（唯一非 DELETE 行为 SESSION 汇总）
- 关键目录全完好：`/data/adb`、`/data/Python`、`/data/system`、
  `/data/misc/keystore`、`/data/vendor/camera`(6613)、微信 `MicroMsg`、
  AGC 滤镜配置(21 个)
- `uninstalled_leftover` 准确返回 1 个真残留（`com.android64bit.web`），
  而非修复前的 360 个

### 10.10 打包链改造：pack.sh 模块自包含（2026-10-03）

**需求**：模块目录内直接运行（MT 管理器）即可打包，依赖工具随模块分发（同 aapt/shfmt）。

**问题**：旧 `pack.sh` 依赖 `zip` 命令，但 Android 普遍没有——
`/system/bin` 无 zip，toybox/busybox 均无 zip applet；Ubuntu 的 zip 是
动态链接（glibc），拷入 Android 无法运行。

**方案**：新增 `tools/`（纯 Python，随模块分发）：

| 文件 | 作用 |
|---|---|
| `tools/zippack.py` | 打包。`zipfile` + `ZipInfo.from_file` **保留 Unix 权限位**（755/644 写入 external_attr，是 `post-fs-data.sh`/`service.sh` 可执行的前提）；支持符号链接、目录条目（含空目录）、流式 1MB 写入；自动排除 `__pycache__`；`--list` 可查条目权限 |
| `tools/zipcheck.py` | 校验。解压（保留权限）+ 源目录↔解压目录 SHA256 逐文件比对；`--manifest`/`--sha256` 辅助子命令 |

**`pack.sh` v3**（262→161 行）：
- 运行时探测顺序：`tools/python3` → `/system/bin/python3` → PATH → 其它常见路径
- 打包/校验全部调用 `tools/` 自带工具，**彻底摆脱 zip 与 unzip 二进制**
- 保留 v1 安全行为：前置检查（webroot + 含 `HttpBackend` 的 Main.dex）、
  临时文件落盘后再 `mv`（避免半成品）、最多 3 次重试、SHA256 逐文件比对
- 退出码：0=成功 1=校验失败 2=缺 python3

**`pack.sh` v4 + 工具进度输出（2026-10-03 追加）**：
- 需求：用户反馈「日志要显示完整过程，不能只显示结果，让用户干等结果」。
- 原状：`zippack.py` / `zipcheck.py` 只在**最后**打印一行汇总，500MB 打包 ~34s
  全程无输出，MT/终端里像卡死。
- 改造：
  - `zippack.py`：新增预扫描（统计文件数 + 原始总体积）；写入阶段每 25 文件打印
    `[ 82.4%] 75/91 文件  384.7 MB / ~532.7 MB  (24.6 MB/s, 16s)`；三阶段小标题 + 每行 flush。
  - `zipcheck.py`：解压按条目打印进度；源/解压清单各自打印 SHA256 进度；
    失败时补「概要」行；每行 flush。
  - `pack.sh`：统一 `step()` 分隔标题 + 5 阶段（探测 Python→定位工具→前置检查→打包→校验）；
    Python `-u` 无缓冲运行；打印开始时间与累计耗时；成功/失败均有汇总。
- 真机验证：91 文件 / 532.7MB → **430,117,015 字节**，34s，全程进度可见；
  `unzip -t` 零错误；SHA256 逐文件一致（91/91）。
- **新产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
  - **SHA256 = `6e1a9d690f5efe68dfdcd40ee1c9f6925e764f59c194a5fa800298589559bbe1`**
  - 关键条目 md5：Main.dex `d5c533e8`、webroot/index.html `3f750826`、
    tools/zippack.py `46570096`、tools/zipcheck.py `682f9f8c`、pack.sh `73dbb495`。

**真机验证**：`sh pack.sh` 一次通过 —— 90 文件、471,311,706 → 345,646,110 字节、
耗时 29s、SHA256 全部一致；产物 `unzip -t` 零错误、解压后权限正确还原；
zip 内无 `__pycache__`。

**MT 管理器用法**：进入母版目录，对 `pack.sh` 选择「执行」即可；
产物固定输出到**上层目录** `ZhangProtect-Android.zip`。

**打包链现状**：
- 母版 `/data/media/0/Download/Files/ZhangProtect-Android/`（88 文件 + tools/2 = 90）
- 产物 `/data/media/0/Download/Files/ZhangProtect-Android.zip`（约 330 MB，含 system/ 内 APK）
- 工程 `/home/projects/ZhangSystemDex` 的 `pack.sh` + `tools/` 与母版**保持一致**（进 Git）

### 10.11 内置 Python 运行时整合（2026-10-03，来自 Python_for_Android-3.13.5）

**背景纠正**：`/system/bin/python3` **不是系统自带**，而是 `PythonforAndroid`
模块挂载的包装脚本（本体 `/data/Python`）。因此上一版 `pack.sh` 仍**隐式依赖**
该模块存在。本轮回合把它整合进 Zhang 模块。

**整合内容**：

1. **运行时包** `Python.zip`（母版目录，随 zip 分发，**不入 Git**）
   - 真实内层运行时：87 MB / 8751 文件 / 解压 **270 MB**
   - 含 `Python/bin/python3.13` + `Python/lib/libpython3.13.so`
   - md5 `921bc3c63b36919de907f80d374c9a7c`
   - 来源说明见 `tools/README-Python.md`

2. **`service.sh` 新增 `ensure_python()`**：开机检查 `/data/Python`，
   缺失则 `unzip Python.zip -d /data` + `chmod -R 755`；已存在则零开销跳过。

3. **`tools/python3`**（模块自带入口）：设 `PYTHONHOME` + `LD_LIBRARY_PATH`
   后 exec `python3.13`。

4. **`pack.sh` 探测链**：`tools/python3` → 动态包装 `/data/Python` →
   `/system/bin/python3` → PATH → 其它；新增运行时自检。

**⚠️ 三个关键坑（勿踩）**：

| 坑 | 现象 | 正解 |
|---|---|---|
| 用错包 | `Python_for_Android-*.zip` 是**模块外壳**，解压得 `module.prop`/`META-INF` | 必须用其**内层** `Python.zip`；判别 `unzip -l X \| grep bin/python3.13` |
| 缺 LD_LIBRARY_PATH | 直接调 `/data/Python/bin/python3.13` → `CANNOT LINK EXECUTABLE: libpython3.13.so not found` | 必须 `LD_LIBRARY_PATH=/data/Python/lib`（`tools/python3` 已处理） |
| FUSE 无执行位 | `/data/media` 下 `[ -x file ]` 恒为假 | 探测用 `-f`，执行用 `sh <脚本>` |

**与独立 Python 模块共存**：两者释放位置相同（`/data/Python`）、内容同源，
无冲突——`PythonforAndroid` 已解压时 Zhang 的 `ensure_python()` 直接跳过。

**真机验证**：
- 隔离测试：从模块 `Python.zip` 解压 8751 文件 / 270 MB（2s），
  隔离运行时跑 `zippack.py` 成功（权限 0755/0644 正确）
- 端到端：`sh pack.sh` 走 `tools/python3` 分支一次通过，92 文件、
  558,559,253 → 430,108,449 字节、33s、SHA256 全部一致
- 产物含 `Python.zip`(87MB) + `tools/` 三件套 + `Main.dex` + `webroot`；`unzip -t` 零错误

**当前产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
（430,108,449 字节 ≈ 410 MB，92 文件 + 目录条目）

### 10.12 待办 / 可选增强

- [ ] 中高风险规则（微信聊天媒体按时间、QQfile_recv 等）尚未在真机做真实删除验证。
- [ ] `rubbish_rule_big_files_list` 目前复用 OLDER_THAN（ageDays=0），语义上应改为
      「按大小阈值列出」，当前仅作占位。
- [ ] WebUI 清理 Tab 的真机可视化确认（本机无 WebView 环境，仅做了无头校验）。
- [ ] 母版 `webroot/index.html`（54611 字节，含 rubbish UI）比工程内旧副本新，
      工程侧 `webroot/` 已废弃（.gitignore 忽略），正式资源以母版为准。

---

## 11. 任务记录：WebUI 电源读数补全 + 清理文案专业化（2026-10-03）

**需求**：用户反馈「电源页电池读数很多缺失；清理页描述不准确，要专业化，
去掉『实测……』等开发/测试阶段字样」。

**根因**：见 `docs/WEBUI_POWER_CLEAN_TASK.md`。
- 电源：后端 `/api/powerstatus` 只吐 4 字段，前端 6 格中 4 格硬编码 “—”；
  `PowerOptimizer` 已有全量快照但 WebUI 拿不到运行实例。
- 清理：`RubbishRuleSet` 的 `note` 含 `实测…`、`真机事故教训`、字面 `**`（前端
  `textContent` 原样显示）。

**改动（3 个 commit，均在 `main` 上，尚未 push）**：
- `5da06ba` feat(power)：PowerOptimizer 静态实例注册表（attach/detach）；
  重写 `HttpBackend.apiPowerStatus` → 一次 dumpsys 解析 + 合并快照 + OEM 兼容。
- `96598ea` refactor(clean)：31 条规则 note 专业化 + 页脚去内部类名 + docs 任务说明。
- `b01bdcc` fix(clean,power)：listOnly 统一零删除护栏（+ 回归守卫）；
  intField/boolField 允许冒号前空白（OPLUS `Battery current : n`）。
- 前端电源页改为 8 格电池 + 8 格策略 + 「最近动作」，重写 `refreshPowerStatus`。

**验证（真机已部署并生效）**：
- SelfTest：**PASS 48 / FAIL 0**（修复前 FAIL 2）。
- `/api/powerstatus` 真机返回全量字段，随息屏/充电实时变化。
- 电源页无头渲染 22/22、清理无头 15/15、结构 13/13 全 PASS。
- 真机文件 md5：Main.dex `d5c533e8…`、webroot/index.html `3f750826…`。

**未完成 / 注意**：
- ✅ 已按最新源码重新打包：`ZhangProtect-Android.zip`（430,117,015 字节，91 文件，
  SHA256 `6e1a9d69…`，见 §10.10），含新 Main.dex + 新 webroot + 进度版 pack.sh/tools。
- 3 个 commit 位于 `origin/main` 之后，视需要 `git push`。
- 备份：设备 `/data/adb/Zhang/_backup_powerfix_20261003-164603/`（旧 dex+webroot）。

---

## 12. 任务记录：设置保存不了 / 保存按钮变黑（2026-10-03）

**需求**：用户反馈「设置保存不了，且更改后保存按钮是黑的」。

**根因（WebUI 导航按钮状态逻辑自相矛盾）**：
- `markDirty()` 末尾调用 `setNavBusy(false)`；
- 而 `setNavBusy(busy)` 实现为 `const b = loaded ? !busy : true; ...disabled = b` ——
  **参数语义与命名相反**：`setNavBusy(false)` 实际把按钮 `disabled=true`。
- 于是 `updateDirtyCount()` 刚把「保存」置为可用，紧接着 `setNavBusy(false)` 又把它禁用；
  按钮永远停在 `.btn:disabled`（`opacity:.4`，深色主题下呈黑色），点击无响应 → 保存不了。

**修复（`webroot/index.html`）**：
- 新增 `busy` 状态位 + 统一真源 `refreshNav()`：
  保存/撤销 = `loaded && !busy && dirtyKeys.size>0`；重新加载 = `loaded && !busy`。
- `setBusy(b)` 语义与命名一致（true=忙碌）；保留 `setNavBusy` 作兼容别名。
- `load()` 全程 `setBusy`；`markDirty()` 只调 `updateDirtyCount()`（不再反向禁用）。
- `updateDirtyCount()` 复算脏标记（改回原值即移除）并 `refreshNav()`。
- `save()` 增加前置校验与正确的成功/失败按钮恢复。

**部署位置（三处一致，md5 `6119cd5265645d9567af725bf070f0e4`）**：
- `app/src/main/assets/webroot/index.html`（**git 跟踪的构建源，随 Main.dex 打包**）
- `/data/adb/modules/Zhang/webroot/index.html`（模块目录，WebUI X 实际读取）
- `/data/media/0/Download/Files/ZhangProtect-Android/webroot/index.html`（母版打包源）

**验证**：
- linkedom 有状态后端 save 流程 **14/14 PASS**（改动后按钮可用 / 落盘一致保留注释 /
  归零 / 二次可保存 / 撤销回滚）。
- 静态检查 PASS、清理 15/15、电源 22/22 无回归。
- 真机 API 往返：写入 → 磁盘反映 → 还原 → reload 均成功。

**⚠ 事故与补救（必读）**：
- 排查时用 `curl -X POST /api/write` 探测后端，**误将 `/data/adb/Zhang/switches.conf`
  覆盖成 `__PROBE__=1`（12 字节）**。
- 补救：从 `_backup_clean_20261003-140001/switches.conf`（14:00 快照，66 行）恢复，
  再调 `/api/reload` 触发 daemon 的 `ensureParamLines()/appendRubbishMissing()`
  **只追加缺失键** → 自动补回 36 个 `rubbish_*` 键，最终 86 键，配置恢复正常。
- **教训**：验证写接口务必用**副本路径或先备份**；`switches.conf` 是唯一真源，
  没有自动备份，误写会丢失用户设置。
- 备份目录：`/data/adb/Zhang/_backup_savefix_20261003-194400/`（旧 WebUI）。

**注意**：本次只改了 WebUI（webroot），**未重新打包 zip**；如需发布，改后可用母版
`pack.sh` 重打包。`Main.dex` 未变动。

---

## 13. 发布构建：含保存修复的正式 zip（2026-10-03 19:53）

**背景**：上一步修复了 WebUI「保存按钮不可点」（见 §12），本次按最新源码重新打包发布。

**产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,117,507 字节**（≈410 MB），91 文件
- **SHA256 = `19fc276aaa8089a049759ea1e0ad1865e5222095e39064bfee705cbcf5e25b93`**
- 打包耗时 35s（`pack.sh` v4 全程进度输出）

**关键条目（md5 与源一致）**：
- `Main.dex` = `d5c533e802a5fa6b93dbeb5e13ec3648`（含电源读数补全 + listOnly 护栏）
- `webroot/index.html` = `6119cd5265645d9567af725bf070f0e4`（**含保存按钮修复**）
- `webroot/config.json` = `d01d5fc7f95f27ed10a33d1a16c1c255`
- `pack.sh` = `73dbb4955be8790141abbd868db61c58`（v4 进度版）
- `tools/zippack.py` = `46570096ae97cb5a4ad4a40985991724`、`tools/zipcheck.py` = `682f9f8ca3e4a82f00336d3385361132`

**校验**：
- `unzip -t` 零错误；`pack.sh` 内置 SHA256 逐文件比对 **91/91 一致**。
- 发布前内容清查：无 `*.bak/*.tmp/*~/*.orig/__pycache__/.DS_Store` 残留；
  `Python.zip` 为必需内容物（保留）。
- 权限位：zip 忠实保存源 FUSE 模式（脚本 0770、数据 0660），与上一版正式包一致，
  安装后由 KernelSU 归一化（模块目录显示为 2777），**非回归**。

**对比上一版正式包**（17:05，430,117,015 字节，SHA256 `6e1a9d69…`）：
差异仅 `webroot/index.html`（保存按钮修复）；`Main.dex` 未变。
---
## 14. 修复：刷入新 zip 后 WebUI 仍无法保存配置（2026-10-04 07:30）
**现象**：用户刷入上一步的 zip 并重启后，反馈「webui 依旧无法保存配置」（保存按钮已可点，
但改动不落盘）。用户追加要求：**改成开关切换后 2 秒自动保存，同时保留保存按钮，且保存按钮永远可点**。
**根因（真机证据）**：
- WebUI 页面 origin 为 `webui-x://…`（opaque origin），向 `http://127.0.0.1:26437` 发请求。
- **GET 正常**（`/api/paths`、`/api/read` 是「简单请求」，无预检）；**POST `/api/write`
  带 `Content-Type: application/json` 会先发 `OPTIONS` 预检**。
- 后端 `route()` **没有 OPTIONS 分支**，落入 `else -> jsonError`；且 `respond()` **缺少
  `Access-Control-Allow-Private-Network: true`**。Chromium 的 **Private Network Access**
  会因此静默拦截从「公有/不透明 origin」到 **loopback** 地址的请求 → POST 从未到达 daemon。
- 佐证：连接 `ss -tlnp` 显示 26437 由新 dex（pid）持有，`/api/ping` 正常，但 POST 无落盘。
**修复**：
- 后端 `HttpBackend.kt`：
  - `respond()` 增加 `Access-Control-Allow-Private-Network: true` 与 `Vary: Origin`。
  - `handle()`：`if (method == "OPTIONS") ""`，直接返回**空体 2xx 预检**，不再进 `route()`。
  - `handle()` 增加**每请求日志** `请求 METHOD PATH`（便于诊断；受 `log_enabled` 门控）。
- 前端 `index.html`：
  - `markDirty()` 末尾 `scheduleAutoSave()`：**改动后 2 秒去抖自动保存**（`save({auto:true})`）。
  - `refreshNav()`：**保存/撤销按钮永远可点**（不再按 dirty/busy 禁用）。
  - `save(opts)` 支持 auto 模式，自动保存用轻提示「已自动保存」。
**验证**：
- 无头 harness（linkedom）：`save_harness.mjs` **17/17 PASS**（含自动保存 2 秒落盘、
  手动保存、撤销、按钮常可点）；`check_html` ALL PASS；`clean` 15/15；`power` 22/22。
- 真机 SelfTest（新 dex `c33ab7ae…`）：**47 PASS / 1 FAIL**；唯一 FAIL 为设备相关的
  AppOps `CAPTURE_CONSENTLESS_BUGREPORT_ON_USERDEBUG_BUILD`（user build 固有，与本次改动无关）。
- 真机端到端：开启 `log_enabled=true` 后，`POST /api/write` 日志显示
  `请求 POST /api/write` → `已写入 /data/adb/Zhang/switches.conf (4977 chars)` →
  `请求 GET /api/reload` → `已加载开关（85 项）`，全链路打通。`switches.conf` 104 行、
  与写入前 **diff 完全一致**（无数据丢失）；随后恢复 `log_enabled=false`。
**产物（已部署）**：
- `Main.dex` = `c33ab7aee533f8ee3723b39759055275`（2,503,564 B），同步到
  `/data/adb/modules/Zhang/Main.dex`、`/data/adb/Zhang/Main.dex`、母版 `Main.dex`。
- `webroot/index.html` = `f0f19f8e5f9f2415099ef9a6e08ab65b`（60,248 B），同步到模块
  `webroot/`、母版 `webroot/`、仓库 `app/src/main/assets/webroot/`。
- 备份：`/data/adb/Zhang/_backup_corsfix_20261004-073009/`（旧 dex + 旧 UI，模块+母版各一份）。
**注意**：本次**未重新打包 zip**。如需发布，用母版 `pack.sh` 重打包（会含新 dex + 新 UI）。
**⚠ 关键环境事实**：`config.conf` 的 `log_enabled=false` → 日志默认全静默；
排查时「看不到请求日志」≠「请求没到」，必须先临时置 `log_enabled=true` 再判断。
---
## 15. 发布构建：含 CORS/自动保存修复的正式 zip（2026-10-04 07:42）
**背景**：上一步修复了「WebUI 无法保存配置」（CORS PNA 预检 + 开关 2 秒自动保存，见 §14），
本次按最新源码重新打包发布。
**产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,118,171 字节**（≈410 MB），91 文件
- **SHA256 = `223aa415c76a9725825f7240c064ab6c99412b1c2e8590b1b1a0c61bb67ac719`**
- 打包耗时 37s（`pack.sh` v4 全程进度输出）
**关键条目（md5 与源一致）**：
- `Main.dex` = `c33ab7aee533f8ee3723b39759055275`（2,503,564 B，含 CORS/PNA 预检修复）
- `webroot/index.html` = `f0f19f8e5f9f2415099ef9a6e08ab65b`（60,248 B，含 **2 秒自动保存 + 保存按钮常可点**）
- `webroot/config.json` = `d01d5fc7f95f27ed10a33d1a16c1c255`
**校验**：
- `unzip -t` 零错误；`pack.sh` 内置 SHA256 逐文件比对 **91/91 一致**。
- 从 zip 解出关键条目 md5 与母版/模块完全一致（dex `c33ab7ae`、UI `f0f19f8e`、config `d01d5fc7`）。
**对比上一版正式包**（19:53，430,117,507 字节，SHA256 `19fc276a…`）：
差异为 `Main.dex`（CORS 修复）+ `webroot/index.html`（自动保存）；其余 89 文件不变。
---
## 16. 修复：保存很慢很卡 + 清理页开关不工作（2026-10-04 08:02）
**诊断手段**：本次先部署「诊断信标」版本（新增 `/api/diag`，页面把 loaded/toggle/save/js_error
事件 POST 回后端，写入 `log/webui_diag.log`，**独立于 `log_enabled`**）。用户复现后日志实证：
- `{"evt":"loaded","keys":104}` → 页面正常加载；
- `{"evt":"toggle","key":"module_appops_auth_enable","checked":true,"dirty":1}` → 开关页标脏正常；
- `save_start`→`save_ok`→`已写入 switches.conf` → 保存链路已通（CORS/PNA 修复有效）。
于是锁定两个**新**问题（与 CORS 无关）：
**问题 1：保存很慢很卡**
- 根因：`HttpBackend extends DaemonLoop(ctx, 1000L)`，`tick()` 每周期只 `accept()` **一个**连接，
  → HTTP 约 **1 请求/秒**（一次保存含 OPTIONS+POST+reload ≈ 3 秒）。日志时间戳逐条相差 ~1.003s 可证。
- 修复：`onStart()` 启动**独立 accept 线程**（阻塞 `accept` + `soTimeout=1000`），
  每连接交给**短命工作线程**处理；`tick()` 改为空操作。响应时间 **~1s → ~3ms**（实测 10 连发均 ~0.003s，并行正常）。
**问题 2：清理页开关不工作**
- 根因：`/api/switch/set` 写盘后**未刷新内存配置**；`ConfigManager.reloadSwitchesIfChanged()`
  依赖 `File.lastModified()`，而 `/data/adb` 的 mtime **只有整秒粒度**，同一秒内「写盘+重载」会被
  判定 mtime 未变而**跳过重载** → `/api/rubbish/status` 持续返回旧值 → 清理页 3 个开关看起来"没生效/复原"。
- 修复：新增 `ConfigManager.reloadSwitches()`（**强制**重载，绕过 mtime），
  在 `apiSwitchSet`、`apiWriteFile`(switches.conf)、`apiReload` 三处调用。
  实测 `switch/set false → status masterEnabled=false → 文件=false → set true → true` 即时一致。
- 前端 `setCleanSwitch()`：成功后回读状态 + 提示「已开启/已关闭」，失败回读纠正界面。
**产物（已部署）**：`Main.dex` = `aeff2f9330e842c1427e6c27875fe079`；
`webroot/index.html` = `8b37730b28850758a5f942e275306cc3`。模块 + 母版均已同步；daemon 已重启。
**验证**：harness save 17/17、clean 15/15、power 22/22、check_html ALL PASS；
真机 SelfTest **98 PASS / 2 FAIL**（2 个 FAIL 均为该 ROM 固有的 AppOps 随机项，与本次改动无关）；
真机 HTTP 延迟 ~3ms；清理页开关即时生效。
**⚠ 诊断信标保留**：`/api/diag` → `/data/adb/Zhang/log/webui_diag.log`（不依赖 log_enabled），
后续排查 WebUI 问题可直接查看。
**注意**：本次**未重新打包 zip**（母版已含新 dex+UI，需要时用 `pack.sh` 重打包）。
---
## 17. 新功能：免重启在线更新（OTA，2026-10-04 08:18）
**需求**：把最新 `Main.dex` / `webroot/index.html` 写入「**已存在的模块目录**」并重启
daemon 进程即可生效——**无需刷 zip、无需重启设备**。
**实现**：
- 后端（`HttpBackend.kt`）新增：
  - `GET  /api/ota/status`：返回模块目录、当前 dex/UI 大小与 md5、更新源、curl 是否可用。
  - `POST /api/ota/update`：`curl` 下载最新 dex+UI → **校验 dex 魔数（`dex\n`）** →
    备份到 `_backup_ota_<时间戳>/` → 写入模块目录（dex 写 `MODDIR/Main.dex`，UI 写
    `MODDIR/webroot/index.html`）→ 写 `ota_restart.sh` 并用 **`setsid` 完全分离**运行，
    其中 `停止Dex.sh` → `service.sh` 完成 daemon 重启。
  - `ConfigManager` 暴露只读 `moduleDir`。
- 前端：设置页新增「在线更新（免重启）」卡片：**检查 / 一键更新**，更新后自动轮询
  `/api/ping` 等待 daemon 回来并刷新页面。
- 脚本：母版 + 仓库新增 `免重启更新.sh`（无 WebUI 时可用；目标固定
  `/data/adb/modules/Zhang`；含魔数校验、备份、重启）。
**实测踩坑与修复（关键）**：
1. **`raw.githubusercontent.com` CDN 缓存滞后**：刚 push 的新 dex 会被当成旧版本下载
   （实测本地 HEAD=`23361326`，raw 仍返回上一版 `aeff2f93`）。改用
   **`https://github.com/FairyXH/ZhangSystemDex/raw/main/`**（302 跳转到带 cache-busting
   的 CDN），实测能取到最新；脚本亦加 `?ts=` 与 `Cache-Control: no-cache`。
2. **重启进程被连带杀死 / 配置根 dex 被写成 0 字节**：原 `( ... ) &` 子 shell 是 daemon
   的子进程，`停止Dex.sh` 的 `pkill` 会把它一起杀掉，daemon 不回；且后端直接写
   `/data/adb/Zhang/Main.dex` 与重启的 `service.sh` 同步产生写竞态。修复：重启逻辑写入
   `ota_restart.sh` 用 `setsid` 在新会话运行（sleep 2 等旧进程退出再拉起），并**移除**后端
   对配置根的重复写（一律交由 `service.sh` 同步）。
**验证**：
- 真机把模块 dex 换成旧版 `d5c533e8` → `POST /api/ota/update` → dex 更新为最新、
  daemon 自动重启、配置根 dex 非空、`/api/ping`=pong；`ota_restart.log` 显示
  `已停止 daemon` → `synced` → `started`。全程无重启设备、无刷 zip。
- `免重启更新.sh` 同样跑通（旧版→最新）。
- harness save 17/17、clean 15/15、power 22/22、check_html ALL PASS；
  真机 SelfTest **49 PASS / 1 FAIL**（唯一 FAIL 为该 ROM 固有的 AppOps 随机项）。
**产物**：`Main.dex` = `eeb41a01e46a9519a6c7ff4bd742c24e`；
`webroot/index.html` = `c8b99094c782a86746742dba8a8fb03f`；`免重启更新.sh` = `021096a5dfa74e146990c52899082db0`。
模块 + 母版 + 仓库 + GitHub 均已同步；daemon 运行中。
**用法**：设置页 →「在线更新（免重启）」→「一键更新」；或直接执行
`sh /data/adb/modules/Zhang/免重启更新.sh`（脚本可从仓库下载）。
**⚠ 提醒**：GitHub raw CDN 有滞后，**push 后请等 1~2 分钟再执行 OTA**，否则可能仍取到上一版。
---
## 18. 修复：清理规则开关不持久化（"QQ 接收文件"打开后复原，2026-10-04 08:26）
**现象**：用户打开「清理」页的「QQ 接收文件」等**规则勾选**，退出 WebUI 再进又复原。
**诊断证据**：`webui_diag.log` 显示该操作时段只有 `loaded`，**没有任何 `toggle`/`save` 事件**
（说明勾选处理器压根没走持久化路径）。
**根因**：`renderCleanCell()` 里规则复选框的 `onchange` 只做
`CLEAN_SELECTED.add/delete(r.id)`，**仅改内存、不写盘、不提示**——退出页面 `CLEAN_SELECTED`
重置即复原。（这与 §16 修的是**不同**问题：§16 是清理页三个**总开关**的 mtime 热重载；
本节是**规则勾选**从不落盘。）
**修复**：勾选后立即用规则自带 `switchKey` 调 `POST /api/switch/set` 写入 `switches.conf`，
再 `/api/reload` + toast 提示；失败则回读 `GET /api/rubbish/rules` 的真实 `enabled` 纠正界面。
（`/api/rubbish/rules` 每条规则本就带 `switchKey` 与 `enabled` 字段，直接复用。）
**验证**：harness `rule_persist_harness.mjs` → 勾选 `qq_file_recv` 触发
`/api/switch/set {key:"rubbish_rule_qq_file_recv",value:"true"}` + `/api/reload`（PASS）；
真机 `false→true` 写盘成功、`rules` 接口 `enabled=true`，还原正常。
**产物**：UI = `70dba478e7f3adf6c57640ca9d9bceb1`（dex 未变 `eeb41a01`）。
已通过 `部署母版到已安装.sh` 同步母版→已安装并重启 dex。
**教训（重要）**：WebUI 里凡是"看起来是个开关"的控件，其 `onchange` **必须**最终调用
`/api/switch/set`（或 dirty+save）落盘；只改前端内存状态的控件一律会"退出即复原"。
---
## 19. 修复：开关切换的"动作"不生效（引入 onSwitchesChanged 回调，2026-10-04 08:33）
**背景**：用户确认"开关可以用了，但对应的动作是否真的生效？"——这是关键的正确性问题。
**发现（真机线程级证据 + 日志）**：开关能写盘、能读回，但**对应模块的启动/停止不会即时发生**。
实测：打开 `network_ipv6_disable_enable` 后 `/proc/sys/net/ipv6/.../disable_ipv6` 不变、
无 `NetworkModule` 线程；打开 `max_cpu_enable` 同理。
**根因（我上次修复引入的回归）**：§16 为解决"写盘后读回旧值"把 `/api/switch/set`、`/api/reload`
改为调 `reloadSwitches()`（强制重载）。但该函数**只刷新内存配置、不触发 `syncModules()`**；
而 `Main` 主循环靠 **`reloadSwitchesIfChanged()` 的 mtime 检测**才调用 `syncModules()`——
`reloadSwitches()` 已经把 `switchesLastModified` 记成新值，主循环于是**认为"无变化"**，
`syncModules()` **永远不会被调用** → 开关只是被"记住"，动作要等重启 daemon 才生效。
**修复**：
- `ConfigManager` 新增 `@Volatile var onSwitchesChanged: (() -> Unit)?`，
  `reloadSwitches()` 与 `reloadSwitchesIfChanged()` 在重载后都触发它。
- `Main.main()` 调整顺序：**先定义 `syncModules()`、注册回调，再启动 HttpBackend**，
  回调内调用 `syncModules()`；并给 `syncModules()` 加 `synchronized(syncLock)` 串行化。
  这样 WebUI 每次写 switches.conf / `/api/reload` 都会**立即**启停对应模块。
**验证（最硬的证据 = 线程数实时变化）**：
- `network_ipv6_disable_enable`：开 → `NetworkModule` 线程 1，关 → 0；
- `memory_clean_enable`：开 → `MemoryModule` 1，关 → 0。
均**瞬时**生效，无需 60s、无需重启设备。
- 真机 SelfTest：49 PASS / 1 FAIL / 2 WARN（FAIL 仍为该 ROM 固有 AppOps 随机项）。
**产物**：`Main.dex` = `ae9fb306b7751bc6edadb51cad903a02`（含 §18 的 UI `70dba478`）。
已通过 `部署母版到已安装.sh` 同步并重启 dex。
**⚠ 架构备忘（新 Agent 必读）**：`Main` 用 `ModuleEntry(name, enabled, factory)` 表驱动
模块启停；任何新增开关若要"即时生效"，必须确保其 `enabled` 被纳入 `entries`，
且走 `reloadSwitches()` 路径（已自动触发回调）。运行时排障可用
`for t in /proc/$(cat /data/adb/Zhang/daemon.pid)/task/*/comm; do cat $t; done` 看模块线程是否在跑。
---
## 20. 概览页「实时总览」（2026-10-04 08:55）
**需求**：概览页显示尽可能全面的实时信息——各功能运行状态、清理了多少垃圾、
省电运行如何等，且要"实时"。
**后端：新增 `GET /api/overview`（HttpBackend.kt）** 一次性聚合返回：
- `modules[]`：每个模块 `key/label/desc/enabled/running/tickCount/lastTickAgoMs/
  lastAction/counters/extras`。**`running` 是线程级真相**（模块线程是否在跑），
  与 `enabled`（开关是否打开）区分开。
- `power{}`：省电子系统快照（`policyLevel/Text`、各类计数、`lastAction`、
  `lowBatteryThreshold`、`screenOffCount/screenOnCount`）。
- `clean{}`：`masterEnabled`、`ruleTotal/ruleEnabled`、`lastSession`、
  累计 `cleanedFiles/cleanedBytes/cleanRuns`、`lastCleanAgoMs`、`recent[]`（审计尾部）。
- `system{}`：`battery{}`、`screenOn`、`mem{}`（totalKv/availKb/usedKb/usedPercent）、
  `storage{dataTotal,dataFree}`、`load1`、`uptimeMs`、`procCount`、`thermalMaxMilliC`。
- `backend{}`：`pid/port/uptimeMs/dexMd5/dexSize/moduleDir/configRoot`。
- `config{}`：`switchCount/logEnabled/powersave`。
**新增 `core/RuntimeRegistry.kt`（全局运行时状态注册表）**：跨线程共享模块运行态。
- `Main` 启动时把每个 `ModuleEntry` 注册进表（带 label/desc）；`syncModules()`
  里 `setEnabled/setRunning`；模块启动时 `module.registryKey = entry.name`。
- `DaemonLoop` 每轮 `tick()` 成功后自动 `RuntimeRegistry.markTick(key)` 上报心跳。
- `SystemTuningModule` 执行清理后 `bump("cleanFiles"/"cleanBytes"/"cleanRuns")`
  并写 `extras["lastCleanMs"]` 等，供概览展示"清理了多少垃圾"。
- 辅助：`ProcessUtils.selfPid()/processCount()`、`ConfigManager.allSwitchKeys()`。
**前端：重写概览页（index.html）**：
- 区块：实时总览（后端/daemon/配置/改动/dex 版本）→ 系统实时状态（12 项）→
  功能模块运行状态（列表，运行中绿点+心跳+计数，按 运行>启用>停止 排序）→
  省电优化运行状况 → 垃圾清理运行状况（含最近审计）→ 快速开关。
- `refreshOverview()` 每 **2 秒** 轮询 `/api/overview`；`document.hidden` 或
  非概览 Tab 时**暂停刷新**（省电）；切回概览 Tab 立即刷新一次。
- 新增工具函数 `fmtDuration/fmtAgo/setText/tempC`；CSS 新增 `.live-dot`/`.ovmod` 等。
**验证**：
- 真机 `GET /api/overview` 返回真实数据（pid/uptime/dex md5/电池 81%/内存 79%/
  load 9.51/950 进程/温度 77.1°C/清理审计行/模块心跳 tickCount）。
- 无头 harness `overview_harness.mjs`（linkedom，mock overview JSON）**27/27 PASS**；
  回归 `save_harness`(17) / `clean_harness`(15) / `power_harness`(22) 全通过。
**产物**：`Main.dex` = `3ecc23c6bc1ce431cd968bfa1ec4bbb2`（2,533,308 B）；
`webroot/index.html` = `4549c5729d522f5e6229394093090c64`。已通过 `部署母版到已安装.sh`
部署并重启 dex。Git：`51f8cd6`。
**⚠ 扩展点（新 Agent 必读）**：任何模块要往概览暴露更多信息，只需
`RuntimeRegistry.get("<entry.name>")?.bump("计数名")` 或 `put("字段", 值)`；
`/api/overview` 会自动带出它的 `counters`/`extras`，前端模块行也会通用渲染
`extras`（除 lastClean* 外）。新增开关的 label/desc 在 `Main.kt` 的 `ModuleEntry`。
---
## 21. 概览增强：历史累计清理量 + 各模块反馈（2026-10-04 09:20）
**动机**：§20 的首版里，"清理了多少垃圾"只统计**本次 daemon 运行以来**（内存计数，
重启归零），且除 `system_tuning` 外多数模块没有实质反馈。
**后端**：
- `AuditLog.cumulative()`：从审计日志（当前 + `.1` 滚动备份）聚合历史累计——
  DELETE 行数=清理文件数、`cols[3]` 的 `<n>B` 求和=释放字节、SESSION 行数=会话数。
  **跨 daemon 重启仍然可见**（读文件而非内存）。
  `/api/overview` 的 `clean` 段新增 `totalFiles/totalBytes/totalSessions`。
- 各模块上报运行时反馈（`RuntimeRegistry`）：
  - `GameOomProtectModule` → `extras{protectedCount,knownGames,protectedList}`；
  - `ServiceGuardModule` → `counters{healthRuns}` + `extras{lastHealthMs}`；
  - `StorageIsolationModule` → `counters{isolated}` + `extras{lastIsolateMs}`。
**前端**：
- 清理区块新增「累计清理文件 / 累计释放空间 / 累计清理会话」（取 `total*`）
  与「本次运行清理」（`cleanRuns 次 / cleanedFiles 文件`）。
- 模块行新增 `extraLines()`：把 `extras` 用 `EXTRA_LABELS` 映射成中文友好名
  （保活进程/识别游戏/隔离时间/上次巡检…），时间戳自动 `fmtAgo`、字节自动 `fmtBytes`。
  计数区还新增"巡检 N 次""隔离 N 项"。
**验证**：
- 真机 `/api/overview`：累计清理 **23536 文件 / 1.82 GB / 24 会话**（从审计日志聚合）；
  模块 extras 正确（game_oom_protect 识别 6 游戏、service_guard 上次巡检、storage_isolation 上次隔离）。
- harness `overview_harness2.mjs` **30/30 PASS**（含累计量、中文 extras 标签断言）。
- 真机 SelfTest：49 PASS / 1 FAIL（FAIL 为 ROM 固有 AppOps 随机项，另一轮为 0 FAIL）。
**产物**：`Main.dex` = `294411005af8befcfc2ddfdf3cd01fdf`；
`webroot/index.html` = `8cf08933d9793e02146ef2206597c842`。已部署并重启 dex。Git：`de29fbd`。
**⚠ 排障提醒**：部署脚本 `部署母版到已安装.sh` 必须用 **`super_admin:shell`（Android）**
执行——`super_admin:terminal`（Ubuntu）看不到 `/data/adb`，会报"模块未安装"。
---
## 22. 修复：概览「最高温度」显示 6000℃（2026-10-04 09:30）
**现象**：概览页「最高温度」显示 6000℃ 等离谱值。
**根因（三层叠加）**：
1. **前端单位猜测错误**：旧 `tempC()` 逻辑是"`|v|>100000` 才 `/1000`，否则 `/10`"。
   而 thermal zone 上报 **`60000` = 0.001°C = 60°C**，因 <100000 被当成 0.1°C →
   渲染成 **6000.0°C**。
2. **后端未过滤非温度 zone**：直接对所有 `thermal_zone*` 取 max。本机 104 个 zone
   里混着非温度信号：`vbat`=mV（3986）、`pm8550b-ibat-lvl0`=mA(-93)、
   `pm8550b-bcl-lvl*`=0、`mmw0/1/2`=-273000（无传感器占位）。
3. **重复计数**：`/sys/class/thermal` 与 `/sys/devices/virtual/thermal` 是**同一批
   zone 的两个 sysfs 视图**，两个都扫 → 有效 zone 计数翻倍（76×2=152）。
**修复**：
- **后端归一化温度**（HttpBackend）：`system.battery.temperatureC = raw/10`（`dumpsys
  battery` 是 0.1°C 单位）、`system.thermalMaxC = maxValidMilli/1000`；均为 1 位小数
  的 °C 字符串，无效/越界（超出 -40..125 或 = -1）返回 `"-1"`。旧字段
  `temperature`(raw) 保留、`thermalMaxMilliC` 删除。
- `readThermalMaxC()`：过滤 `v<=0` 与 `v>150000`；按 zone `type` 排除
  `ibat/vbat/bcl-lvl/current/voltage`；**只扫一个根目录**（优先 `/sys/class/thermal`）。
  新增 `thermalZoneCount` 暴露有效 zone 数。
- **前端移除单位猜测**：新增 `fmtTempC(c)`，只格式化后端已归一化的 °C（`-1`/越界
  → `"—"`）；不再有 `tempC` 旧函数。
**验证**：
- 真机：电池温度 **37.0°C**、最高温度 **56.9°C**（zone=76）——正常。
- harness `temp_harness.mjs` **6/6 PASS**（含 `fmtTempC(-1)→—`、`fmtTempC(6000)→—`）；
  回归 save(17)/clean(15)/power(22) 全通过。
**产物**：`Main.dex` = `100f4d3b76979e0e1041c18e5b3cb149`；
`webroot/index.html` = `6916d2050c2236172d3f47918eae0555`。已部署并重启 dex。Git：`a3d8cde`。
**⚠ 教训**：sysfs 温度单位不统一（battery=0.1°C、thermal=0.001°C），且 zone 里混有
非温度信号；**归一化必须在后端做一次**，前端不得再猜单位。Kotlin 块注释里写路径
`thermal_zone*/temp` 会把 `*/` 当成注释结束符导致编译失败——已注意规避。

---

## 23. 修复：「目标应用运行中强制清理」开关打不开（2026-10-04 09:55）

**现象**：清理页「目标应用运行中强制清理」开关点击后立刻弹回未勾选，像是「打不开」。

**根因（纯前端漏写盘）**：`webroot/index.html` 的 `setCleanSwitch(key, on)`
（修复后约 1625 行）此前只做：
```
await api("/api/reload");  await loadCleanState();  toast(...);
```
**从不调用 `/api/switch/set`**。于是点击 → reload（无变化）→ `loadCleanState()`
从 `/api/rubbish/status` 回读**后端旧值 false** → 把刚勾上的复选框改回 false。
三个总开关（`swCleanMaster`/`swCleanScreenOff`/`swCleanForce` →
`rubbish_clean_enable` / `rubbish_clean_screen_off_only` / `rubbish_force_when_running`）
都受此影响。后端 `apiSwitchSet` 本身正常（真机 `curl` 写盘 OK）。

**修复**：在 `setCleanSwitch` 开头补上（参照 `persistCleanRule`）：
```js
await api("/api/switch/set", { key: key, value: on ? "true" : "false" });
await api("/api/reload").catch(()=>{});
await loadCleanState();
```

**验证**：
- harness `cleanswitch_harness.mjs`（新建，linkedom 驱动真实 `onchange`）：
  **修复版 7/7 PASS**；**对旧部署版（`6916d20…`）跑同一 harness → 4 FAIL**
  （缺少 `/api/switch/set`、后端未变、界面被复原），精确复现原 bug。
- 真机端到端：`POST /api/switch/set {rubbish_force_when_running,true}` →
  `reload` → `/api/rubbish/status` 的 `forceWhenRunning=true`，`switches.conf` 已写盘
  （验证后已复位为 false）。
- SelfTest：**47 PASS / 1 FAIL / 2 WARN / 7 SKIP**（唯一 FAIL 为该 ROM 固有 AppOps 随机项）。

**产物**：`Main.dex` 未变 = `100f4d3b76979e0e1041c18e5b3cb149`；
`webroot/index.html` = `d16c93a6abd2fbe1ef43a1791608df02`。已部署到 `/data/adb/modules/Zhang`
并重启 dex。Git：`cb87d6e`。

**⚠ 教训（再次确认）**：凡是「开关」控件，其 `onchange` 必须最终写出**持久化 API**
（`/api/switch/set`）再回读；只 `reload`+回读会把界面覆盖回旧值，表现为「开关打不开」。
同类问题已在 §18（清理规则勾选）出现过一次。
**⚠ harness 陈旧项**：`check_html.mjs` 报 `MISSING DOM ids: ovPortPid` —— 该 id 在当前
概览页已废弃（line 801 仍引用），属**预存**问题，与本次修复无关，两个版本均报同样结果。

---

## 24. 清理扫描算法现状与性能分析（2026-10-04 11:45）

### 扫描算法（现状）
规则驱动 + 多模式（`core/rubbish/RubbishCleaner.kt`）：
- **普通模式**（GLOB / DIR_CONTENT / DIR_SELF / EMPTY_DIR / OLDER_THAN / UNINSTALLED_SCAN）：
  按规则 roots 展开 → 递归 `listFiles()` → 按名/模式/时间匹配。
- **深度模式**（`scanDeep`，带 `ScanCache` 增量）：
  - `scanApk`：`collectFiles`（minBytes 预筛）后逐个 `FileIdentifier.isApk`（读文件头魔数，无视扩展名）。
  - `scanBigFiles`：收集 ≥阈值文件后按大小降序（listOnly）。
  - `scanDuplicates`：先按 size 分组 → `DUP_CONTENT` 再算 SHA-256（前 4MB 截断）细分 → `pickVictims` 保留最新。
  - `scanJunk`：栈式遍历（非递归）+ `JunkPatterns.isJunk`（名/后缀/父目录语义/魔数/零字节）。
- **缓存**：`ScanCache` 按规则存 `<rootDir>/rubbish_index/<ruleId>.idx`（`kind\tpath\tsize\tmtime`），
  用 `isFresh(path,size,mtime)` 判定复用。
- **并行度**：**无**（全单线程串行，未用线程池/协程）。
- **调用**：`apiRubbishScan` 在 HTTP 工作线程同步执行，全量扫描阻塞该请求。

### 实测性能（真机）
| 项 | 数据 |
|---|---|
| `/data/user/0` 顶层目录 | 674 |
| `/data/user/0` 文件总数 | **565,647** |
| 纯 `find`（无读取，仅 stat） | **55 s** |
| `junk_all_apps` 扫描（roots=`/data/user/<u>`） | **>180 s 超时未完成** |
| `junk_media_apps` 索引大小 | 4.5 MB（正常产出） |
| `junk_all_apps.idx` | **不存在**（超时未走到 `cache.save`） |

### 发现的真问题（性能未最优，有 4 个瓶颈）
1. **单线程串行**，无并行：IO 密集却只用 1 个核；`find` 基线已 55s，Kotlin 再加 JVM `File`
   对象与逐文件 `isDirectory/isFile`（各一次 stat）→ 超 180s。
2. **缓存只在“全部跑完”后才 `cache.save`**（第 198/260 行）——大范围规则**超时/中断则索引
   永不落盘**，下次仍全扫，**增量缓存对最大规则完全失效**。
3. **目录级增量已实现但从未调用**：`ScanCache.Index.dirFresh()`/`stampDir()`/`dirStamps`
   均无调用点（grep 确认），文档声称的“目录 mtime 未变则整目录复用”**根本没接上**。
   现状是“逐文件 size+mtime 比对”——对 56 万文件而言，光是列出+stat+查表就极慢。
4. **默认 roots 过大**：`junk_all_apps` 直接扫整个 `/data/user/<u>`（含 Android/data、obb、
   各 App 的 files/、databases/ 等一切），`maxDepth=12`；穷举全私有目录本质上是 O(全部文件)。

### 建议优化方向（未实施，待用户拍板）
- **A. 目录级剪枝（收益最大、风险最低）**：真正接上 `dirFresh`——目录 mtime 未变则整棵子树
  跳过，只递归 mtime 变化的目录。可把“暖扫”从分钟级降到秒级。
- **B. 分片+可中断**：扫描按顶层目录分批，边扫边 `cache.save`（或扫完一个 App 存一次），
  避免超时丢索引；前端显示进度。
- **C. 并行化**：用固定线程池（如 4）并行遍历互不相交的顶层子树，注意别打爆 emmc/ufs。
- **D. 收窄默认范围**：`junk_all_apps` 默认只扫“已知垃圾目录语义”（cache/logs/tmp/crash），
  或按 App 分组让用户勾选，而非无条件递归整个 `/data/user/0`。
- **E. 预筛**：先按目录名白名单（cache/tmp/logs/crash）命中才深入，其余不动。

**结论**：当前算法“正确性/安全性”没问题（删除全经 RubbishGuard），但**性能未最优**——
大范围深度扫描严重超时，且增量缓存设计有三处未生效。上面 A+B 是性价比最高的两项。

---
## 25. 性能优化：清理深度扫描 A+B+C（2026-10-04，已真机验证）
**目标**：解决 §24 记录的 `junk_all_apps` 深度扫描超时（>180s）。
**已实施三项（commit `069cadb`）**：
- **A. 分片并行扫描**：新增 `core/rubbish/ParallelScanner.kt`——顶层子项切分为独立分片，
  无锁并发队列 + `rubbish_scan_threads`（1..8，默认 4）线程遍历，互不相交子树。
- **B. 分片级增量缓存**：重写 `ScanCache`——以「分片聚合(ShardAgg)」替代「每目录聚合」，
  索引体积 **33MB → ~50KB**；仅落盘命中(J)条目，剔除海量非命中(O)条目导致的写放大；
  索引格式转义路径中的制表符/换行（修复文件名含 `\t` 导致的行错位）。
- **C. 多线程 IDM 式分片**：扫描边进行边落盘索引，避免超时丢索引。
**真机实测**：`junk_all_apps` 冷扫 **54s → 暖扫 4s**（约 13x）；索引 33MB → 50KB。
**验证**：SelfTest 新增「清理.并行扫描计数一致」——冷/暖/再暖 均 3文件/3字节（确定性）。
**注意**：`rubbish_scan_threads` 为新增键（默认 4），过大可能打爆 emmc/ufs。

---
## 26. 新功能：OOM 保护名单（2026-10-04/05，已真机验证）
**需求**（用户原文）：新增 oom 保护名单，应用选择器 + 手动编辑列表（一行一个包名）；
像保护游戏 oom 一样把列表应用 oom=-1000、提高进程优先级，但**不超过系统自身**；
处理好内存泄露（无法释放）；默认内置 `com.ai.assistance.operit`。
**实现**：
- `core/OomProtectList.kt`：名单文件 `oom_protect.conf`（一行一包名，`#` 注释），
  `normalize()` 过滤注释/空行/重复/非法包名；`DEFAULT_PACKAGE=com.ai.assistance.operit`。
- `modules/OomProtectModule.kt`（DaemonLoop 5s）：
  - **安全钳制** `clampOom(v)=v.coerceIn(-900,1000)`——用户应用最激进只到 **-900**
    （system_server 同级，永不越过 init），`SAFE_FLOOR=-900`；主进程默认请求 -1000→钳到 -900，
    子进程 -700；主进程温和 `renice(-10)`，不做 RT 抢占。
  - **内存泄露/无法释放处理**：`lastAdj`（pid→上次值，避免重复写）每轮只保留存活 pid；
    `touchedPids` 记录改动过的 pid，移出名单或关闭时**还原为 0**（仅存活进程）；
    `onStop` 清空全部结构。
- HTTP：`/api/oom/read|write|status|apps`（HttpBackend）。
- 开关：`oom_protect_enable`（**默认 true**，登记于 `SWITCH_DESCRIPTIONS` + `SPECIAL_DEFAULT_TRUE`
  + `ensureMissing()` 显式默认行）。
- WebUI：设置页新增「OOM 保护名单」三区块（总开关+受保护状态 / 应用选择器 / 手动编辑 textarea）。
**真机验证**（自建测试模块目录启动 daemon）：
- 开启后 operit `oom_score_adj=-900`（钳制生效）；关闭后**还原为 0**。
- `/api/oom/{read,write,status,apps}` 输出**合法 JSON**（python json.load 校验 VALID）。
- SelfTest：`OOM.安全钳制/名单归一化/名单读写往返/默认内置包名/配置键齐备` **5/5 PASS**。
**已修的关键 bug（前后端联调发现）**：`JsonBuilder.arr` **不自动插入逗号**，
原 `apiOomWrite/apiOomStatus/apiOomApps` 生成 `[\"a\"\"b\"]`/`[{}{}]` 非法 JSON；
已统一改用 `jsonStrArray()` / 显式 `comma()`（commit `11f7d92`）。**改动 JSON 数组务必注意此点**。

---
## 27. ⚠ 事故：重启锁屏卡死（package.xml 损坏）与包状态改写加固（2026-10-05）
**现象**：用户开启大量清理功能后重启，**锁屏输入密码后卡死无法进入**；TWRP 删除模块 +
`/data/system/package.xml` 后恢复。**模块目录被删，当前未重装**（母版完好）。
**根因（源码走查）**：仅「改写包状态」的操作会导致该症状，锁定：
1. `AppManagerModule.disableApp()` 原含 `pm uninstall <pkg>`（**全用户卸载**）+ disable，
   且被 `SystemTuningModule.heavyTick()` **周期反复触发** → packages.xml 高频重写；
2. `SystemTuningModule` **无条件** `pm uninstall --user 0 com.oplus.appdetail`（每个常规周期）；
3. `chattr +i` 无存在性校验。
**加固（commit `401c47b`，已构建通过）**：
- `disableApp`：**移除全用户 `pm uninstall`**，改仅 `disable-user`（可逆）；新增**幂等判断**
  （`FrameworkOps.applicationEnabledState()`，已禁用则跳过）→ 杜绝重复改写。
- `uninstallAppDetailOnce()`：仅当包存在时执行**一次**（进程生命周期去重）。
- `chattr` 前校验文件存在，失败静默。
- 新增 `core/PackageStateBackup.kt`：首次改写包状态前滚动备份 packages.xml 等到 `<root>/backup/`。
**详细复盘**：`docs/incidents/2026-10-05_boot_hang_package_xml.md`。
**教训**：包状态改写类操作（uninstall/disable）**永远不要**在周期任务里裸跑；
开机阶段（post-fs-data/service）**绝不做**包卸载/禁用。
**当前设备状态**：`/data/adb/modules/Zhang` 已删除（未重装）；`/data/adb/Zhang` 配置根保留；
母版 `/data/media/0/Download/Files/ZhangProtect-Android/` 已更新为最新 dex + webroot。
重装路径：用户自行用母版 `pack.sh` 打 zip 或直接部署目录。

---
## 28. 当前构建/部署关键值（2026-10-05）
- `Main.dex`（含 OOM + 安全加固 + JSON 修复）= md5 `83b3e6d06d8f444e579ab305666da1db`（2,571,408 B）。
- `webroot/index.html`（含 OOM UI）= md5 `063d57cf47646555ca607bbdaa4ea7cc`。
- 已同步：母版 `Main.dex` + `webroot/index.html`（见 §27）。
- Git 最新 4 commit：`069cadb`（清理性能 A+B+C）→ `401c47b`（安全加固）→
  `7194cf4`（OOM WebUI）→ `11f7d92`（OOM JSON 修复）。
- **未 push**（本地 main 领先 origin/main 4 个 commit）。

---
## 29. 发布构建：含 OOM 保护名单 + 安全加固的正式 zip（2026-10-05 09:04）
**背景**：本次汇总发布三项改动——清理深度扫描性能 A+B+C（§25）、
OOM 保护名单（§26）、包状态改写安全加固（§27/§28）。
**产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,197,301 字节**（≈410 MB），100 文件
- **SHA256 = `7188a43f7f8d6e30a958d327dae7fb190407ed0bc27fc09598bd1308b4fdf875`**
- 打包耗时 32s（`pack.sh` v4 全程进度输出）
**关键条目（md5 与源一致，已从 zip 内 `unzip -p` 复验）**：
- `Main.dex` = `83b3e6d06d8f444e579ab305666da1db`（2,571,408 B，含 OOM + 安全加固 + JSON 修复）
- `webroot/index.html` = `063d57cf47646555ca607bbdaa4ea7cc`（89,585 B，含 OOM 保护名单 UI）
- `webroot/config.json` = `d01d5fc7f95f27ed10a33d1a16c1c255`
**校验**：
- `unzip -t` 零错误；`pack.sh` 内置 SHA256 逐文件比对 **100/100 一致**。
- 发布前内容清查：无 `*.bak/*.tmp/*~/*.orig/__pycache__/.DS_Store` 残留。
**部署**：用户可刷此 zip 或直接用母版 `部署母版到已安装.sh`。
**⚠ 重装后建议**：先仅开 OOM 保护等低风险功能观察一轮，再逐步开启清理/停用类功能
（见 §27 事故教训）。

---
## 30. 回归修复：分片缓存暖扫几乎不命中（2026-10-05，已真机验证，commit 2698f5d）
**现象**：刷入模块后真机回归，`junk_all_apps` 冷扫 72s，但**连续暖扫仍 69~75s**（预期 <1s），
日志 `分片=777/777 复用=71`（命中率 ~9%）。

**定位过程（关键）**：
1. 用 python 直接核对索引 `junk_all_apps.idx`：**777/777 分片 mtime 与磁盘完全一致**（本应全命中）。
2. 加临时诊断日志后发现 **`载入聚合=777` 但复用仅 71**，且绝大多数分片 `idx.shardAgg()` 返回 null。
3. 一度以为 daemon 未加载新 dex——**发现部署陷阱**：`service.sh` 会把 `${MODDIR}/Main.dex`
   （即 `/data/adb/modules/Zhang/Main.dex`）同步到 `/data/adb/Zhang/Main.dex`。
   只更新后者会被重启脚本用模块里的旧 dex 覆盖 → 必须**同时更新两处**。

**真正根因**：`ParallelScanner.scan` 的**增量落盘**分支（每 64 个分片）调用了
`idx.retainShards(aliveShards)`，而 `aliveShards` 当时只装了「已完成」的分片，
于是把磁盘索引里**尚未扫描到的 700+ 分片聚合整片删除**并落盘。下次暖扫时这些分片
无缓存 → 命中率骤降到 ~70/777，暖扫无加速。

**修复（ParallelScanner.kt）**：
- `aliveShards` 语义改为「本次扫描涉及的**全体**分片」，扫描开始即预填（与进度无关）；
- 增量落盘时**不再** `retainShards`（集合不完整），只 `cache.save`；
- 仅在扫描全部结束后的单次 `retainShards(aliveShards)` 里剔除真正消失的分片；
- `shardAggs` 是普通 `HashMap`：`shardAgg`/`putShardAgg`/`mergeInto` 的读写统一持同一把锁，
  消除多线程并发 put 丢数据；
- 新增「扫描开始」日志：`分片= N 载入聚合= N 载入条目= N 线程= N`（便于日后诊断命中率）。

**真机验证（最终 commit 6eb2d5a1 的 dex）**：
- `junk_all_apps`：冷扫 **72s** → 暖扫 **<1s**，`复用=777/777`（100%）。
- `app_cache`：冷 12899 文件/1.1GB → 暖 1s，冷/暖**计数完全一致**。
- `system_junk_data`：冷/暖一致。
- SelfTest：**53 PASS / 1 FAIL（AppOps 环境项，与本改动无关）/ 2 WARN / 7 SKIP**。

**部署脚本陷阱（务必记住）**：更新运行中的 dex 必须写**两处**：
`/data/adb/modules/Zhang/Main.dex` **和** `/data/adb/Zhang/Main.dex`，
否则 `重启Dex.sh`（→`service.sh`）会用模块目录里的旧 dex 覆盖。
**日志配置陷阱**：daemon 实际读 `/data/adb/Zhang/config.conf`（root_dir），
不是 `/data/adb/modules/Zhang/config.conf`；调 `log_enabled` 要改前者。

---
## 31. 重建发布：含暖扫命中率修复的 zip（2026-10-05 10:2x）
**产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,197,503 字节**，100 文件，pack 30s，内置 SHA256 逐文件比对 100/100 一致。
- **SHA256 = `7200199f6f5758f9be1c7199a5cd39243da965d66c76bffd45bc83d28d29971e`**
- `unzip -t` 零错误；zip 内 `Main.dex` = `6eb2d5a15a3a0742c06c1cb278fd6e8a`（含 §30 暖扫修复）。
- 母版 `webroot/index.html` = `063d57cf47646555ca607bbdaa4ea7cc`（不变）。
**取代**：§29 的 zip（SHA256 7188a43f…）已被本版取代，勿再使用旧包。

---
## 32. 回归续测 + 修复 + 发布（2026-10-05 11:2x）
### 本轮回归测试覆盖
- 模块运行态：overview 27 项模块全部正常加载/运行（oom_protect、game_oom、system_tuning、service_guard 等）。
- OOM 保护名单：热加载新包/移除包均正确（-900/-700 ↔ 还原 0）；safety clamp 生效。
- 清理分片缓存：junk_all_apps 冷 72s→暖 <1s（777/777）；app_cache/system_junk_data/uninstalled_leftover 等冷暖计数一致。
- HTTP 安全：越界读写（/proc、/data/system）被拒；/api/paths|ping|overview|oom/*|rubbish/* 正常。
- WebUI：JS 经 node --check 语法通过。

### 本轮修复
1. **fix(appmanager)**: `disableApp` 入口增加 `AppListProvider.installed()` 判断，
   跳过不存在的包，消除每维护周期重复的 `Unknown package` 告警噪声（真实存在的包仍正常停用）。
2. **关键发现：仓库 Main.dex 陈旧会破坏 OTA**——`otaDexUrl` 指向 `repo/Main.dex`，
   而仓库里该文件停留在 10-04（md5 100f4d3b），OTA 会把设备降级回旧版（丢 OOM/暖扫/安全加固）。
   已在本次发布把它更新为当前构建 `105b0252`。**今后每次发版必须同步仓库 Main.dex 与 assets/webroot。**

### 本次发布产物
- `/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,197,579 字节**，100 文件；内置 SHA256 逐文件 **100/100 一致**；`unzip -t` 零错误。
- **SHA256 = `84de99b67caa6216c329dc15c00dcd2bbc9bfb68e3b82116bd5dfc5af9e735f1`**
- `Main.dex` = `105b025251244b328d8926f3a252343c`（2,571,676 B）
- `webroot/index.html` = `063d57cf47646555ca607bbdaa4ea7cc`（不变）

### 关于既有高危路径（未改，因有开关且当前关闭）
- `AntiDetectionModule.removeHmaResidue()`：每 5 分钟递归删除 `/data/system` 下名称含
  hide/hma/applist 的目录。当前无匹配项（无副作用），但属高敏路径，未动。
- `StorageIsolationModule`：/data/media 用户目录搬移（显式 HIGH RISK），受 storage_isolation_enable 门控（当前关闭）。

---
## 33. 高危路径白名单化：HMA 残留清理（2026-10-05，commit cbfb75f）
**背景**：用户要求把高危路径加入白名单，主要清理安卓/ColorOS 目录，避免模糊匹配误删。
**原实现（危险）**：`AntiDetectionModule.removeHmaResidue()` 按「文件名含 hide/hma/applist」
  **模糊匹配** + `deleteRecursive` 删除 `/data/system` 下匹配**目录**。问题：
- 误删风险：`hmac_key`（含 hma）、任何含 applist/hide 字样的厂商目录都会被命中；
- 实际无效：真正的目标 `hidemyandroid_applist.conf` 是**文件**，旧代码只匹配目录 → 根本清不掉。
**新实现（精确白名单）**：
- 白名单仅含确切路径：`/data/system/hidemyandroid_applist.conf`、`/data/adb/modules/hidemyapplist`；
- 删除前 `isWhitelisted()` 双重校验（等于白名单项或其子路径），文件/目录分别处理，单项失败不影响其它；
- 白名单与判定抽为 companion 纯函数，SelfTest 新增「HMA白名单精确性」正/反例断言。
**实测（本机 /data/system）**：
- 真实匹配仅 `hidemyandroid_applist.conf`（文件）；`hmac_key` 是**误报**（佐证必须精确匹配）。
- 反例保护完好：`packages.xml`、`users/0/package-restrictions.xml`、`sensor_service/hmac_key`、
  ColorOS 目录 `shortx_*`/`thanos_*` 均未被触碰。
- SelfTest：**54 PASS**（新增项 PASS，误放反例=[]），HMA conf 被正确清理。

---
## 34. 发布：含 HMA 白名单化的 zip（2026-10-05 11:5x）
**产物**：`/data/media/0/Download/Files/ZhangProtect-Android.zip`
- 大小 **430,198,429 字节**，100 文件；内置 SHA256 逐文件 **100/100 一致**；`unzip -t` 零错误。
- **SHA256 = `7c882823e6d421c5906c08160b5a5b406e8116ae57d82a66f4207e62ae422f82`**
- `Main.dex` = `ef26d8fca55df8e84c9776d0ecaac0d0`（2,573,624 B，含 HMA 白名单化 + 此前暖扫修复 + AppManager 修复）
- 已同步：模块目录（两处）、仓库根 Main.dex、母版、zip。**取代** §32 的 zip（84de99b6…）。
**SelfTest**：54 PASS / 1 FAIL（AppOps 环境项）/ 2 WARN / 7 SKIP（total 64）。
---
## 35. 修复：清理页「扫描」虚高到 34.3GB（listOnly 污染可清理总量）（2026-10-05）
**现象**：WebUI 清理页扫描显示「可清理 28382 文件 · 34.3GB」。
**根因**：`RubbishCleaner.scan()` 汇总时把**所有**规则（含 `listOnly=true` 的仅列出规则）
的 files/bytes 无差别累加进 `Summary.totalFiles/totalBytes`。
其中 `big_files_private`（扫描整个 `/data/user/<u>` 下 >50MB 文件，仅列出不删）
单条就报 **34,955,159,800 B**，占总量 94%——命中的是游戏资源/微信数据库等正常数据。
**修复**：
- `RubbishCleaner.kt`：`RuleResult` 增 `listOnly` 字段；`scan()` 汇总 `if (!rule.listOnly)` 才累加
  （`clean()` 的 listOnly 分支本就 `continue`，无需改）；`summaryToJson` 输出 `listOnly`。
- `app/src/main/assets/webroot/index.html`（= 母版 index.html）：扫描结果里 listOnly 规则显示
  「仅列出 N 文件 · X（不计入可清理）」。
**实时验证**（重启 dex，POST /api/rubbish/scan 同一规则集合）：
- 修复前 TOTAL = 34,924,859,619 B（34.9GB）→ 修复后 TOTAL = **1,821,617,647 B（约 1.82GB）**。
- `big_files_private` 仍返回 275 文件 / 34.9GB，带 `listOnly:true`，不再计入 TOTAL。
**产物**：`Main.dex` md5 **`ae15d5380cc9e5e9cff0655d17add0bf`**（2,574,036 B）；
`webroot/index.html` md5 **`ebfef0a0c10bde7b77b35c0bc5b8ec51`**（89,721 B）。
已部署：模块目录 Main.dex + /data/adb/Zhang/Main.dex + 模块 webroot/index.html；
仓库根 Main.dex、`app/src/main/assets/webroot/index.html`、母版 webroot 均已同步。
**SelfTest**：清理.* 12 PASS / 0 FAIL（列 0 删除守卫、审查 17/17 均过）；
全局 54 PASS / 1 FAIL（既存 AppOps WRITE_SETTINGS，与本改动无关）/ 2 WARN / 7 SKIP。
**教训**：汇总口径必须区分「只读/信息型」与「可回收」两类规则；新增 listOnly 规则自动豁免。
详见 `docs/incidents/2026-10-05_rubbish_scan_total_inflated.md`。
---
## 36. 清理扫描进度 + 正在扫描路径 + 清理预览弹窗（2026-10-05）
**需求**：扫描时显示进度与「正在扫描的位置」；点「清理」前弹预览窗列出将删文件。
**根因**：`/api/rubbish/scan|clean` 原为同步阻塞，WebUI 干等且易超时。
**实现**：
- 新增 `core/rubbish/RubbishProgress.kt`（单例）：阶段/当前规则/当前路径/规则进度/
  分片进度/累计命中/结果 JSON。
- `RubbishCleaner`：scan/clean 每规则开始结束、每分片完成上报进度；
  新增 `preview(ruleIds,maxSamples)` 只读返回将删路径；`previewRuleCount()` 供总数。
- `HttpBackend`：`/api/rubbish/scan|clean` 改为启动**后台任务**立即返回；
  新增 `/api/rubbish/progress`、`/api/rubbish/result`、`/api/rubbish/preview`。
  注意：需 `import ...rubbish.RubbishProgress`（否则 Unresolved reference）。
- 前端 `index.html`：清理页加 `#cleanProg`（规则+路径）与 `#prevModal` 预览窗；
  `doCleanScan/runCleanNow` 走 `pollCleanProgress()`（600ms 轮询）；
  `doCleanRun` 先 preview 弹窗，确认后 `runCleanNow()`。
**验证**：真机轮询可见 8/26 规则、192/434 分片、当前路径、累计命中；
结果 totalBytes=2.55GB（正确排除 listOnly 34GB 虚高）；预览按规则列出具体文件/空目录。
SelfTest 清理.* 12 PASS/0 FAIL；全局 54/1/2/7（同前，唯一 FAIL 为既存 AppOps）。
**产物**：`Main.dex` md5 `d31ddd5cca214ac5288ed6ae09c6e990`；
`index.html` md5 `9cbac2ab722e83c478a8e9e398d00dc0`。已部署模块+运行目录+重启 dex。
详见 `docs/plans/clean_scan_progress_and_preview.md`。

## 37. 竞品逆向分析：CZero(com.web.czero) 捐赠联网验证 + 规则系统（2026-10-05）
**来源**：`/data/media/0/Download/Files/com.web.czero/com.web.czero.apk` (v1.2.9) + `CZero_Mod.zip`。
**完整报告**：`docs/analysis/CZERO_ANALYSIS.md`（已提交 4357b4a）。JADX 输出在 `.../com.web.czero/_jadx`。
**核心结论**：
- **有捐赠模式 + 联网验证**：捐赠解锁进阶功能；走 `https://verify.czeropage.top` 云端换 token，
  订单号 `^\d{10,40}$` / 激活码 `^CZERO-[2-9A-Z]{5}-[2-9A-Z]{5}$` 预校验；设备指纹 `SHA-256(serialno/android_id/UUID)`
  绑定，多设备上限+解绑冷却；token 落 SP(`czero_prefs`) 并双备份到 `/data/adb/czero/license`、`/data/system/czero_license`（明文）。
  云端返回 `license_revoked` 时回收。云规则走 `https://app.czeropage.top/api/app`（Bearer）。
- **规则系统数据驱动**：`/data/adb/modules/CZero/list/` 下 JSON（黑名单 `clean_paths.json` 60+ 分组、
  白名单 `clean_whitelist.json`、空目录/排序/压制各自 JSON）；核心清理为 arm64 **原生 ELF**（customize/zero/Tencent/*/GCclean1/emptyfolder/filesort/suppress），
  App 只做编排(shell)。安装时 `merge_groups` 只增不删。
- **可借鉴**：规则外置 JSON+只增不删合并、黑白双名单、ISO-8601 调度(`PT5M`/`P1D`+`at`)、
  触发门限(电量/温度/息屏/max_runtime)、时间屏障 `temporal_barrier_days`、清理后 MediaScanner 刷新、
  `CZ|size|path` 文本协议、专项引擎隔离、云规则隐私黑名单。
- **我们的优势**：`safeDelete` 唯一审查入口 + `UserGuardRules` + `AuditLog` 比 CZero（无集中审查）更安全，应保留。
**无代码改动**，本项仅产出分析文档。后续如需落地上述借鉴项须另开任务。

## 38. 落地「在线规则（多源直链+定期拉取）+ 规则编辑器（导入/导出 JSON）」（2026-10-05）

**需求（用户）**：既然有在线规则，我们的在线规则怎么制定？要有界面让用户手动输入规则链接直链；
直链定期自动拉取、支持多套在线规则；另有规则编辑器界面可导出 JSON 规则。

**已实现（4 个 commit）**：
- `6d72622` 数据层：
  - `core/rubbish/RuleDoc.kt`：`RuleDoc`(Doc/Group/Result) + `RuleDocCodec`
    （`validatePath`/`parse`/`encode`/`toCleanRule`）。schema `{version,name,author,description,groups[{name,enabled,mode,risk,defaultOn,roots,pattern,ageDays,keep,note,uiGroup}]}`。
    校验红线：绝对路径、禁 `..`、禁根、长度 ≤400、GLOB 必带 pattern。
  - `core/rubbish/OnlineRuleStore.kt`：多源订阅。布局 `{rootDir}/online_rules/index.json`
    + `online_rules/<id>/rules.json` + `{rootDir}/user_rules.json`。`addSource/updateSource/removeSource`、
    `fetchSource/fetchDue`（`HttpURLConnection`，connect 8s/read 12s，响应 ≤2MB，失败保留旧缓存）、
    `allRules()`（在线 id 前缀 `ol_<srcId>_`、用户 `ur_`；switchKey 空=随总开关）、`readUserRules/saveUserRules`。
- `d9490b4` 集成与 API：
  - `RubbishCleaner`：`selectRules()` 改为 `allRulesMerged()`（内建+在线+用户，按 id 与 `mode|roots|pattern` 去重，
    5s 缓存 + `invalidateRuleCache()`）；`rulesToJson()` 输出合并表并加 `mode`/`source`(internal|online|user)；
    新增 `exportGroups()`。
  - `modules/OnlineRuleModule.kt`：`DaemonLoop`，5min 检查一次，`fetchDue()`，异常隔离。
  - `Main.kt`：注册 `online_rules` 模块条目（受 `online_rules_enable` 控制）。
  - `HttpBackend`：新增 11 个端点 `/api/rules/online/{list,add,update,remove,fetch,content}`、
    `/api/rules/{validate,user/get,user/set,user/export,import}`。
    ⚠ 注意 `MiniJson` 只支持**扁平** map，嵌套规则 JSON 由 `extractRuleText()` 原样透传后交 `RuleDocCodec` 解析。
- `d744cd1` WebUI：清理 Tab 新增「在线规则」「规则编辑器」按钮 + 两个面板（源列表/启停/拉取/编辑/删除/新增；
  编辑器 校验/从在线源导入/导入/导出/复制/下载 JSON/保存为用户规则）。
  **真源是 `app/src/main/assets/webroot/index.html`；`webroot/` 只是交付副本且被 .gitignore**（重要坑，见下）。
- `ConfigManager`：登记 `online_rules_enable`（默认 false）+ `ensureParamLines()` 补齐旧配置缺失键。
- `SelfTest`：`onlineRuleChecks()`（schema 往返、校验红线、GLOB、源增删、URL、导出往返、配置键）。

**验证**：`./gradlew compileDebugKotlin --offline` **BUILD SUCCESSFUL**（含 `--rerun-tasks` 全量）；
`node --check` 对 assets 与 webroot 的 script 提取均 **JS_SYNTAX_OK**。
`./gradlew assembleDebug` 在本 proot 环境 **失败于 AAPT2 daemon startup**（环境问题，非代码）。

**坑 / 注意事项（新 Agent 必读）**：
1. **WebUI 双文件**：改 UI 必须改 `app/src/main/assets/webroot/index.html`（打包进 APK assets、被 git 跟踪），
   再 `cp` 到 `webroot/index.html`（交付副本、gitignore）。只改 `webroot/` 不会进入 APK、也不会被提交。
   两者在本轮之前**已存在内容差异**（webroot 105373B vs assets 96652B），本轮采用「以 assets 为基线、逐个锚点移植增量」处理，未互相覆盖。
2. `super_admin:terminal` 会话可能卡死（所有命令 `exitCode:-1` 空输出）：用 `background:"true"` 起新会话，
   再用 `super_admin:terminal_wait` + `terminal_getscreen`（注意名字不是 `terminal_terminal_getscreen`）。
3. 管道 `cmd | tail` 后的 `$?` 是 tail 的退出码，需 `cmd > log 2>&1; echo $?` 才准。

**未做（可选后续）**：OTA 发布 zip 重打包（`pack.sh`/`构建WebUI.bat`）、真机端到端验证（加源→拉取→扫描命中）、
在线规则签名校验（当前仅 JSON schema + 路径红线，未做发布者签名）。

---

## §39 端到端验证与删除缺陷修复（2026-10-05）

**触发**：用户要求「验证功能是否全部完整，包括 WebUI 界面；确保清理功能确实按规则走、有内置规则」。

### 构建 / 部署链路（本 proot 环境唯一可行路径）
- `assembleDebug/Release` 因 **AAPT2 无法启动**（缺 `/lib64/ld-linux-x86-64.so.2`）失败，仅影响资源打包，不影响 dex。
- 用 `d8` 直接从 Kotlin class 产物生成 dex：
  ```
  D8=/opt/android-sdk/build-tools/36.0.0/d8
  AJ=/opt/android-sdk/platforms/android-37.1/android.jar
  KS=<kotlin-stdlib-2.2.21.jar>   # 必须作为【输入】而非 --lib，否则 stdlib 不打包
  CLS=app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes
  mkdir -p /tmp/dex   # ⚠ d8 要求 --output 目录【预先存在】
  $D8 --min-api 34 --output /tmp/dex --lib $AJ $KS $(find $CLS -name '*.class')
  ```
  产物约 3,074,0xx 字节（含 stdlib 与全部新类）。
- 部署：`cp` dex 到 `/data/adb/modules/Zhang/Main.dex` 与 `/data/adb/Zhang/Main.dex`，
  再 `cd /data/adb/modules/Zhang && sh 停止Dex.sh && sh service.sh`；端口固定 **26437**。
- **UI 部署坑**：WebUI 从 `/data/adb/modules/Zhang/webroot/index.html` 加载（HttpBackend 的 `File(mod,"webroot/index.html")`），
  另有 `system/webroot/index.html` 副本；构建 dex **不会**同步 UI，必须手动把
  `app/src/main/assets/webroot/index.html`（真源，112278B）拷到这两处。本轮发现部署副本曾是旧版（96652B，不含在线规则 UI）并已修正。

### 发现并修复的代码缺陷（commit `2659450`）
- **现象**：`POST /api/rubbish/clean {"rules":"empty_dirs"}` 返回 `files=0`，目标空目录残留，审计日志
  无任何 DELETE/REJECT 记录（静默失败）。
- **根因**：`RubbishGuard.deleteTree()` 只递归删除目录**内容**，**从不删除传入的目录自身**；
  对空目录（`listFiles()` 返回空数组而非 null）直接跳过，导致 `cleanEmpty`/`cleanDeep` 的空目录删除全部失效。
- **修复**：`deleteTree(dir, ruleId, deleteSelf=false)` 新增 `deleteSelf`；`safeDelete()` 对目录传 `true`
  实现彻底删除；递归子目录由内部统一删自身；删除失败时新增 **FAIL 审计 + rejected**（消除静默）。
  `safeCleanDirContents` 仍传默认 `false`，保持「保留容器」语义不变。

### 端到端验证结果（真机 dex 部署后实测）
- **内置规则 31 条**（`source=internal`，switchKey 无空）；在线 1 + 用户 1，合并表 **33 条**。
- `preview` 多规则不再只返回首条（历史缺陷已消解）。
- 在线规则端到端：`add`→`fetch`→合并→`preview` 命中真实文件（`<u>` 占位符展开正确）。
- 校验红线 4 例（相对路径 / `..` / GLOB 缺 pattern / 合法）全部正确。
- `user/set`、`user/export`、`import`（user 与 online 两种 target）均可用。
- **删除实测**：造 `ZSD_T2_SUB/inner/zero.txt` → clean 后 audit 依序
  `DELETE zero.txt(file) → inner(dir) → ZSD_T2_SUB(dir)`，目录彻底消失；空目录 `Operit/skills` 被真实删除。
- **WebUI 验证**：UI 引用 38 个 `/api/*` 端点，与 HttpBackend 路由表**零缺失**；
  `node --check` 通过；HTML 150 个 id、标签全部闭合；`api()` 默认端口 26437 与后端一致。
  12 项 UI 走调用链端到端测试 **12/12 通过**。

### 已知环境限制（非代码缺陷）
- `/data/media/0`（主用户 emulated/FUSE）下 root **无法删除其它应用 uid 属主的目录**，
  `rmdir`/`rm -rf` 均报 `EPERM`（即使用 `/mnt/pass_through` 直连路径也一样）。
  而 `/data/media/999`（多用户直连）可正常删除（已验证）。
  修复后此类失败会记录 **FAIL 审计 + rejected**，不再静默；真实删除能力以 18:40 自动清理
  `files=2780 bytes=675MB rejected=6` 为证。
- 回滚备份：`/data/adb/Zhang/_backup_deletefix_*`（dex）、`/data/adb/Zhang/_backup_ui_*`（index.html）。

### 复现命令速查
```
API=http://127.0.0.1:26437
curl -s $API/api/ping
curl -s $API/api/rubbish/rules | python3 -m json.tool | head
curl -s -X POST $API/api/rubbish/preview -d '{"rules":"empty_dirs","max":5}' -H 'Content-Type: application/json'
curl -s -X POST $API/api/rubbish/clean   -d '{"rules":"empty_dirs"}' -H 'Content-Type: application/json'
tail -20 /data/adb/Zhang/log/rubbish_clean.log
```

---

## §40 WebUI 规则源卡片移动端布局修复 + 面板上移（2026-10-05，commit `5ac66c6`）

**需求**：①在线规则/编辑器面板移到上方（原来在 #cleanGroups 之后，需长距离滚动）；
②修复移动端规则源卡片严重布局异常（正文被操作控件挤压到 ~1 汉字宽、逐字竖排；按钮横向溢出被裁切）。

**根因**：
- `.cell .val{flex:0 0 auto}` 且内部不换行，5 个控件（开关 51px + 拉取/编辑/导入到编辑器/删除）
  总宽超过窄屏；`.cell` 无 `flex-wrap`，`.val` 又不收缩 → `.body` 被压缩到 ~15px（1 字）。

**修复（响应式，非缩字体）**：
- `.cell` 加 `flex-wrap:wrap`；`.body` → `flex:1 1 200px;min-width:0` + `overflow-wrap:anywhere`。
- `.val` → `flex:0 1 auto;flex-wrap:wrap;max-width:100%;margin-left:auto`。
- 新增 `.cell.actrow`（规则源专用）：窄屏 `.val{flex-basis:100%;width:100%}` 独占第二行并换行；
  `@media(min-width:560px)` 恢复右侧同行。按钮包 `.btns` 容器统一换行。
- `.cell .d.url`：宽屏单行省略号；`@media(max-width:560px)` 改可换行。
- `#cleanGroups` 移到 `onlinePanel`/`editorPanel` 之后。

**真机验证（SukiSU Ultra 的 WebUIX Activity，包名 com.sukisu.ultra，屏幕 1264px 物理宽）**：
- 打开路径：`monkey -p com.sukisu.ultra`（或点模块卡片）→ 底栏「🧹清理」→「在线规则」按钮。
- UI 层次实测（android view hierarchy 是权威渲染数据）：名称/URL/状态行宽度**均 791px**
  （原约 15px）；状态行「状态：成功 · 规则 2 条 · 间隔 24h · 上次 …」横向完整；
  拉取/编辑/导入到编辑器 同行，删除自动换行到下一行；卡片右边界 1155 在容器 1211 内，无横向溢出。
- OCR 截图复核一致。

**验证方法（可复用）**：本机无标准浏览器/jsdom/chromium，无法用 headless 验证；
可靠路径是 SukiSU WebUI（真机 WebView）+ `Automatic_ui_base` 的 `get_page_info`（读渲染后 UI 层次）
+ `daily_life:take_screenshot`（OCR 复核）。WebUI 加载的是
`/data/adb/modules/Zhang/webroot/index.html`（**改 assets 真源后必须手动同步到此处**，见 §39）。

**部署核对**：源与部署 md5 一致 `a748f93b7fcb5104d1842554f9be70f6`，114196 字节，无临时探针残留。

---

## §41 最终验收：部署 → 全功能测试 → pack → git push（2026-10-05）

**执行链**：部署最新 dex → 端到端功能测试 → pack.sh 打包 → git push（13 commits）。

### 关键坑：shell 环境被 LD_LIBRARY_PATH 污染（务必牢记）
测试中途 `super_admin:shell` 突然**所有命令无输出/报 `CANNOT LINK EXECUTABLE ... libcrypto.so`**，
一度误判 daemon 已死。**真因**：之前用 `export LD_LIBRARY_PATH=/data/Python/lib:...` 测试 Android
侧 python，污染了 shell 会话的全局环境，导致 `/system/bin/ls` `curl` `nc` 等**全部动态链接失败**。
**解法**：每条 shell 命令开头加 `unset LD_LIBRARY_PATH LD_PRELOAD; export PATH=/system/bin:/system/xbin;`。
用绝对路径 `/system/bin/ls` 也不能绕过（LD_LIBRARY_PATH 仍生效），必须 unset。

### 环境可达性结论（用于起本地规则源）
- Ubuntu(proot) 与 Android **网络命名空间独立**：Android 侧连不上 proot 的 `127.0.0.1:<port>`。
- Android 侧可用 HTTP 服务器：`/data/Python/bin/python3.13`（需 `PYTHONHOME=/data/Python LD_LIBRARY_PATH=/data/Python/lib`）、
  `/system/bin/python3`（交互受限）。**最可靠**：`toybox nc` 循环响应（脚本见下）。
  `busybox httpd` **不可用**（exit 127，KSU busybox 未编译 httpd）。
- 一次性 HTTP 源（nc 版）：
  ```sh
  # /data/local/tmp/ncserv.sh
  B=$(cat /data/local/tmp/htdocs/rules.json); L=$(printf '%s' "$B" | wc -c)
  while true; do { printf 'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' "$L" "$B"; } | nc -l -p 18932 -q1; done
  ```
  后台常驻：`(/system/bin/sh /data/local/tmp/ncserv.sh >/dev/null 2>&1 &)`

### 全功能测试结果（全部通过）
- 基础接口：ping/overview/paths/rubbish-status/ota-status/history/guard-read ✅
- 规则表：31 内置（全有 switchKey，模式覆盖 DIR_CONTENT/GLOB/JUNK_SCAN/OLDER_THAN/APK_SCAN/DIR_SELF/
  EMPTY_DIR/UNINSTALLED_SCAN/BIG_FILE_SCAN/DUP_CONTENT/DUP_SAME_SIZE）✅
- 校验红线：合法/拒相对路径/拒 `..`/GLOB 缺 pattern ✅
- preview：多规则全返回、单规则正常 ✅
- **真实清理**：造 `ZSD_FINAL_SUB/{inner/zero.txt, emptydir}` → clean 后 audit 依序
  `DELETE emptydir(dir) → zero.txt(file) → inner(dir) → ZSD_FINAL_SUB(dir)`，目录彻底消失 ✅
- 用户规则：set/get/export/合并/import ✅
- **在线规则端到端**：本地 nc 源 add → `fetched:true ruleCount:1` → list `SUCCESS sha256=00bdd395...`
  → 合并进规则表（`ol_src_f5d5831d_0`）→ preview 命中 3 个真实文件（`<u>` 展开正确）→ content 可取内容 ✅
- WebUI（SukiSU WebUIX，1264px 物理宽）：规则源卡片名称/URL/状态行宽 **791px**、按钮自动换行、无溢出；
  面板已上移（onlinePanel 在 cleanGroups 之前）；在线/用户规则可见并已合并 ✅

### 打包（pack.sh v4）
- 在**模块目录**运行：`cd /data/adb/modules/Zhang && sh pack.sh`
- 产物：`/data/adb/modules/ZhangProtect-Android.zip`，**430,386,994 字节（410.4 MB）**，压缩率 76.9%
- SHA256 逐文件校验 108 文件全部一致，退出码 0。

### Git push
- 仓库 `Main.dex` 原为旧版（2591388B / d31ddd5c）→ 更新为部署版（3074184B / 91556594）后提交。
- `git push origin main`：`b5e3b79..6aa4998`，**13 个提交**，退出码 0。
- 远端 `refs/heads/main = 6aa4998f335df7220d36fa86c6c3c3b1d86b895`，本地与远端一致，工作区干净。
