# 构建笔记（ZhangSystemDex）

## 产物与入口

- 源码：`app/src/main/java/io/github/fairyxh/zhangsystemdex/`
- WebUI：`app/src/main/assets/webroot/index.html`
- 构建：`./gradlew :app:assembleRelease`
- 产物：`app/build/outputs/apk/release/app-release-unsigned.apk`
- **发布用的 `Main.dex` = 上述 APK 里的 `classes.dex`**（本项目为单 dex）。

提取命令：

```sh
unzip -p app/build/outputs/apk/release/app-release-unsigned.apk classes.dex > Main.dex
```

发布前自检（`构建MainDex.bat` 同款要求）：

- `Main.dex` 必须含 `HttpBackend` 标记（否则是旧 dex，WebUI 无后端）；
- `Main.dex` 必须含 `SkipMountGuardModule` 标记；
- 发布必须同步：仓库根 `Main.dex` + `app/src/main/assets/webroot/index.html`，
  否则 OTA 会把设备降级回旧版本。

## ⚠️ proot 环境的 AAPT2 坑（本机必读）

本机为 **aarch64 宿主 + x86_64 Ubuntu proot**，x86_64 二进制通过
**qemu 用户态模拟**（`/usr/bin/qemu-amd64-static`）执行。

**现象**：`./gradlew :app:assembleRelease` 报

```
AAPT2 aapt2-9.1.1-14792394-linux Daemon #0: Daemon startup failed
```

**根因**：直接 exec x86_64 的 `aapt2` 会 **SIGILL（Illegal instruction）**，
而显式经 qemu 就没问题：

```sh
# ✗ 直接跑：无输出 / Illegal instruction
aapt2 version

# ✓ 显式走 qemu：正常
qemu-amd64-static -L /usr/x86_64-linux-gnu aapt2 version
```

**解决**：做一个包装脚本，让 Gradle 通过它调用 aapt2。

```sh
mkdir -p /opt/aapt2wrap
cat > /opt/aapt2wrap/aapt2 <<'E'
#!/bin/sh
exec qemu-amd64-static -L /usr/x86_64-linux-gnu \
  /opt/android-sdk/build-tools/36.0.0/aapt2 "$@"
E
chmod 755 /opt/aapt2wrap/aapt2
```

**不需要再手改 `gradle.properties`。** `build.gradle.kts` 已做平台感知探测：

- Linux 上 `/opt/aapt2wrap/aapt2` 存在且可执行 → 自动注入 `android.aapt2FromMavenOverride`；
- Windows / macOS → 不注入任何属性，使用 AGP 自带的 Maven 版 aapt2。

解析优先级：`-Paapt2Override=<path>` > 环境变量 `AAPT2_WRAPPER` > Linux 默认包装脚本。

```sh
# 手动指定（可选，优先级最高）
./gradlew :app:assembleRelease -Paapt2Override=/opt/aapt2wrap/aapt2
AAPT2_WRAPPER=/opt/aapt2wrap/aapt2 ./gradlew :app:assembleRelease
```

指定路径不存在时在配置阶段直接失败并打印该路径，不会静默回落。

⚠️ `android.aapt2FromMavenOverride` **禁止**再写进 `gradle.properties`：
它随仓库扩散会让 Windows/macOS 构建去找不存在的 `/opt/...` 而失败
（commit `aabf67e` 曾误提交该行，已由平台感知逻辑取代）。

注意：`/lib64` 符号链接曾在本机被破坏（`/lib64/ld-linux-x86-64.so.2`
为 x86_64 动态加载器）。已修复为：
`/lib64 -> /usr/x86_64-linux-gnu/lib64`。
若 `ls /lib64` 报不存在，重建即可。

## 产物部署

Windows 侧用 `部署母版到已安装.sh` / `push.bat`；本机（Android）流程：

1. 把新 `Main.dex` 与 `webroot/index.html` 同步到母版
   `/sdcard/Download/Files/ZhangProtect-Android/`；
2. 同步到已安装模块 `/data/adb/modules/Zhang/`；
3. 在母版目录执行 `sh pack.sh` 生成 `ZhangProtect-Android.zip`
   （内部 zipcheck 会做 SHA256 逐文件校验）。

## ✅ aarch64 proot 环境实测可用的构建方案（2026-10-22 验证）
本机若为 **aarch64 Ubuntu proot**（无 Android SDK），按以下步骤可完整构建：
```sh
# 1. SDK（commandlinetools 仅提供 x86_64 aapt2，靠 qemu 跑）
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p /opt/android-sdk/cmdline-tools && unzip -q commandlinetools-linux-*.zip   -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools   /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk   'build-tools;36.0.0' 'platforms;android-36'

# 2. x86_64 用户态模拟 + 交叉库
apt-get install -y qemu-user-static libc6-amd64-cross libgcc-s1-amd64-cross
ln -sfn /usr/x86_64-linux-gnu/lib64 /lib64
ln -sfn /usr/x86_64-linux-gnu/lib/libgcc_s.so.1 /usr/x86_64-linux-gnu/lib64/libgcc_s.so.1

# 3. aapt2 包装（Gradle 通过它调用）
mkdir -p /opt/aapt2wrap
printf '#!/bin/sh
exec qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/android-sdk/build-tools/36.0.0/aapt2 "$@"
' > /opt/aapt2wrap/aapt2
chmod 755 /opt/aapt2wrap/aapt2
```
包装脚本就位后**无需任何额外配置**：`build.gradle.kts` 会在 Linux 上自动探测
`/opt/aapt2wrap/aapt2` 并注入覆盖（逻辑见上一节）。
构建：`ANDROID_HOME=/opt/android-sdk ./gradlew :app:assembleRelease --no-daemon`
（首次会下载 Gradle 9.3.1，约 5 分钟；之后约 30 秒。）

## ✅ Windows 侧构建验证（2026-11-17）

去掉 `gradle.properties` 里的硬编码覆盖后，在 Windows + JDK 17 +
Android SDK（platform `android-37.1`、build-tools `37.0.0`）实测：

```bat
gradlew.bat clean :app:assembleRelease --console=plain --no-daemon
```

- `BUILD SUCCESSFUL`，`:app:processReleaseResources` 正常执行（aapt2 = Maven 版）；
- APK 内 `classes.dex` 与仓库根 `Main.dex` **md5 完全一致**（`f01cfaea…`），
  说明平台差异只影响资源编译链路，不影响交付产物；
- `-Paapt2Override=<不存在路径>` 会在配置阶段报
  `aapt2 override points to a missing file: …` 并中止。
