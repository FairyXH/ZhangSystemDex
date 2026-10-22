# 母版（ZhangProtect-Android）与已安装模块一致性核查

- 日期：2026-10-22
- 母版路径：`/data/media/0/Download/ZhangProtect-Android/`
- 已安装模块：`/data/adb/modules/Zhang/`
- 设备：OnePlus PKG110 / Android 15 / ColorOS V15.0.0

## 1. Main.dex 一致性：通过

| 位置 | md5 | 字节 |
|---|---|---|
| 母版 `ZhangProtect-Android/Main.dex` | `2f764b9f6992d8b4a2a9e38da77265ba` | 2817344 |
| 已安装 `/data/adb/modules/Zhang/Main.dex` | 同上 | 2817344 |
| 运行时副本 `/data/adb/Zhang/Main.dex` | 同上 | 2817344 |
| `/sdcard/Download/Main.dex` | 同上 | 2817344 |
| 仓库 `Main.dex` | 同上 | 2817344 |

运行时 `/api/overview` 自报 `dexMd5` / `dexSize` 与上表一致。

`strings` 校验母版 dex 含全部最新修复标记：
`ensureBaseDirs`、`BASE_DOWNLOAD_DIRS`、`SelfOomGuardModule`、`sysfsBatteryText`、
`MAX_BODY_BYTES`、`ACQUIRE_TIMEOUT_MS`，确认对应最新源码构建（非旧 dex 碰巧同名）。

## 2. 内置 APK 一致性：通过（修复 1 处过期）

- 母版 `system/app`：50 个目录；已安装：46 个目录。
- 差异 4 个（`io.github.fentia.screenunlock`、`io.github.generalpeople1970.adbstayawake`、
  `io.github.shuoh118.autousbmtp`、`io.github.xiaotong6666.fusehide`）为用户**新增**内容，
  经确认无需纳入模块内置比对，未做任何改动。
- 其余 45 个 APK 全部 md5 一致。

### 修复项：`com.github.tianma8023.xposed.smscode`

| 来源 | versionName | versionCode | md5 |
|---|---|---|---|
| 母版 | — | — | `9355ce54f0b7703556e838c84a1b0610` |
| `/data/app`（用户安装，实际生效） | 3.4.0 | 714 | `9355ce54...` |
| 已安装模块 `/system/app`（修复前） | 3.3.9 | 656 | `5b96926b8e2c9820d7d8fb292f45e3f0` |

判定：母版为新版，模块内置为旧版 3.3.9。按既定规则用母版覆盖已安装模块：
旧文件备份至 `/data/adb/modules/Zhang/smscode.apk.bak`，覆盖后权限 `777 root:root`。

> 注意：该包本机未挂载到 `/system/app`（用户已装 3.4.0 覆盖显示），
> 但模块内置备份仍是 3.3.9，一旦卸载用户版会回落到旧版，故必须同步。

## 3. 补回 `/sdcard/Download/Main.dex`

此前因一次被安全策略拦截的 `rm -rf` 误删，已从母版复制补回，md5 校验一致。

## 4. 结论

母版与已安装模块完全对齐，且对应最新源码构建，可直接作为发布基线。
