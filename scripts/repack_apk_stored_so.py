#!/usr/bin/env python3
"""
把 APK 内的 .so 重新打包为 Stored（未压缩），使其可作为「系统应用」被 dlopen。

## 为什么需要

Android 上加载 native 库有两条路径：

1. **普通应用**（/data/app）：若 `extractNativeLibs=true`，安装时系统把
   `lib/<abi>/*.so` **解压**到 `/data/app/.../lib/<abi>/`，运行时从那里加载。
   APK 里的 so 可以是压缩的。

2. **系统应用（或直接指定 apk!/lib 路径加载）**：系统**不会解压**，
   `dlopen` 直接 mmap APK 内的条目。**此时 so 必须未压缩（Stored）**，
   否则报：
       dlopen failed: library ".../base.apk!/lib/arm64-v8a/xxx.so" not found

Shizuku 的 starter 通过 `pm path` 定位 APK 并设置
`-Dshizuku.library.path=<apk>!/lib/<abi>`，走的是第 2 条路径。
原 APK 由普通应用打包（so 为 Deflate），一旦被挂成系统应用/被以
apk!/lib 方式加载，服务端就起不来。

## 本脚本做什么

读取 APK，把 `lib/**/*.so` 条目改为 **Stored**（不压缩）重新写出，
其余条目保持原样。产出 `<name>.fixed.apk`。

- 保留原压缩方式之外的所有 zip 元数据（时间、外部属性）；
- 不触动 AndroidManifest / 资源，因此**不改变包名、签名以外**的内容；
  ⚠️ 重新打包会**破坏原签名**，但由于本 APK 是被「模块挂载」使用
  （不是安装），签名不影响加载。

用法：
    python3 repack_apk_stored_so.py <in.apk> [out.apk]
"""
import os
import sys
import zipfile

SO_PREFIX = "lib/"
SO_SUFFIX = ".so"


def repack(src: str, dst: str) -> None:
    zin = zipfile.ZipFile(src, "r")
    zout = zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED)

    changed = 0
    total = 0
    for info in zin.infolist():
        data = zin.read(info.filename)
        is_so = info.filename.startswith(SO_PREFIX) and info.filename.endswith(SO_SUFFIX)
        if is_so:
            total += 1
            # 用 ZIP_STORED 写入未压缩数据
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new_info.compress_type = zipfile.ZIP_STORED
            new_info.external_attr = info.external_attr
            new_info.internal_attr = info.internal_attr
            new_info.create_system = info.create_system
            zout.writestr(new_info, data)
            changed += 1
        else:
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            new_info.internal_attr = info.internal_attr
            new_info.create_system = info.create_system
            zout.writestr(new_info, data)

    zout.close()
    zin.close()
    print(f"repacked: {src} -> {dst}  (so 条目 {total} 个，已改为 Stored)")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(src)[0] + ".fixed.apk"
    if not os.path.isfile(src):
        print(f"no such file: {src}")
        return 1
    repack(src, dst)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
