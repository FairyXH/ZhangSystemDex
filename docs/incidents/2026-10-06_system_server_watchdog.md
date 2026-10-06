# 事故：system_server 被 Watchdog 杀死 → 界面崩溃/软重启（2026-10-06）

## 一、现象

- 用户描述：**「毫无征兆」系统卡死**；状态栏**无法下拉**、界面全局冻结，随后软重启（有时只是 SystemUI 崩）。
- 多次出现：约 15:52、17:29、17:40（每次伴随 `SYSTEM_RESTART`）。

## 二、取证（系统原始数据）

| 时间 | 证据文件 | 关键内容 |
|---|---|---|
| 15:50 | `dropbox/system_server_pre_watchdog@1791273042142.txt.gz` | `CriticalEventLog: com.android.systemui StatusBar is not responding, Waited 5000ms for MotionEvent` |
| 15:52 | `dropbox/SYSTEM_SERVER_WATCHDOG@1791273175861.txt.gz` + `SYSTEM_RESTART` | Watchdog 杀 system_server |
| 17:27 | `dropbox/system_server_pre_watchdog@1791278846608.txt.gz` | CPU: `43% system_server / 38% com.kugou.android.lite / 28% zhangsystemdex / 5.2% kswapd0` |
| 17:39 | `anr/traces_SystemServer_WDT06_10_17_40_08.971_pid2091` | 多条 Binder 线程卡在同一把锁 |
| 17:40 | `dropbox/**system_app_crash@*.lost`（多个 0 字节） | SystemUI 崩溃 |
| 17:39 | `anr/anr_21655_*`（微信，VmSwap **164MB**）、`anr_7911_*`（GMS，VmSwap 50MB） | 内存压力导致 ANR |

## 三、根因（确定）

### 统一症状

```
Subject: Blocked in monitor com.android.server.Watchdog$BinderThreadMonitor
         on monitor thread (watchdog.monitor) for 15s

"watchdog.monitor" ... state=R
  native: #00 libc.so (writev+12)
  native: #01 liblog.so (LogdWrite) ... 
  native: #10 libbinder.so (IPCThreadState::blockUntilThreadAvailable)
  at android.os.Binder.blockUntilThreadAvailable
  at com.android.server.Watchdog$BinderThreadMonitor.monitor(Watchdog.java:490)
```

### 关键栈（`traces_SystemServer_WDT...pid2091`，17:39）

多条 `binder:2091_x` 全部卡在**同一把锁** `<0x04663ef2>`：

```
PackageManagerShellCommand.runDump
 → ActivityManager.dumpPackageStateStatic
 → Binder.doDump → ActivityManagerService.dumpAppRestrictionController
 → AppRestrictionController.dump → AppBatteryTracker.dump
 → AppBatteryTracker.updateBatteryUsageStatsIfNecessary   ← Object.wait() 同一把锁
```

即：**多条 `cmd package dump` 并发进入 system_server，全卡在 `AppBatteryTracker` 的同一把锁上**，
把 **Binder 线程池占满** → `watchdog.monitor` 拿不到 Binder 线程 → 15s → **Watchdog 杀 system_server**。

### 触发源（三个叠加）

1. **AI Agent 自身的高频重命令**：本会话中反复执行
   `for p in /proc/[0-9]* …`（每次遍历 ~1000 进程）、`dumpsys *`、`cmd package dump`、
   `logcat -d` 等。17:44 的 logcat 中可见 `DeepseekProvider` 记录着 Agent 的
   `tool_calls`（`package_proxy`/`super_admin`）请求体。
2. **Operit AI 把每条 AI 请求/命令回显全量写入 logcat**：
   tag `AIService`/`ToolPkg`/`RootShellExecutor`/`SHELL_IN`/`SHELL_OUT`/`DeepseekProvider`，
   **占近 4000 行日志的 51%**（2048 行）。→ `logd` 繁忙，`writev` 阻塞（logd 常态 4.8% CPU）。
3. **设备内存长期吃紧**：`SwapTotal 7.5GB / SwapFree 仅 1.2GB`（已用 6.3GB）；
   微信 VmSwap 164MB、GMS 50MB；`kswapd0` 持续回收。

**结论**：这不是单一模块的 bug，而是「**内存压力下的换页风暴 + logd/Binder 拥塞 + 突发 dump 负载**」
共同导致 system_server 的 Binder 线程池耗尽，触发 Watchdog 误杀。

## 四、为什么与 ZhangSystemDex 有关但非其缺陷

- 17:27 时 `zhangsystemdex` 占 28% CPU、97558 minor fault，是**负载源之一**（每 5s 全量扫 /proc）。
- 早期版本（已修）`BuiltinConfig.isForcedOom` 曾同步跑 45×`cmd package dump`——**这正是 17:39 栈中的
  `PackageManagerShellCommand.runDump` 特征**；该路径在刷入 `bd254a9a` 后已改为**异步后台探测**。
- 本次 17:39 的 `cmd package dump` 洪流主要来自 **Agent 命令 + Operit 日志**，非模块。

## 五、已实施的防护（ZhangSystemDex 侧）

1. **`isForcedOom` 异步化**（`builtin_forced.conf` 落盘 + 后台串行探测）——消除模块自身的 45×dump。
2. **`PowerManagerModule` 重操作延后 20s 后台执行**——避免与开机高峰争 Binder。
3. **Doze 白名单只写差异项 + 每 8 项让出 60ms**——把 45 次 Binder 写降到「仅变化项」。
4. **`ShellExecutor` 全局节流**（本轮新增）：
   - 同一时刻 ≤2 个 shell 进程；
   - 新调用最小间隔 120ms，**内存吃紧（MemAvailable<900MB）时放大到 600ms**；
   - → 即使各模块同时触发，也不会把 system_server 的 Binder 池打满。
5. **`pidsOf` 严格化**（仅匹配 arg0）——不再误伤 `cmd package dump` 等 shell 子进程。

## 六、仍需用户/上游处理

- **Operit 的全量 logcat 回显**（`RootShellExecutor`/`AIService`/`DeepseekProvider`）是**关键放大器**，
  建议关闭其命令行/请求体日志，或降低日志级别。
- **AI Agent 应避免高频重命令**（大范围 `/proc` 遍历、循环 `dumpsys`、`cmd package dump`）。
- **设备内存**：swap 长期高占用，建议限制 GMS/微信等后台。

## 七、验证

- 新 dex `a2db1ae17a0fe9143ef30de424c80e5a`（2,761,944B）已部署至三副本并重启。
- `/api/builtin/status`：45 内置 / 8 强制 OOM 正常；`/api/ping` OK。
- 误伤清理后 2 轮 tick 内无复发（`pidsOf` 严格化生效）。