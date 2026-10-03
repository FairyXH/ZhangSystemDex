#!/system/bin/python3
# -*- coding: utf-8 -*-
"""
zipcheck.py —— 模块自带完整性校验工具（与 zippack.py 配套，零外部依赖）

作用：
  1. 把 zip 解压到指定目录（保留权限位，用于比对）；
  2. 对「源目录」与「解压目录」分别生成 SHA256 清单并比对；
  3. 汇总差异并给出可读报告。

这样即使系统没有 `unzip` 二进制（或行为不一致），也能完成打包自检。
**全程进度输出**（解压 / 计算 SHA256 / 比对），每行 flush，避免用户干等。

用法:
    python3 zipcheck.py <zip> <源目录> <解压临时目录>
    python3 zipcheck.py --manifest <目录>        # 只打印清单
    python3 zipcheck.py --sha256 <文件>          # 打印单文件 SHA256

退出码: 0=一致  1=有差异/失败
"""
import hashlib
import os
import sys
import time
import zipfile

EXCLUDE_NAMES = {"ZhangProtect-Android.zip"}
EXCLUDE_DIRS = {"__pycache__"}
BUF = 1 << 20

# 每处理多少个条目输出一次进度
PROGRESS_EVERY = 25


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


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(BUF)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def manifest(root, label="计算 SHA256"):
    """返回 {相对路径: (sha256, 大小)}；符号链接按目标文本哈希。带进度。"""
    root = os.path.abspath(root)

    # 先统计文件总量，便于显示百分比
    all_files = []
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in EXCLUDE_DIRS]
        for f in fns:
            if f in EXCLUDE_NAMES:
                continue
            all_files.append(os.path.join(dp, f))

    out = {}
    total_bytes = 0
    t0 = time.time()
    for i, full in enumerate(all_files, 1):
        rel = os.path.relpath(full, root)
        if os.path.islink(full):
            data = os.readlink(full).encode()
            out[rel] = (hashlib.sha256(data).hexdigest(), len(data))
        else:
            try:
                size = os.path.getsize(full)
            except OSError:
                size = 0
            out[rel] = (sha256_file(full), size)
            total_bytes += size
        if i % PROGRESS_EVERY == 0 or i == len(all_files):
            pct = (i * 100.0 / len(all_files)) if all_files else 100.0
            _log("zipcheck:   %s [%5.1f%%] %d/%d 文件  %s  (%.0fs)"
                 % (label, pct, i, len(all_files), _human(total_bytes), time.time() - t0))
    return out


def extract(zpath, dest):
    """解压 zip 到 dest（保留 Unix 权限位）。带进度。"""
    dest = os.path.abspath(dest)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zpath) as z:
        infos = z.infolist()
        n = len(infos)
        t0 = time.time()
        written = 0
        for i, info in enumerate(infos, 1):
            name = info.filename
            if name.endswith("/"):
                d = os.path.join(dest, name.rstrip("/"))
                os.makedirs(d, exist_ok=True)
                mode = (info.external_attr >> 16) & 0o7777
                if mode:
                    try:
                        os.chmod(d, mode)
                    except OSError:
                        pass
            else:
                target = os.path.join(dest, name)
                os.makedirs(os.path.dirname(target), exist_ok=True)

                mode = (info.external_attr >> 16) & 0o7777
                is_link = (info.external_attr >> 16) & 0o170000 == 0o120000
                data = z.read(info)
                if is_link:
                    try:
                        os.symlink(data.decode(), target)
                    except OSError:
                        pass
                else:
                    with open(target, "wb") as fh:
                        fh.write(data)
                    if mode:
                        try:
                            os.chmod(target, mode)
                        except OSError:
                            pass
                    written += len(data)
            if i % PROGRESS_EVERY == 0 or i == n:
                pct = (i * 100.0 / n) if n else 100.0
                _log("zipcheck:   解压 [%5.1f%%] %d/%d 条目  已写出 %s  (%.0fs)"
                     % (pct, i, n, _human(written), time.time() - t0))
    return 0


def compare(src, dest):
    src = os.path.abspath(src)
    dest = os.path.abspath(dest)
    t0 = time.time()

    _log("zipcheck: [1/3] 生成源目录清单...")
    ms = manifest(src, "源清单")
    _log("zipcheck: [1/3] 完成：源目录 %d 个文件（%.0fs）" % (len(ms), time.time() - t0))

    t1 = time.time()
    _log("zipcheck: [2/3] 生成解压目录清单...")
    md = manifest(dest, "解压清单")
    _log("zipcheck: [2/3] 完成：解压目录 %d 个文件（%.0fs）" % (len(md), time.time() - t1))

    _log("zipcheck: [3/3] 比对 SHA256 ...")
    only_src = sorted(set(ms) - set(md))
    only_dst = sorted(set(md) - set(ms))
    diff = sorted(k for k in (set(ms) & set(md)) if ms[k] != md[k])
    done = len(ms) - len(only_src)

    if not only_src and not only_dst and not diff:
        _log("[+] 完整性校验通过: 共 %d 个文件, SHA256 全部一致（%.0fs）"
             % (len(ms), time.time() - t0))
        return 0

    _log("[!] 完整性校验未通过:")
    for k in only_src[:20]:
        _log("    仅源目录存在: %s" % k)
    for k in only_dst[:20]:
        _log("    仅zip内存在:  %s" % k)
    for k in diff[:20]:
        _log("    内容不一致: %s (src=%s zip=%s)" % (k, ms[k][0][:12], md[k][0][:12]))
    if len(only_src) + len(only_dst) + len(diff) > 60:
        _log("    ...（其余略）")
    _log("    [概要] 源=%d 解压=%d 仅源=%d 仅zip=%d 不一致=%d（已比对 %d）"
         % (len(ms), len(md), len(only_src), len(only_dst), len(diff), done))
    return 1


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        sys.stderr.write(__doc__)
        sys.exit(2)

    if args[0] == "--manifest" and len(args) == 2:
        for k, v in sorted(manifest(args[1]).items()):
            print("%s  %s  %d" % (v[0], k, v[1]))
        sys.exit(0)

    if args[0] == "--sha256" and len(args) == 2:
        print(sha256_file(args[1]))
        sys.exit(0)

    if len(args) != 3:
        sys.stderr.write(__doc__)
        sys.exit(2)

    zpath, src, dest = args
    if os.path.isdir(dest):
        for dp, dns, fns in os.walk(dest, topdown=False):
            for f in fns:
                p = os.path.join(dp, f)
                try:
                    os.remove(p)
                except OSError:
                    pass
            for d in dns:
                p = os.path.join(dp, d)
                try:
                    os.rmdir(p)
                except OSError:
                    pass
    _log("zipcheck: 解压 %s -> %s" % (zpath, dest))
    extract(zpath, dest)
    sys.exit(compare(src, dest))