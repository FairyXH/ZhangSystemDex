#!/system/bin/python3
# -*- coding: utf-8 -*-
"""
zippack.py —— 模块自带打包工具（与 aapt / shfmt 同属 tools/，零外部依赖）

为什么需要它：
  Android 系统普遍**没有 `zip` 命令**（toybox / busybox 均未内置 zip applet），
  而模块打包又必须生成 **可被 KernelSU/Magisk 正确安装** 的 zip。
  本脚本用 Python 标准库 zipfile 实现打包，并**严格保留 Unix 权限位**
  （755 / 644），这是安装脚本 post-fs-data.sh / service.sh 能被正确执行的前提。

用法:
    python3 zippack.py <源目录> <输出zip>
    python3 zippack.py --list <zip>          # 列出条目（含权限）

特性:
  * 保留 st_mode（755/644）→ zip 内 external_attr 写入 Unix 模式
  * 保留符号链接（不解引用）
  * 写入目录条目（含空目录），确保结构完整
  * 跳过 __pycache__ / 自身产物
  * 流式写入（1MB 缓冲），400MB+ 目录也不会爆内存
"""
import os
import stat
import sys
import zipfile

# 需要排除的条目名（打包脚本自身的产物，避免自包含）
EXCLUDE_NAMES = {"ZhangProtect-Android.zip"}
EXCLUDE_DIRS = {"__pycache__"}


def pack(src, out):
    src = os.path.abspath(src)
    count = 0
    total = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
        for dp, dns, fns in os.walk(src):
            dns[:] = sorted(d for d in dns if d not in EXCLUDE_DIRS)

            # 目录条目（保留权限，确保空目录可还原）
            for d in dns:
                full = os.path.join(dp, d)
                arc = os.path.relpath(full, src) + "/"
                try:
                    st = os.stat(full)
                except OSError:
                    continue
                zi = zipfile.ZipInfo.from_file(full, arc)
                zi.compress_type = zipfile.ZIP_STORED
                zi.external_attr = (stat.S_IFDIR | (st.st_mode & 0o7777)) << 16
                z.writestr(zi, b"")

            # 文件条目
            for f in sorted(fns):
                if f in EXCLUDE_NAMES:
                    continue
                full = os.path.join(dp, f)
                arc = os.path.relpath(full, src)

                # 符号链接：存链接目标文本，不解引用
                if os.path.islink(full):
                    target = os.readlink(full)
                    zi = zipfile.ZipInfo(arc)
                    zi.create_system = 3  # Unix
                    zi.external_attr = 0xA1FF0000  # S_IFLNK | 0777
                    z.writestr(zi, target)
                    count += 1
                    continue

                zi = zipfile.ZipInfo.from_file(full, arc)
                zi.compress_type = zipfile.ZIP_DEFLATED
                with open(full, "rb") as fh, z.open(zi, "w") as w:
                    while True:
                        buf = fh.read(1 << 20)
                        if not buf:
                            break
                        w.write(buf)
                total += os.path.getsize(full)
                count += 1

    print("zippack: 打包 %d 个文件, 原始 %d 字节 -> %s (%d 字节)"
          % (count, total, out, os.path.getsize(out)))
    return 0


def list_zip(path):
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            mode = (info.external_attr >> 16) & 0o7777
            print("%04o  %10d  %s" % (mode, info.file_size, info.filename))
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--list":
        sys.exit(list_zip(sys.argv[2]))
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        sys.exit(2)
    sys.exit(pack(sys.argv[1], sys.argv[2]))
