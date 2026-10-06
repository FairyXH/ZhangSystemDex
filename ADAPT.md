# ADAPT.md —— 面向 Agent 的项目工作手册

> 本文档是给**接手本项目的 AI Agent / 开发者**读的。目标：读完这一份，就能理解
> ZhangProtect（ZhangSystemDex）**如何工作、结构是什么、代码在哪、如何构建、如何部署、
> 如何在新系统上适配**，以及**历史踩过的坑**，不必重新逆向整个项目。
>
> 配套文档：
> - `README.md`（母版内）：面向用户的功能说明书（含全部开关与模块职责）。
> - 仓库 `docs/AGENT_CONTEXT.md`：逐次任务的压缩上下文（最新状态以它为准）。
> - 仓库 `docs/BUILD_NOTES.md`：构建环境与 aapt2 坑。
> - 仓库 `docs/plans/`、`docs/incidents/`：设计与事故复盘。
>
> 维护约定：**改代码在源码仓，改产物在母版，两者必须同步**（见 §6）。

---

## 0. 一句话概要

ZhangProtect 是一个 **Magisk / KernelSU Root 系统管理模块**。它的长期运行逻辑已从 Shell
迁移为 **Kotlin Dex Daemon**：单个 `Main.dex` 经 `app_process` 以 **root（UID 0）** 启动，
无 Activity / Application 生命周期，按 `switches.conf` 动态加载功能线程（关闭的功能不建线程），
每 60s 热加载开关。它同时提供 **KernelSU WebUI**（`webroot/index.html`）与 **本地 HTTP API**
（默认 `http://127.0.0.1:26437`）用于可视化配置与状态查询。

---

## 1. 术语与三条“轨”

| 名称 | 路径 | 角色 |
|---|---|---|
| **母版 (master)** | `/data/media/0/Download/Files/ZhangProtect-Android/` | **发布真源**。打包输入。含 `pack.sh`、`webroot/`、`system/app/`、全部发布脚本。 |
| **源码仓** | Ubuntu 环境 `/home/projects/ZhangSystemDex/`（= `/sdcard/...` 映射之外的工程） | **代码真源**。Kotlin 工程 + `docs/` + `docs/AGENT_CONTEXT.md`。 |
| **已安装模块** | `/data/adb/modules/Zhang/` | **运行期模块（KernelSU 实际挂载）**。开机由 KernelSU 执行其 `service.sh`。 |
| **运行数据根** | `/data/adb/Zhang/` | 运行期配置与日志（`switches.conf`、`Main.dex` 副本、`log/`、`cache/`）。 |

> ⚠️ 关键约束（来自用户长期要求）：
> 1. **所有修改必须在母版进行**（母版是唯一发布真源），**禁止从已安装模块打包**。
> 2. 打包必须用**母版自带的 `pack.sh`**。
> 3. 改完必须**免重启更新**到已安装模块（= 母版整体覆盖 + 重启 dex），无需重启设备。
> 4. 母版内的脚本/配置**不写注释**（保持发布产物干净）；源码仓内的代码正常写注释。

---

## 2. Git 仓库

- **远程**：`https://github.com/FairyXH/ZhangSystemDex.git`（分支 `main`）
- **本地**：`/home/projects/ZhangSystemDex`
- 推送：`cd /home/projects/ZhangSystemDex && git push origin main`
- 凭据：环境内 `gh` CLI 已登录账号 `FairyXH`（OAuth 设备流，token 权限 `gist, read:org, repo`）。
- **发版要求（务必遵守）**：OTA 源是 **仓库根的 `Main.dex`**，因此每次发版必须
  把新构建的 `Main.dex` 同步到**仓库根**，并把 `app/src/main/assets/webroot/index.html`
  一并提交；否则在线更新会把设备**降级**回旧版本。

---

## 3. 运行时架构

```
┌──────────────────────────────── 模块目录 (母版 / 已安装) ────────────────────────────────┐
│ Main.dex（唯一核心，Kotlin Dex）                                                         │
│ config.conf（仅两键：root_dir、log_enabled）                                             │
│ service.sh（正式启动器）  post-fs-data.sh  install.sh  uninstall.sh                      │
│ 启动Dex.sh / 停止Dex.sh / 重启Dex.sh / 调试运行Dex.sh                                      │
│ pack.sh + tools/{zippack.py,zipcheck.py,python3}  Python.zip（内置 Python 运行时）        │
│ webroot/{index.html,config.json}（KernelSU WebUI）                                       │
│ system/ (overlay 内置系统应用)   ZhangSetting/    sqlite_lib/   aapt  shfmt              │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                                   │  service.sh 首次启动时同步 Main.dex
                                   ▼
┌──────────────────────────── 运行数据根 /data/adb/Zhang/ ─────────────────────────────────┐
│ Main.dex（启动器同步的副本）                                                              │
│ switches.conf（全部功能开关 + 中文注释）                                                  │
│ doze.conf / game_pause.conf / asguard.conf / notification.conf / autorun.conf ...        │
│ oom_protect.conf / builtin_guard.conf / builtin_forced.conf / notif_keepalive.conf ...    │
│ incidents.log（事故哨兵采集）                                                             │
│ cache/（xposed_modules.json、sqlite_lib/…）   log/（zhang.log 1MB 滚动、daemon.log）      │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**启动命令（service.sh 核心）**：

```sh
nohup /system/bin/app_process \
  -Djava.class.path="/data/adb/Zhang/Main.dex" \
  /system/bin --nice-name="zhangsystemdex" \
  io.github.fairyxh.zhangsystemdex.Main \
  "${MODDIR}" \
  >"/data/adb/Zhang/log/daemon.log" 2>&1 &
echo $! >"/data/adb/Zhang/daemon.pid"
```

> ⚠️ **`MODDIR` 由 `$(dirname "$0")` 决定**：开机走 `/data/adb/modules/Zhang/service.sh`（正确）；
> 而**免重启更新**是在**母版目录**执行 `重启Dex.sh`，因此运行期 `ctx.modDir` 会变成**母版路径**。
> 代码里凡涉及“模块自身资源”的地方都要用 `BuiltinApps.effectiveRoot/effectiveModuleDir`
> 之类逻辑做**真源纠正**（详见 §5.4）。

**入口类**：`io.github.fairyxh.zhangsystemdex.Main`
**主要模式**：`selftest`（全量自测）、`notification <action>`、`accessibility <action>`（CLI）。

---

## 4. 源码工程结构（`/home/projects/ZhangSystemDex`）

```
app/src/main/java/io/github/fairyxh/zhangsystemdex/
├── Main.kt              # 入口：解析参数、初始化、按开关加载模块线程、60s 热加载
├── DebugMenu.kt         # 前台调试数字菜单（单次执行任意功能）
├── SelfTest.kt          # 全量自测（PASS/FAIL/WARN/SKIP），adb 一键回归
├── core/                # 基础设施
│   ├── DexContext, ConfigManager, Logger, RootUtils, HiddenApiBypass
│   ├── FrameworkOps（API 优先、shell 降级的操作封装）, ShellExecutor（全局节流）
│   ├── PropUtils, SettingsUtils, ProcessUtils, FileUtils, SqliteUtils
│   ├── ServiceManagerUtils, BinderUtils, SystemContext
│   ├── AppListProvider, GameListProvider, PackageManagerProvider
│   ├── DaemonLoop（守护线程基类）, GamePauseCoordinator
│   ├── BuiltinApps / BuiltinConfig（内置应用清单与逐应用 guard/oom）
│   ├── KeepAliveList（通知/无障碍保活名单）, OomProtectList（OOM 名单）
│   ├── IncidentWatchModule 相关采集、ShizukuResidue（防检测白名单）
│   └── HttpBackend.kt   # 本地 HTTP 服务 + 全部 /api/* 端点（WebUI 后端）
├── modules/             # 功能模块（各模块一个 DaemonLoop 子类）
└── ...
app/src/main/assets/webroot/index.html   # WebUI 唯一权威源（构建进 dex 资产）
webroot/index.html                        # ⚠️ 旧版副本，已废弃（.gitignore 忽略）
tools/{zippack.py,zipcheck.py,python3}    # 打包工具链（随模块）
docs/                                     # Agent 文档（AGENT_CONTEXT / BUILD_NOTES / plans / incidents）
```

**WebUI 权威源**：`app/src/main/assets/webroot/index.html`。仓库根 `webroot/` 是**已废弃副本**，
`.gitignore` 已忽略，不要用它。

---

## 5. 核心机制（适配新系统必须理解）

### 5.1 开关体系与热加载
- `switches.conf`（`/data/adb/Zhang/switches.conf`）是**唯一开关来源**，格式为 `key=value # 中文注释`。
- 解析时 `substringBefore('#')` 剥离行内注释；缺失键**回退默认**（多数 `false`，若干特殊项 `true`）。
- `ensureParamLines()`：**只追加完全缺失的键，绝不覆盖已有值**（新增配置项必须走它做 migration）。
- `Main` 主循环**每 60s** 检查 `switches.conf` 的 lastModified，变更则热加载（启停线程），无需重启。
- `powersave_enable` 是总闸：开启后多数调优类功能失效；但**防护类**（如 `skip_mount_guard`）绕过总闸。

### 5.2 线程模型
- 每个功能 = 一个 `DaemonLoop` 子类线程，**独立异常捕获**（单模块崩溃不拖垮 daemon）。
- `GamePauseCoordinator`：游戏在前台时可挂起“可暂停模块”。
- 关闭的功能**完全不创建线程、不执行任何逻辑**。

### 5.3 Framework-first / shell 降级原则
- 能用 Android API 的一律优先 API（包管理、AppOps、Doze 白名单、WiFi/蓝牙、renice/chmod 等）。
- API 不可用（hidden-API 过滤 / 权限 / OEM 限制）时**降级等效 shell**，失败警告**按操作去重**（只记一次）。
- **`ShellExecutor` 有全局节流**（并发 ≤2，启动间隔 ≥120ms；内存吃紧时 600ms）——历史事故教训，勿移除。

### 5.4 内置应用清单（`BuiltinApps`）——**易踩坑**
- 模块内置应用（随模块挂载为系统应用，如 Shizuku/GKD/通知滤盒/Operit 等）**默认保活且不听从开关**。
- 包名来源：`<模块>/system/app/<包名>/<包名>.apk`（**父目录名即包名**，不解析 APK）。
- **运行期真源必须是已安装模块** `/data/adb/modules/Zhang/system/app`，**不能是母版**。
  因为免重启更新时 `ctx.modDir` 会指向母版。
  - `BuiltinApps.effectiveRoot(rootDir, modDir)` → 实际生效的 `<模块>/system/app`（展示用）
  - `BuiltinApps.effectiveModuleDir(rootDir, modDir)` → **模块根**（`packages()` 期望的层级）
  > 踩坑：`packages(modDir)` 内部会拼 `system/app`，所以必须传**模块根**；传错会变成
  > `<模块>/system/system/app`，枚举结果变 0。
- `BuiltinConfig`：`builtin_guard.conf`（逐应用 `guard,oom`）；`isForcedOom` 为**只读缓存 +
  后台异步探测**（`builtin_forced.conf` 落盘），**绝不在主路径同步跑 `cmd package dump`**（见 §8 事故）。

### 5.5 OOM 保护（`OomProtectModule`）——**易踩坑**
- 周期 **5s**；用户要求保护值 **≈ -500**（`SAFE_FLOOR=-500`，主 `-500`、子 `-450`），**绝不触碰系统核心进程**。
- `applyAdj` **必须实地读回** `/proc/<pid>/oom_score_adj` 再决定是否写：
  - AMS（ColorOS 的 `OomAdjusterSocExtImpl`）会周期性**覆盖**我们写入的 adj（改回 cached=900）；
  - 若只信本地缓存 `lastAdj`，一旦被覆盖就**永远不再写回**，保护永久失效（Shizuku 就是这么“自己停止”的）。
- `isProtectedSystemProcess(pid)`：comm/cmdline 白名单 + `/system|/vendor|/odm|/system_ext|apex`
  路径判定 + `comm=main 且 cmdline 含 zygote` → **这些进程绝不改 adj**。
- 内存看门狗：物理内存已用 ≥ **90%** 且“受保护进程 RSS 合计 ≥ 总内存 40% / 单个 ≥ 25%”时介入
  （取消保护 + force-stop），冷却 60s。

### 5.6 WebUI 与本地 API
- `HttpBackend` 监听 `127.0.0.1:26437`，提供 `webroot/index.html` 与全部 `/api/*`。
- 常用端点：`/api/ping`、`/api/paths`、`/api/overview`、`/api/oom/status`、
  `/api/builtin/status|apps|guard/get|guard/set`、`/api/keepalive/*`、`/api/shizuku/status|residue`、
  `/api/guard/alerts`（**事故哨兵**，零 shell 读取 watchdog/crash/重启/内存低点记录）。
- **诊断优先走 API，不要反复跑 su/重命令**（见 §8 事故）。

### 5.7 事故哨兵（`IncidentWatchModule` + `/api/guard/alerts`）
- 每 15s **纯文件读取**（`/proc/uptime`、`/data/system/dropbox/`、`/data/anr/`、`/proc/meminfo`）
  记录 `reboot/watchdog/pre_watchdog/restart/native_crash/crash/anr/mem_low` 到 `incidents.log`。
- 完全零 shell，避免“边救火边浇油”。事故后**首选**用它取证。

---

## 6. 构建与部署流程

### 6.1 构建（Ubuntu 环境）
```sh
cd /home/projects/ZhangSystemDex
bash /opt/build.sh        # 等价于 gradle :app:assembleRelease（见该脚本）
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
# 提取发布 dex：
python3 -c "import zipfile,hashlib;z=zipfile.ZipFile('app/build/outputs/apk/release/app-release-unsigned.apk');z.extract('classes.dex','/tmp/dx');b=open('/tmp/dx/classes.dex','rb').read();open('Main.dex','wb').write(b);print(hashlib.md5(b).hexdigest())"
```
- 构建环境：JDK 21（`/opt/jdk-21.0.12.1+1`）、Gradle 9.3.1（`/opt/gradle-9.3.1`）、
  Android SDK（`/opt/android-sdk`）、AAPT2 桥接包装器（`/opt/aapt2-qemu/aapt2`）。
- **本机 aapt2 坑**：aarch64 宿主 + x86_64 proot，`aapt2` 需经 qemu 运行，详见 `docs/BUILD_NOTES.md`。
- 发布 dex 自检：必须含 `HttpBackend`、`SkipMountGuardModule` 标记。

### 6.2 免重启更新（母版 → 已安装，无需重启设备）
标准动作（本机 Android shell 下）：
```sh
unset LD_LIBRARY_PATH LD_PRELOAD; export PATH=/system/bin:/system/xbin
# 1) 新 dex 同步到三副本
for d in /data/media/0/Download/Files/ZhangProtect-Android /data/adb/modules/Zhang /data/adb/Zhang; do
  cp -f /sdcard/Download/_new.dex "$d/Main.dex"
done
# 2) 重启 daemon（母版自带脚本；内部=停止 dex + service.sh 启动）
cd /data/media/0/Download/Files/ZhangProtect-Android && sh 重启Dex.sh
# 3) 验证
curl -s http://127.0.0.1:26437/api/ping
curl -s http://127.0.0.1:26437/api/overview   # dexMd5 应与新构建一致
```
- 更完整的“母版整体覆盖 + 同步 dex + 重启 dex”见母版 `部署母版到已安装.sh`。

### 6.3 打包（必须用母版 pack.sh）
```sh
cd /data/media/0/Download/Files/ZhangProtect-Android && sh pack.sh
# 产物：/data/media/0/Download/Files/ZhangProtect-Android.zip（含 SHA256 逐文件校验、最多 3 次重试）
```
- `pack.sh` 依赖**模块内置 Python**（`tools/python3` + `Python.zip`），**不需要系统 `zip`**。
- 打包前会做前置检查（`webroot/index.html`、`webroot/config.json`、含 `HttpBackend` 的 `Main.dex`）。

### 6.4 发布清单（每次发版照做）
1. 构建并提取新 `Main.dex`；
2. 同步到**母版** + **已安装模块** + **运行数据根**，再重启 dex 验证；
3. 把新 `Main.dex` 同步到**仓库根**并提交（否则 OTA 降级，见 §2）；
4. 更新 `README.md` / `ADAPT.md` / `docs/AGENT_CONTEXT.md`；
5. `git push origin main`；
6. **检查母版是否最新**（`Main.dex` md5 三处一致），然后 `sh pack.sh`。

---

## 7. 环境适配（换新设备 / 新系统时）

### 7.1 前置条件
- **Root**：Magisk 或 KernelSU（本项目按 KernelSU 安装，`id=Zhang`）。必须有 `su`/root 才能运行 daemon。
- **Android 版本**：当前适配 **Android 15 / Kernel 6.1.x / ColorOS（OnePlus PKG110）**。
- 内置应用清单里的 app（Shizuku、GKD 等）是否安装**不影响 daemon 启动**，只是对应保活失效。

### 7.2 路径与命名（硬编码项，换机需核对）
| 常量 | 值 | 位置 |
|---|---|---|
| 模块 id | `Zhang` | `module.prop` / 目录名 |
| 模块目录 | `/data/adb/modules/Zhang` | `Main.kt`、`service.sh`、`BuiltinApps` |
| 配置根 | `/data/adb/Zhang` | `config.conf` 的 `root_dir` |
| HTTP 端口 | `26437` | `HttpBackend` |
| 母版/发布目录 | `/data/media/0/Download/Files/ZhangProtect-Android` | 打包与发布 |
| Python 释放目录 | `/data/Python` | `service.sh` + `Python.zip` |

### 7.3 适配要点（按顺序检查）
1. **`app_process` 可用性**：新系统需能用 `/system/bin/app_process` + `-Djava.class.path=<dex>` 运行。
2. **hidden-API**：`SystemContext.get()` 走 `Looper.prepareMainLooper()+ActivityThread.systemMain()`；
   若新 ROI 拿不到 Context，功能会整体降级到 shell 路径（慢但可用）。
3. **Framework SQLite 不可用**：Android 15 `app_process` 下 framework `SQLiteDatabase` 无法访问
   settings provider，代码自动走内置 `sqlite_lib/` 的 sqlite3 CLI（**每次启动自动修复执行权限**）。
4. **SELinux**：daemon 域为 `u:r:su:s0`；读 `system_file` 类路径等若被拒，`File.listFiles()`
   会返回 null（这正是历史“内置应用枚举为 0”的根因之一）。必要时用 shell 兜底。
5. **AMS/OomAdjuster 覆盖 adj**：ColorOS 会周期性覆盖 `oom_score_adj`；`applyAdj` 必须实地读回（见 §5.5）。
6. **`cmd package dump` / `dumpsys` 重命令**：**绝不在 daemon tick/启动路径同步执行**（见 §8）。
7. **shell 环境**：Android shell 命令前需 `unset LD_LIBRARY_PATH LD_PRELOAD; export PATH=/system/bin:/system/xbin`。
8. **proot/Ubuntu 与设备隔离**：Ubuntu 侧**看不到 `/data/adb`**，需用 `/sdcard` 交换文件、由 Android shell 部署。
9. **`rm -rf` 被安全拦截**：改用唯一目录名 + `rmdir`，或精确 `rm -f`。
10. **长命令 ≤60s**：用后台执行 + 轮询结果。

### 7.4 新系统“从零适配”最短路径
```
1) 安装模块（母版 zip）→ 开机确认 /data/adb/Zhang 生成、daemon 起来了
2) curl /api/ping、/api/overview  → 后端 OK？
3) app_process ... Main <moddir> selftest  → 看 PASS/FAIL，逐项修路径/权限
4) 检查 switches.conf 是否生成、默认值是否符合预期（含 ensureParamLines 迁移）
5) 检查内置应用枚举：/api/builtin/apps 的 dir 是否 = /data/adb/modules/Zhang/system/app
6) 检查 WebUI：KernelSU 打开 /webroot/index.html，或直接 curl /api/*
7) 观察 incidents.log / /api/guard/alerts，确认无 watchdog/软重启
```

---

## 8. 历史事故与教训（务必遵守）

> 完整复盘见仓库 `docs/incidents/` 与 `docs/AGENT_CONTEXT.md` §51–§55。

1. **软重启根因（2026-10-06）**：新代码 `BuiltinConfig.isForcedOom()` 曾对 45 个内置应用**同步**
   执行 `cmd package dump`（每次可超时 20s），多条并发进入 system_server 后**全卡在同一把锁**
   （`AppBatteryTracker.updateBatteryUsageStatsIfNecessary`）→ Binder 线程池占满 →
   `watchdog.monitor` 卡 `blockUntilThreadAvailable` 15s → **Watchdog 杀 system_server → 软重启**。
   - 修复：组件探测改**异步 + 落盘缓存**；daemon 启动/ tick 路径**禁止同步重 IPC**。
   - 建议：诊断优先 `/api/guard/alerts`，**别再反复 dumpsys/logcat**。
2. **`pidsOf` 误伤 shell 子进程**：曾用 `cmdline.contains(pattern)`，把 `cmd package dump com.box.app`
   等 shell 子进程也设了 adj；已改为**仅匹配 arg0**。
3. **绝不能动的进程**：`system_server`/`init`/`zygote*` 等系统核心进程的 `oom_score_adj`
   一律不改（历史曾误改 system_server 为 0，已恢复为 -900）。
4. **`applyAdj` 必须实地读回**（见 §5.5），否则 AMS 覆盖后保护永久失效（Shizuku 案例）。
5. **内置应用清单真源**：运行期必须用**已安装模块**，不能用母版（见 §5.4）。
6. **发版必须同步仓库根 `Main.dex`**，否则 OTA 降级（见 §2）。
7. **`skip_mount`**：模块目录若残留 `skip_mount`，Magisk 会跳过本模块 `system/` 挂载；
   `SkipMountGuardModule` 每 10s 清理（默认开）。

---

## 9. 常用命令速查

```sh
# —— Ubuntu（源码仓）——
cd /home/projects/ZhangSystemDex && bash /opt/build.sh        # 构建
cd /home/projects/ZhangSystemDex && git status -sb && git push origin main

# —— Android shell（设备）——
unset LD_LIBRARY_PATH LD_PRELOAD; export PATH=/system/bin:/system/xbin
curl -s http://127.0.0.1:26437/api/ping
curl -s http://127.0.0.1:26437/api/overview
curl -s http://127.0.0.1:26437/api/builtin/apps
curl -s 'http://127.0.0.1:26437/api/guard/alerts?limit=50'
cd /data/media/0/Download/Files/ZhangProtect-Android && sh 重启Dex.sh   # 免重启重启 dex
cd /data/media/0/Download/Files/ZhangProtect-Android && sh pack.sh      # 打包
# 全量自测：
cd /data/adb/modules/Zhang && /system/bin/app_process -Djava.class.path=/data/adb/Zhang/Main.dex \
  /system/bin --nice-name=zst io.github.fairyxh.zhangsystemdex.Main /data/adb/modules/Zhang selftest
```

---

## 10. 给下一个 Agent 的建议

- **先读**：`docs/AGENT_CONTEXT.md`（最新状态，含 §51–§55 事故与修复）→ 本文件 → `README.md`。
- **改代码**：在源码仓改，构建，同步三副本，重启 dex，跑 `selftest` 与相关 API 验证。
- **改产物/脚本**：在母版改（不写注释），然后免重启更新 + pack。
- **发版**：走 §6.4 清单，一步别漏。
- **遇到系统级异常（卡死/重启）**：先 `/api/guard/alerts` 取证，**不要**大剂量 su/dumpsys。
- **保持文档同步**：每次重要改动后在 `docs/AGENT_CONTEXT.md` 追加一段压缩记录。

---

*本文件随项目演进更新；如与 `README.md` 冲突，以“运行期行为 + 源码”为准。*
