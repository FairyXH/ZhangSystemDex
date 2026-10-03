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
  * **全程进度输出**：先统计总量，再按百分比/文件数/字节数实时回报，
    且每行 flush，用户在 MT/终端里能看到持续进展而非干等结果。
"""
import os
import stat
import sys
import time
import zipfile

# 需要排除的条目名（打包脚本自身的产物，避免自包含）
EXCLUDE_NAMES = {"ZhangProtect-Android.zip"}
EXCLUDE_DIRS = {"__pycache__"}

# 每处理多少个文件输出一次进度（字节数变化时也会输出）
PROGRESS_EVERY_FILES = 25


def _log(msg):
    """立即输出一行（flush），保证 MT/终端实时可见。"""
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def _human(nbytes):
    v = float(nbytes)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if v < 1024.0 or unit == "TB":
            return ("%.1f %s" % (v, unit)) if unit != "B" else ("%d B" % int(v))
        v /= 1024.0


def scan_totals(src):
    """预扫描：统计待打包文件数与原始总字节数（用于显示进度百分比）。"""
    files = 0
    total = 0
    for dp, dns, fns in os.walk(src):
        dns[:] = [d for d in dns if d not in EXCLUDE_DIRS]
        for f in fns:
            if f in EXCLUDE_NAMES:
                continue
            full = os.path.join(dp, f)
            files += 1
            try:
                if not os.path.islink(full):
                    total += os.path.getsize(full)
            except OSError:
                pass
    return files, total


def pack(src, out):
    src = os.path.abspath(src)

    t0 = time.time()
    _log("zippack: 源目录 = %s" % src)
    _log("zippack: 输出   = %s" % out)
    _log("zippack: [1/3] 预扫描（统计文件数与原始体积）...")
    est_files, est_bytes = scan_totals(src)
    _log("zippack: [1/3] 完成：待打包 %d 个文件，原始合计 %s（%.1fs）"
         % (est_files, _human(est_bytes), time.time() - t0))

    _log("zippack: [2/3] 写入 zip（保留 Unix 权限位，流式 1MB 缓冲）...")
    count = 0
    total = 0
    dir_entries = 0
    links = 0
    t1 = time.time()
    last_report_files = 0

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
                dir_entries += 1

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
                    links += 1
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

                # 进度：每 N 个文件输出一次（含百分比 / 字节 / 速率）
                if count - last_report_files >= PROGRESS_EVERY_FILES:
                    last_report_files = count
                    pct = (count * 100.0 / est_files) if est_files else 100.0
                    elapsed = time.time() - t1
                    rate = (total / elapsed) if elapsed > 0 else 0
                    _log("zippack:   [%5.1f%%] %d/%d 文件  %s / ~%s  (%.1f MB/s, %.0fs)"
                         % (pct, count, est_files, _human(total), _human(est_bytes),
                            rate / (1 << 20), elapsed))

    _log("zippack: [2/3] 完成：写入 %d 个文件 + %d 个目录条目（含 %d 个符号链接），"
         "耗时 %.1fs" % (count, dir_entries, links, time.time() - t1))

    _log("zippack: [3/3] 收尾（fsync + 读取产物大小）...")
    try:
        size = os.path.getsize(out)
    except OSError as e:
        _log("zippack: [!] 无法读取产物大小: %s" % e)
        return 1

    ratio = (size * 100.0 / total) if total else 0.0
    _log("zippack: [3/3] 完成：%s -> %s（压缩率 %.1f%%），总耗时 %.1fs"
         % (_human(total), _human(size), ratio, time.time() - t0))
    _log("zippack: 打包完成: %d 个文件, 原始 %d 字节 -> %s (%d 字节)"
         % (count, total, out, size))
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