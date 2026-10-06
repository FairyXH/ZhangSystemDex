# 计划：模块内置应用默认保活（不听配置）

日期：2026-10-06

## 需求（用户原文要点）

1. 模块自身携带的内置 app（`system/app/` 内所有应用，即模块 `system/app/<pkg>/<pkg>.apk`）：
   - **默认加入 Doze 白名单（电池优化白名单）**；
   - **默认加入多任务 Lock（MIUI `locked_apps` / ColorOS `app_lock_data_file_name`）**；
   - 这两项**不受任何开关控制**（不听从配置）。
2. **无障碍保活**、**通知使用权保活**、**OOM 保活** 也默认对内置 app 生效，
   同样**不听从配置**（不受 `a11y_keepalive_enable` / `notif_keepalive_enable` /
   `oom_protect_enable` / `powersave_enable` 影响）。
3. WebUI 在对应卡片旁显示**说明**并**列出名单**。

## 实现设计

### A. 内置应用清单来源（核心）
新增 `core/BuiltinApps.kt`：
- `root(ctx)` = `modDir + "/system/app"`（DexContext.modDir 即 Magisk 模块目录）。
- `packages()`：枚举 `<root>/*/*.apk`（大小写兼容 `Apk`），取**父目录名**作为包名，
  用 `OomProtectList.isValidPackage` 校验；带 mtime 缓存（避免 5s/10s 循环反复扫盘）。
- `packagesCached()` / `invalidate()`。
- 兜底：目录不存在时返回空列表（不影响任何逻辑）。

> 说明：目录名 == 包名（模块打包规范），且比解析 APK manifest 更稳（不依赖 aapt/framework）。

### B. Doze 白名单（不听配置）
`modules/PowerManagerModule.kt`：
- `applyDozeList()` 的 `white` 集合 **无条件** union `BuiltinApps.packages()`；
  清理逻辑（移除不在 white 中的 user 白名单）自动保留内置应用。
- `onStart()` 中 `doze_enable=false` 分支：原来只加 `requiredDozePackages`，
  现在也加内置应用。

### C. 多任务 Lock（不听配置）
`PowerManagerModule.applyLockedApps()`：`packages` **无条件** union 内置应用。
- 同时 `onStart()` 中 `locked_apps_enable=false` 时也要执行（因为默认要生效）。

### D. 无障碍 / 通知使用权保活（不听配置）
`core/KeepAliveList.kt`：
- 新增 `builtinPackages(rootDir)`：读取 `BuiltinApps`（由 modDir 推导 rootDir 的父级路径）。
- `read(rootDir, kind)` 返回值 **union 内置应用**（并保证文件不存在时也含默认+内置）。
- 语义：内置应用 **始终在名单里且不受总开关控制**。

`modules/KeepAliveModules.kt`：
- `NotificationKeepAliveModule.tick()` / `AccessibilityKeepAliveModule.tick()`
  直接用 `KeepAliveList.read(...)`（已含内置）→ 无需再加开关判断（原本就没有）。
- 确保 tick 不因 `notif_keepalive_enable=false` 提前 return（当前实现无此判断，仅 `pkgs.isEmpty()`）。

`Main.kt`：
- `notif_keepalive` / `a11y_keepalive` 模块的 `enabled()` 改为 **常开**
  （只要存在内置应用或有名单就运行）。简化：直接 `{ true }`（与 `accessibility_guard` 一致）。

### E. OOM 保活（不听配置）
`core/OomProtectList.kt`：
- `effectivePackages(rootDir)` union `BuiltinApps`。

`modules/OomProtectModule.kt`：
- `tick()` 中 `oom_protect_enable=false` 分支：**不再** return，
  而是把 `packages` 限定为「内置应用」子集（内置始终保护）。

`Main.kt`：
- `oom_protect` 模块 `enabled()` 改为 `{ true }`（内置应用始终受保护）。

### F. HTTP 后端
`core/HttpBackend.kt` 新增端点：
- `/api/builtin/apps` → `{ok, count, dir, packages:[{pkg,label,installed,apk}]}`
- `/api/keepalive/get` 增加字段：`builtin:[pkg...]`、`enforced:[pkg...]`（实际强制生效集合）。
- `/api/oom/status` 增加字段：`builtin`、`builtinProtected`（当前存活且被保护的内置包）。

### G. WebUI（`app/src/main/assets/webroot/index.html`）
1. 设置页「通知使用权保活名单」卡片 + 「无障碍服务保活名单」卡片 + 「OOM 保护名单」卡片：
   新增一块 **说明 + 内置应用名单** 渲染区（`#notifKaBuiltin` / `#a11yKaBuiltin` / `#oomBuiltin`）。
2. 顶部加说明：内置应用默认保活、不受开关控制。
3. `loadKeepAlive()` / `loadOom()` 里渲染内置名单（来自 `/api/builtin/apps` 与 `r.builtin`）。
4. 卡片标题旁加 `ⓘ` 提示文字。

### H. 配置项说明
`ConfigManager` 的 `SWITCH_DESCRIPTIONS` 文案补充「内置应用不受此开关控制（始终生效）」。

### I. 自测
`SelfTest.kt` 新增 `builtinChecks(s, ctx)`：
- 内置应用清单可读（数量 > 0）；
- 内置应用全部在 Doze 生效集合中（`buildWhiteList` 语义）；
- 内置应用全部在 `KeepAliveList.read` 结果中（两种 kind）；
- 内置应用全部在 `OomProtectList.effectivePackages` 结果中。

## 验证
- `node --check` 提取 script。
- `bash /opt/build.sh` 构建 → 取 dex（注意单条命令 ≤60s）。
- SelfTest 真机运行。
- 真机 API 验证 + WebUI 渲染（SukiSU WebUIX）。

## 风险
- 内置应用会 **强制** 加入 Doze 白名单与多任务 Lock：会略微增加耗电，但符合用户明确要求。
- 45 个内置应用全部写 MIUI `locked_apps` 与 ColorOS 文件，需注意系统 JSON 体积（可接受）。
