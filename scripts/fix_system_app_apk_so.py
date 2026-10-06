#!/usr/bin/env python3
"""
批量修复「被挂载为系统应用」的 APK：把 lib/**/*.so 重打包为 Stored（未压缩）。

## 背景（真机实测结论）

Android 上 native 库有两条加载路径：

1. 普通应用（/data/app，extractNativeLibs=true）
   → 安装时系统把 so **解压**到 /data/app/.../lib/<abi>/，运行时从那里加载。
      APK 内 so 压缩与否无所谓。

2. 系统应用 / 直接以 `<apk>!/lib/<abi>` 加载
   → 系统**不解压**，dlopen 直接 mmap APK 内条目。
      **此时 so 必须 Stored（未压缩）**，否则：
        dlopen failed: library ".../base.apk!/lib/arm64-v8a/xxx.so" not found

Shizuku 的 starter 就是第 2 条路径：它用 `pm path` 拿 APK 路径，然后设
`-Dshizuku.library.path=<apk>!/lib/<abi>`。原 APK 的 so 是 Deflate，
所以服务端 rikka.shizuku.server.ShizukuService 一启动就
UnsatisfiedLinkError，表现为「Shizuku 服务未运行」。

实测扫描：本机 system/app 下 26 个挂载应用里 22 个 so 本来就是 Stored
（说明作者本就按系统应用方式打包），只有少数含 Deflate。

## 本脚本做什么

扫描 `<root>/system/app` 与 `<root>/system/priv-app`，对每个 APK：

- 若所有 .so 已是 Stored → 跳过；
- 否则把 .so 重写为 Stored，其余条目保持原压缩方式，覆盖原文件
  （先备份为 `.apk.pre-stored.bak`）。

用法：
    python3 fix_system_app_apk_so.py <root> [--dry-run]

<root> 可以是母版目录，也可以是 /data/adb/modules/Zhang。
"""
import os
import shutil
import sys
import zipfile


def analyzed(path: str):
    """返回 (so总数, Stored数)。"""
    with zipfile.ZipFile(path) as z:
        sos = [i for i in z.infolist() if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        stored = sum(1 for i in sos if i.compress_type == zipfile.ZIP_STORED)
        return len(sos), stored


def repack_stored(src: str, dst: str) -> None:
    """把 src 中的 .so 改写为 Stored，输出到 dst。"""
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            is_so = info.filename.startswith("lib/") and info.filename.endswith(".so")
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = zipfile.ZIP_STORED if is_so else info.compress_type
            ni.external_attr = info.external_attr
            ni.internal_attr = info.internal_attr
            ni.create_system = info.create_system
            zout.writestr(ni, data)


def process_apk(apk: str, dry: bool) -> str:
    total, stored = analyzed(apk)
    if total == 0:
        return "no-so"
    if stored == total:
        return "ok"
    if dry:
        return f"would-fix({stored}/{total})"
    bak = apk + ".pre-stored.bak"
    tmp = apk + ".tmp"
    try:
        shutil.copy2(apk, bak)
        repack_stored(apk, tmp)
        os.replace(tmp, apk)
        return f"fixed({total - stored}/{total} 个 so 转为 Stored)"
    except Exception as e:  # noqa: BLE001
        if os.path.exists(tmp):
            os.remove(tmp)
        return f"ERROR: {e}"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = sys.argv[1].rstrip("/")
    dry = "--dry-run" in sys.argv

    fixed = skipped = noso = 0
    for sub in ("system/app", "system/priv-app"):
        base = os.path.join(root, sub)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            d = os.path.join(base, name)
            if not os.path.isdir(d):
                continue
            apks = [f for f in os.listdir(d) if f.lower().endswith(".apk")]
            if not apks:
                continue
            apk = os.path.join(d, apks[0])
            r = process_apk(apk, dry)
            if r == "ok":
                skipped += 1
            elif r == "no-so":
                noso += 1
            else:
                fixed += 1
                print(f"  {name}: {r}")
    print(f"完成：修复 {fixed}，已是 Stored {skipped}，无 so {noso}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())