# Shizuku 启动方案（官方 root 命令）

> 状态：已定稿（2026-10-06）。长期约束：**禁止改 Shizuku 包**，只用官方入口。

## 1. 背景问题

模块 `ShizukuModule` 保活长期失效：`serverPids` 恒为空，`restartCount` 飙升到 130+。
先前排查误判根因（曾考虑「so 被 Deflate 压缩」「必须重打包 APK 为 STORED」），均被否决。

## 2. 真正根因（已证实）

旧实现重启用的是 **ADB 模式遗留的 starter**：
- `/data/local/tmp/shizuku_starter`（官方 start.sh 中用于 **adb/wireless 模式**的流程）
- `/data/local/shizuku_starter`（2025-11 旧版，非官方位置）

该 starter 把库路径拼成 `<apk>!/lib/arm64-v8a`，导致：
```
nativeloader: Load <apk>!/lib/arm64-v8a/librish.so using isolated ns clns-1:
              dlopen failed: library ".../base.apk!/lib/arm64-v8a/librish.so" not found
java.lang.UnsatisfiedLinkError: dlopen failed: ... not found
  at rikka.shizuku.xh.I(SourceFile:30)
  at rikka.shizuku.server.ShizukuService.<init>
```
→ 服务端启动即失败。**与 APK 压缩方式无关，问题在用了错误的 starter 入口。**

另外，服务端进程名是 **`shizuku_server`**（不含包名），而旧识别逻辑只按包名
`pidsOf("moe.shizuku.privileged.api")` 匹配进程 → **永远匹配不到服务端**。

## 3. 官方 root 启动方式（源码依据）

官方仓库 `RikkaApps/Shizuku`，文件
`manager/src/main/java/moe/shizuku/manager/starter/Starter.kt`：

```kotlin
object Starter {
    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")
    val userCommand: String = starterFile.absolutePath
    val adbCommand = "adb shell $userCommand"
    val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
}
```

`StartRootViewHolder.kt` → `StarterActivity(EXTRA_IS_ROOT=true)` → 执行 `Starter.internalCommand`。

**即官方 root 启动命令为：**
```sh
<ApplicationInfo.nativeLibraryDir>/libshizuku.so --apk=<ApplicationInfo.sourceDir>
```

starter 本体是随 APK 分发的 native lib `libshizuku.so`，使用的是
**`nativeLibraryDir`（系统已解压的真实目录）**，因此 `librish.so` 能正常加载。

## 4. 实测验证结果

```
info: switch cgroup succeeded, cgroup in /sys/fs/cgroup
info: switching mount namespace to init...
info: starter begin
info: killing old process...
info: use apk path from argv
info: apk path is /data/app/~~xxxx==/moe.shizuku.privileged.api-...==/base.apk
info: starting server...
info: shizuku_server pid is 1821
info: shizuku_starter exit with 0
```
服务端进程：`1821  1  0  shizuku_server`（root 模式 uid=0，ppid=1 已 daemon 化）。

路径可动态获取（无需硬编码随机串）：
```
NLD = $(pm path <pkg> | sed 's/package://;s|/base.apk$||')/lib/<abi>   # ~= nativeLibraryDir
SRC = $(pm path <pkg> | sed 's/package://')                            # ~= sourceDir
```
（模块运行在 DexContext 中取不到 Android `ApplicationInfo`，故用 `pm path` + ABI 推导。）

## 5. 落地实现要点

1. `ShizukuModule.officialStartCommand()`：用 `pm path` 解析 `sourceDir`，推导
   `nativeLibraryDir/lib/<abi>`，拼接 `"<nld>/libshizuku.so" --apk="<src>"`。
   - ABI：优先 arm64-v8a → 目录 `lib/arm64`；兼容 armeabi-v7a → `lib/arm`。
   - 推导出的 `libshizuku.so` 不存在则回退旧 starter。
2. 重启优先级：**官方命令 → 旧 starter 兜底**。
3. 进程识别：服务端 = `pidsOf(PACKAGE)` ∪ `pidsOf("shizuku_server")`，
   再由 `classify()` 按 uid 划分（uid 0 / 2000 / 用户段≥900 视为服务端）。
4. `ShizukuResidue`：`/data/local/tmp/shizuku_starter` 是官方 start.sh 会重建的文件，
   默认改为「受保护（GUARDED）」，仅显式开关才清理。

## 6. 稳定性 / 通用性说明

| 维度 | 本方案 |
|---|---|
| 换设备 | 路径经 `pm path` + ABI 动态推导，无设备相关硬编码 |
| Shizuku 升级 | 使用官方契约参数 `--apk=`，升级后依旧有效 |
| 不改包 | 完全不修改 Shizuku APK |
| root 方案 | 不依赖具体 su 实现，仅需能执行 shell |
| 兜底 | 保留旧 starter 路径作为 fallback |
