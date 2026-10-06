# 计划：内置守护可独立开关 + OOM 可选/强制 + 内存看门狗 + 软重启修复

- 制定：2026-10-06
- 状态：**核心实现已完成；软重启根因已定位并修复；待最终重建/部署验证**

## 一、需求（用户 2026-10-06）

1. 模块内置守护（Doze/Lock/通知/无障碍保活）改为**可按应用独立开关**（默认开）。
2. OOM 保护改为**可选（复选框）**：
   - 含**无障碍服务**或**通知监听**组件的内置应用 → **强制** OOM 保护（不可关）；
   - 其余内置应用 → 可选（默认不勾）。
3. 新增**内存看门狗**：OOM 保护期间若物理内存 ≥ 阈值，判断是否模块所致，若是则取消保护并终止高占用对象。
4. 覆盖到已安装模块 + **复原 oom** + **免重启更新**。
5. （追加）OOM 保护值**不要太高，≈-500**；**坚决不动 system_server 等系统级进程**。

## 二、实现要点

### BuiltinConfig（`builtin_guard.conf`）
- 一行 `pkg=guard,oom`；`guard` 默认 1、`oom` 默认 0。
- `isForcedOom(pkg)`：探测是否声明 AccessibilityService / NotificationListenerService。
  - **2026-10-06 重写**：改为**只读缓存 + 后台异步探测**（`builtin_forced.conf` 落盘）。
    旧实现同步 `cmd package dump` × 45 → 阻塞 system_server → Watchdog 软重启（已修复）。

### 接入点
- `KeepAliveList.builtinPackages` → `BuiltinConfig.guardPackages`。
- `OomProtectList.effectivePackages` → 用户名单 ∪ **用户保活**（**剔除内置**）∪ `BuiltinConfig.oomPackages`。
  - 关键修复：内置应用 OOM 只由 `builtin_guard.conf` 决定，**不被守卫开关拖入**。
- `OomProtectModule`：总开关关时仅保护 `builtinPackages`（= 强制∪勾选）。

### 内存看门狗（OomProtectModule）
- 常量：`WATCHDOG_MEM_USED_TRIGGER=90`（由 95 提前）、`TOTAL_SHARE=40`、`SINGLE_SHARE=25`、`COOLDOWN=60s`。
- `tick()` 末尾 `checkMemoryWatchdog()`：受保护进程 RSS 合计 ≥40% 或单进程 ≥25% → 取消保护 + force-stop 高占用对象。

### 安全（用户强要求）
- `SAFE_FLOOR = -500`，`DEFAULT_MAIN_ADJ=-500`、`DEFAULT_CHILD_ADJ=-450`。
- `isProtectedSystemProcess(pid)`：comm/cmdline 白名单 + `/system|/vendor|/odm|/system_ext|apex` 路径 +
  `comm=main 且 cmdline 含 zygote` → **绝不改动其 oom_score_adj**。
- `applyAdj` / `restoreAll` / `onStop` / stale 清理 / 看门狗终止 全部先过该系统进程判定。
- `GamePauseModule` / `GameOomProtectModule` 的游戏 OOM 由 -1000 改为 -500/-450，并过滤系统进程。

### HTTP / WebUI
- `GET /api/builtin/guard/get`、`POST /api/builtin/guard/set`。
- `/api/builtin/apps` 增加 `guard/oomChecked/forcedOom/oomEffective`。
- `/api/builtin/status`、`/api/oom/status` 增加守护/OOM/看门狗字段。
- WebUI「设置」页：守护开关 + OOM 复选框（强制项 disabled 标注「OOM·强制」）。

## 三、验证

- `bash /opt/build.sh` → BUILD SUCCESSFUL。
- 自测（`Main <moddir> selftest`）覆盖：OOM 安全钳制、系统进程保护、内置 OOM 生效集合。
- 设备实测：daemon 启动不再阻塞；`system_server` adj 保持 -900；受保护用户应用 adj = -500/-450。

## 四、已知遗留

- `PowerManagerModule.applyDozeList()` 在 `onStart()` 会对 45 个内置应用逐个
  `dumpsys deviceidle whitelist +pkg`（系统 IPC）。**仅启动一次**，但仍是启动期系统压力；后续可考虑批量/异步化。
- `模块.AppOps.write/read` 自测 FAIL 为 ColorOS（`com.oplus.securitypermission`）已知问题，非本轮引入。