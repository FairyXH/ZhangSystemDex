# 事件：启用新 dex 后系统反复软重启（root cause 定位）

- 时间：2026-10-06 15:48 ~ 16:10
- 现象：部署含「内置守护开关 + OOM 可选 + 内存看门狗」的新 `Main.dex` 并重启 daemon 后，系统
  **反复软重启**。用户描述症状：**状态栏无法下拉、全局冻结、前台尚可操作，随后卡死并重启**。

## 证据链

1. daemon 于 `15:48:49` 启动，日志停在 `[ConfigManager] 已加载开关（94 项）` 后**再无输出**。
2. `15:51` 出现大量 `system_app_anr`；`15:52` 出现
   `SYSTEM_SERVER_WATCHDOG@1791273175861.txt.gz` + `SYSTEM_RESTART@...` →
   **Watchdog 杀死 system_server → 软重启**（另有两份 watchdog 报告，说明反复发生）。
3. 症状「状态栏下拉失效 + 全局冻结」是 **system_server 主线程/Binder 被阻塞**的典型表现。

## 根因（已确认）

新代码 `BuiltinConfig.isForcedOom(pkg)` → `AccessibilityGrant/NotificationGrant.probeComponents(pkg)`
会执行 **`cmd package dump <pkg>`（可超时 20s）**。

而 `BuiltinConfig.oomPackages()` 会对 **`builtin_apps.conf` 中的全部 45 个内置应用**逐个探测：

```
effectivePackages() → BuiltinConfig.oomPackages() → 45 × isForcedOom() → 45 × cmd package dump
```

`cmd package` 是**重操作，最终在 system_server 内执行**；45 次串行调用 **严重阻塞 system_server**
→ 状态栏无响应 / 全局冻结 → **Watchdog 60s 超时杀死 system_server → 软重启**。
这解释了「15:48 部署新 dex → 15:52 重启」的时间相关性（旧 dex 无此探测，不触发）。

## 加剧因素

1. **adj 过高**：保护值用 `-900`（主）/`-700`（子），把应用钉太死。实测 `com.omarea.vtools`
   （Scene）被钉在 `-900` 且 RSS≈589MB（全机最高），内存只增不减。
2. **误伤系统进程**：实测重启前后 `com.huawei.hwid.core`（内置应用 `com.huawei.hwid` 的子进程）
   被设为 `-700`；曾一次误把 `system_server` 的 adj 改为 0（已恢复）。
3. **内存看门狗太晚**：阈值 95%，此时系统已进入疯狂回收/卡死，触发也来不及。

## 修复（本次实施）

- **A. 组件探测异步 + 持久化缓存**：启动/首轮不从 daemon 线程同步跑 45×`cmd package dump`；
  探测结果落盘缓存（`builtin_forced.conf`），重活放到后台线程，且**串行限速**。
- **B. adj 降到 -500/-450**，并加下限钳制；用户明确要求「-500 左右，不要被频繁杀死即可」。
- **C. 系统进程白名单**：`pidsOf`/`applyAdj` 拒绝任何 `system_server`、`init`、`zygote*`、
  `surfaceflinger`、`servicemanager` 等系统核心进程，**坚决不动**。
- **D. 看门狗提前**：阈值下调（≈90%），并主动收缩保护区。

## 教训

- **绝不在 daemon 主循环/启动路径同步执行 `cmd package` / `dumpsys` 等重 IPC**。
- 任何「per-package 探测」都要先估算调用次数（本例 45 次）并考虑其对 system_server 的冲击。
- 修改系统级 `oom_score_adj` 前必须排除系统核心进程。
