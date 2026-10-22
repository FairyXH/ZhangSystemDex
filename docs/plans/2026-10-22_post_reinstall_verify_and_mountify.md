# 母版重装后验证报告 + mountify 不挂载原因

- 日期：2026-10-22
- 设备：OnePlus PKG110 / Android 15 / ColorOS V15.0.0
- 新 dex：`1d60160698b02dcfc941d3ce1ced25c6`（2,820,800 字节）

## 结论速览

| 项 | 结果 |
|---|---|
| 模块重装后 daemon | 正常，adj=-1000，线程 37~39 |
| dex 一致性 | 模块 / Download / 运行时自报 三处 md5 完全一致 |
| 25 个模块 | 20 启用且 tickCount 全 > 0，5 个按配置关闭 |
| Download 基础目录 | Files / Important / Music 均存在且 777 |
| mountify 是否挂载本模块 | **否，且不会挂载 —— 架构性限制，非配置错误** |

## 一、main.dex 一致性

| 位置 | md5 |
|---|---|
| `/data/adb/modules/Zhang/Main.dex` | `1d60160698b02dcfc941d3ce1ced25c6` |
| `/sdcard/Download/Main.dex` | 同上 |
| 运行时 `/api/overview` 自报 | 同上（dexSize 2820800） |

> 母版目录 `/data/media/0/Download/ZhangProtect-Android/` 已由用户清理，
> 不存在属预期，不作为失败项。

## 二、mountify 为什么不挂载 Zhang

mountify `v2.0.5`，`mountify_mounts=2`（auto 模式，本应挂载所有带 system 目录的模块）。

### 事实

本机 5 个模块带 `system` 目录：

`ViPER4Android-RE-Fork`、`aml`、`iOSThemeFontsForColorOS`、
`iOSThemeStatusIconForColorOS`、`Zhang`。

mountify 的 `description` 自报已挂载：前 4 个，**无 Zhang**。

`mount_diff` 实际挂载点全部是 etc/fonts/lib 类目录，无一条涉及 `/system/app`：

```
+overlay /system/etc     lowerdir=/mnt/vendor/mountify/etc:/system/etc
+overlay /system/fonts   lowerdir=/mnt/vendor/mountify/fonts:/system/fonts
+overlay /vendor/etc     lowerdir=/mnt/vendor/mountify/vendor/etc:/vendor/etc
... （共 10 条，均无 system/app）
```

验证：`mount | grep -c ' /system/app '` 得到 **0**；
`/system/app/com.github.tianma8023.xposed.smscode` 为 **MISSING**；
模块内 50 个 APK 在系统侧全部不可见。

### 根因

mountify 对每个模块只做 **overlay 叠加**，且叠加目标必须是**系统上已存在的目录**。

- 其它 4 个模块的内容落在 `/system/etc`、`/vendor/lib`、`/system/fonts` 等
  真实存在且可叠加的目录，因此正常。
- Zhang 需要落地的是 `/system/app`。overlay 语义下这意味着要把整个
  `/system/app` 目录层作为一个整体处理，而不是新增子项。
  mountify 的 `single_depth()` 用 `ls -d */` 遍历后逐个
  `mount -o lowerdir=...`，对 `/system/app` 这种"大量子目录"的父目录并不适用
  （且会与系统自身 `/product/app` 的 overlay 链冲突），因此必然失败并被静默跳过。

这不是开关问题：`mountify_mounts=2` 已生效（另 4 个模块都挂上了），
`Zhang` 也没有 `disable` / `remove` / `skip_mountify` / `system/etc/hosts`
这些会被跳过的标记。

### 结论与建议

**Zhang 内置 APK 是否挂进 /system 与本模块功能无关**。
Zhang 的设计是维护「母版 system/app 目录 + 一键安装/更新脚本」
（见模块根目录 `一键安装所有apk.sh`、`一键更新系统apk.sh`、`修正Apk名字.sh`），
通过 `pm install` 装到 `/data/app`，而非依赖 mountify 挂载。
实测这些包都在 `/data/app/` 正常安装并生效（见上一轮一致性核查报告）。

若确实想让它们以系统应用形态出现，正确做法是：

1. 用 KSU 原生模块机制（去掉 `skip_mount`，让 KernelSU 自己 mount），
   而不是交给 mountify；或
2. 继续用模块自带的 `一键安装所有apk.sh` 走 `/data/app` 路线（当前做法）。

**不建议**为了 mountify 去改 Zhang 的目录布局。

## 三、本次同时修复的问题

见提交 `43d3fb6`：游戏模块空转占单核 83% 降到 55%，
以及游戏 OOM 保护 adj 只设置不回收的存量泄漏。

## 四、取证命令备忘

```sh
# mountify 自报挂载了谁
grep description /data/adb/modules/mountify/module.prop
cat /data/adb/modules/mountify/mount_diff
# 本模块 APK 是否进系统
mount | grep -c ' /system/app '
ls /system/app | wc -l
# 哪些模块带 system 目录
for d in /data/adb/modules/*/; do [ -d "$d/system" ] && basename $d; done
```
