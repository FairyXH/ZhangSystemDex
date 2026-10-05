# 事故复盘：2026-10-05 重启后锁屏卡死（package.xml 疑似损坏）

## 现象
用户打开大部分清理功能后重启设备，**锁屏输入密码后一直卡住无法进入**。
进入 TWRP 删除模块目录 + 删除 `/data/system/package.xml` 后重启恢复正常。

> 注：删除前请先备份；`package.xml` 删除后系统会依据已安装 APK 重建包状态
> （会丢失部分组件启用状态/权限授予历史），属于「重包数据库」的应急手段。

## 影响面
- 模块运行路径 `/data/adb/modules/Zhang` 被整体删除 → daemon 不再运行。
- 母版 `/data/media/0/Download/Files/ZhangProtect-Android/` 完好（源码还在）。

## 根因分析（基于源码走查）

`symptom = package.xml 损坏/不一致 → PackageManagerService 解析失败 → 开机卡在锁屏`。
只有「会改写包状态」的操作可能导致该症状。走查代码后锁定以下高危点：

### 高危 1：`AppManagerModule.disableApp()` 周期性 `pm uninstall`（最可疑）
`core`/`modules/AppManagerModule.kt`：
```kotlin
private fun disableApp(pkg: String) {
    ShellExecutor.run("pm uninstall $pkg")            // ← 全用户卸载，改写 packages.xml
    for (u in users) setApplicationDisabledUser(pkg, u) // ← 再改写
    setApplicationEnabled(pkg, false)                  // ← 再改写
}
```
- 由 `SystemTuningModule.heavyTick()` 周期调用（`disable_apps_enable` 开时）。
- **同一包在每个维护周期被反复 uninstall+disable**，`package.xml` 被高频重写；
  若 reboot 恰逢写入中途 → 文件损坏 → 开机卡死。
- `pm uninstall`（无 `--user`）是**全用户卸载**，语义过重，且对系统应用会造成
  PackageManager 内部状态不一致。

### 高危 2：无条件 `pm uninstall --user 0 com.oplus.appdetail`
`SystemTuningModule` 常规周期**无条件**执行（line 149），与开关无关，
每次维护都改写一次包数据库。

### 高危 3：`chattr +i` 不可变位
对 `/data/data/cn.gov.pbc.dcep/envc.push` 设置 `+i`，若目标路径异常会影响写入安全网。

### 与本次 OOM 改动无关
本次新增的 `OomProtectModule` / `OomProtectList` / `/api/oom/*` **不触碰任何包状态**，
只写 `/proc/<pid>/oom_score_adj`，与 package.xml 无关。

## 已采取的加固措施

1. **`disableApp` 幂等化 + 去 `pm uninstall`**：
   - 移除全用户 `pm uninstall`，改为仅 `pm disable-user --user <u>`（可逆、幂等）；
   - 禁用前先检查当前启用状态，**已禁用则跳过**，杜绝重复改写包数据库。
2. **`com.oplus.appdetail` 卸载改为「存在才执行且仅一次/会话」**，并受开关门控。
3. **包状态操作串行化 + 失败即停**：避免一轮内对多包批量改写时中断损毁。
4. **高危操作前快照 `package.xml`**（备份到 `/data/adb/Zhang/backup/`），
   便于万一损坏时人工恢复。
5. `chattr +i` 增加「先确保父存在再操作 + 失败静默」的健壮性。

## 长期建议
- 包状态改写类操作（uninstall/disable）**永远不要在周期任务里裸跑**：
  改为「仅状态变更时执行一次」+ 全局幂等去重。
- `package.xml` 在每次包状态改写前做一次滚动备份（保留最近 1 份）。
- 开机阶段（post-fs-data/service）**绝不**做包卸载/禁用。

## 恢复操作参考（应急）
1. TWRP 挂载 data；
2. `rm -rf /data/adb/modules/Zhang`（禁用模块自启）；
3. 备份后 `rm /data/system/package.xml*`；
4. 重启，系统重建包数据库；
5. 确认可分模块重开功能，逐个排查。