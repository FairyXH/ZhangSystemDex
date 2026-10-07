# 更新日志（CHANGELOG）

本项目为 KernelSU/Magisk 模块 **ZhangProtect（ZhangSystemDex）**。
版本号沿用 `module.prop` 的 `versionCode`；下列按**日期倒序**记录。

## [2026-10-07]

### 修复
- **Shizuku 保活失效（二次根治）**：`ShizukuModule` 旧 `classify()` 以 **uid 形态**区分
  主进程/服务端（`uid/100000 >= 900 → 服务端`）。本机 Shizuku 已转为**系统应用**
  （`packages.list` 中 `partition=system`），其主应用会同时以 `10335` 与 `99910335`
  两个 uid 出现，`99910335` 被误判为服务端 → `/api/shizuku/status` 恒报
  `serverPids` 非空、`healthy:true` → 保活逻辑「看到服务端在」直接返回 →
  **真正的 `shizuku_server` 从未被拉起** → Shizuku 客户端报 “is not running”。
  现改为**按进程名分类**（`classifyNamed`）：`arg0 == shizuku_server` 才算服务端；
  包名（可带 `:proc` 后缀）算主应用。新增 `ProcessUtils.procName(pid)`。
- **Shizuku 保活失效（一次根治）**：`ProcessUtils.pidsOf` 曾用 `File.readBytes()` 读
  `/proc/<pid>/cmdline`，而 procfs 文件 `st_size` 恒为 0 → 只读到 0 字节 →
  任何进程都匹配不到。改为流式读取 `readProcText()`（`FileInputStream` 循环 read）。
  此前表现为保活每 30s 误判「服务端不在」并重启（`restartCount` 涨到 137）。
- **Shizuku 官方启动命令**：改用官方 root 启动方式
  `<nativeLibraryDir>/libshizuku.so --apk=<sourceDir>`（换机通用、稳定），
  失败才回退旧 `/data/local/tmp/shizuku_starter`。

### 变更
- `SelfTest`：14d/14e/14h 改为**按进程名**断言；新增 **14i**（procfs 流式读取回归）、
  **14j**（系统应用 uid 防误判回归：`99910335` 主应用不得判为服务端）。

### 备注
- 发版再次强调：改源码后**必须同步仓库根 `Main.dex`**，否则 OTA 拉取旧 dex 造成降级。
- 当前产物：`Main.dex` = `b9b5e895712f237d670bf23f40f34fd2`（2781384 B）。

## [2026-10-06]

### 修复
- **软重启根因**：内置应用组件探测曾对 45 个应用**同步**执行 `cmd package dump`，
  并发进入 `system_server` 后卡在同一把锁（`AppBatteryTracker.updateBatteryUsageStatsIfNecessary`），
  导致 Binder 线程池耗尽、`watchdog.monitor` 超时 → Watchdog 杀掉 system_server → **软重启**。
  现改为**后台异步 + 落盘缓存**（`builtin_forced.conf`），启动/tick 路径**不再同步跑重 IPC**。
  （关联：`SYSTEM_SERVER_WATCHDOG@…`、`system_server_pre_watchdog@…`）
- **系统界面卡死**：同上根因的 Binder 线程池拥塞；新增 `ShellExecutor` 全局节流
  （并发 ≤2、间隔 ≥120ms，内存吃紧 600ms），并严格化 `ProcessUtils.pidsOf`（仅匹配 arg0，
  不再误伤 `cmd package dump` 等 shell 子进程）。
- **Shizuku 频繁 “is not running”**：`OomProtectModule.applyAdj` 只信任本地缓存，
  被 ColorOS 的 `OomAdjusterSocExtImpl` 覆盖 `oom_score_adj`（改回 cached=900）后**永久不再写回**，
  保护失效 → 被回收。现改为**实地读回 `/proc/<pid>/oom_score_adj`**，被覆盖后 ≤5s 自动纠回。
- **内置应用名单来源显示为母版**：免重启更新由母版目录启动 daemon，`ctx.modDir` 指向母版，
  `/api/builtin/*` 误用该路径。现统一使用 `BuiltinApps.effectiveRoot/effectiveModuleDir`，
  **运行期真源固定为已安装模块** `/data/adb/modules/Zhang/system/app`。
- **SelfTest 误报 FAIL**：`模块.AppOps.write/read` 随机取到 Android 15 “系统强控 op”
  （如 `MANAGE_ONGOING_CALLS`、`MANAGE_EXTERNAL_STORAGE`），写入返回成功但系统不放行，
  被误判失败。现区分「写入调用失败=FAIL」与「写入成功但系统门控=SKIP」，自测恢复
  **74 PASS / 0 FAIL**。
- **Shizuku 每 30s 无意义重启**：服务端在即视为可用（`serverUsable`），不再反复重启
  （此前 `restartCount` 一度达 40）。

### 新增
- **事故哨兵 `IncidentWatchModule` + `/api/guard/alerts`**：每 15s **纯文件读取**
  （`/proc/uptime`、dropbox、ANR、meminfo）记录 watchdog / 崩溃 / 软重启 / 内存低点，
  **零 shell**，避免诊断时加重系统负担。
- **内置应用守护增强**：逐应用「守护」开关 + OOM 可选/强制（含无障碍或通知组件者强制），
  新配置文件 `builtin_guard.conf`、`builtin_forced.conf`；WebUI 新增对应面板与说明。
- **内存看门狗**：物理内存已用 ≥90% 且受保护进程占用异常时取消保护并 force-stop（冷却 60s）。

### 加固 / 安全
- OOM 保护值统一为 **≈ -500**（`SAFE_FLOOR=-500`，主 -500 / 子 -450），
  **绝不触碰 `system_server`/`init`/`zygote*` 等系统核心进程**（`isProtectedSystemProcess`）。
- `PowerManagerModule` 启动期重操作（Doze 白名单等）**延后 20s 后台执行**，且只写差异项。

## [2026-10-05]

- 修复 OTA 陈旧降级风险与分片缓存暖扫命中率：增量落盘不再误删未扫描分片，
  `junk_all_apps` 暖扫由 ~70/777 提升到 777/777（暖扫 <1s）。
- `AppManagerModule.disableApp` 增加 installed 判断，消除重复的 Unknown package 告警。
- 明确**发版要求**：OTA 源为仓库根 `Main.dex`，发版必须同步仓库根 `Main.dex`
  与 `app/src/main/assets/webroot/index.html`，否则在线更新会降级设备。

## [2026-10-03]

- **打包链自包含 + 内置 Python 运行时**：`pack.sh` 改为纯 Python 实现
  （`tools/zippack.py` + `tools/zipcheck.py`），不依赖系统 `zip`，保留 Unix 权限位，
  含 SHA256 逐文件自检；整合 `Python_for_Android-3.13.5`（`Python.zip` → `/data/Python`），
  `service.sh` 首次开机释放。可在 MT 管理器直接运行 `pack.sh` 出包。
- **垃圾清理安全加固**：修复 7 处缺陷（2 处严重），引入 `UNINSTALLED_SCAN` 模式、
  拆分 `DIR_JUNK_STRONG`、`app_cache` 改 `filesOnly` 等；真机全类型清理 `rejected=0`。

## [2026-08-07]

- 新增 `SkipMountGuardModule`（默认开）清理模块目录 `skip_mount` 残留，防止挂载被跳过。
- 修复 `hma_config_enable` 默认失效（补描述 + 旧配置自动追加）。
- 新增 `heavy_screen_off_only` 开关；`FileUtils.copyDirRecursive` 整目录 cp -rf 语义。
- 修复 `copyMount()` 误删模块 `system/` 内置应用（符号链接处理）。
- 日志全量中文化；heavy 首次启动立即执行；LSPosed 扫描口径修正；新增 `SelfTest` 与 `FrameworkOps`。

## [2026-08-06]

- **Shell → Dex 全量迁移完成**：开关体系 + 热加载、LSPosed 数据库扫描、调试菜单、
  heavy 任务独立开关、12 个特殊开关默认开启。
