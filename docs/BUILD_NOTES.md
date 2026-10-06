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

然后（**不要提交到仓库**，这是本机专属配置）在 `gradle.properties` 末尾加：

```properties
android.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2
```

即可 `./gradlew :app:assembleRelease` 成功。

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

