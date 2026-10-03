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
- 本地 git 仓（Linux 侧）：`/tmp/zsd_remote`（若重建，从此文与 README 恢复）。
- 本地构建工程（非 git 仓，含工具链）：`/home/projects/ZhangSystemDex`。
- 打包源目录（设备）：`/data/media/0/Download/Files/ZhangProtect-Android/`（= `/sdcard/Download/Files/ZhangProtect-Android/`）。
- 安装后对应：`/data/adb/modules/Zhang/`；运行目录：`/data/adb/Zhang/`。

## 3. 关键产物与正确校验值（务必使用带 HttpBackend 的新版）

| 产物 | 位置 | md5 | 说明 |
| --- | --- | --- | --- |
| `Main.dex`（新）| 模块目录/打包目录/仓库 | `8224b53e578ddda00bb08df900c28829` | 含 `HttpBackend`(7处)、`26437`(1处)，2358752B |
| `Main.dex`（旧，禁用）| 备份名 `Main.dex.bak.1791001188` | `7460aaa69e082d3c8084854bbf269655` | 0处 HttpBackend，2306316B |
| `webroot/index.html`（新版 iOS 重构）| 打包目录/模块目录/仓库 | `e91f05a8fb2f280d1fefa4e422869f44` | 41302B，四 Tab iOS 底栏 UI，KernelSU WebUI 入口 |
| `webroot/index.html`（旧版，已废弃）| 备份 `/tmp/zsd_new/index.old.bak.html` | `a8cb7f03f947151a9247b25aea16d032` | 30475B，单页滚动分组列表 |
| `webroot/config.json` | 打包目录/模块目录 | `d01d5fc7f95f27ed10a33d1a16c1c255` | 449B，WebUI X 宿主清单 |

> 切勿用旧值：`Main.dex` 若为 `7460aaa6...`、或 webroot 用 `4643...` / `a8cb7f03...` 系列即旧包。

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
