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
from pathlib import Path, PurePosixPath

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


def _safe_target(dest_root, name):
    """校验 zip 条目名，返回 dest 内的绝对路径；越界一律拒绝（Zip Slip 防护）。

    * 拒绝绝对路径与含 `..` 的条目名；
    * 再用 resolve() + is_relative_to() 做一次真实路径归属校验，
      防止大小写/分隔符差异绕过前一步。
    """
    norm = name.replace("\\", "/")
    rel = PurePosixPath(norm)
    if rel.is_absolute() or ".." in rel.parts:
        raise ValueError("非法 zip 条目（越界）：%r" % name)
    target = (dest_root / norm).resolve()
    if not target.is_relative_to(dest_root):
        raise ValueError("非法 zip 条目（越界）：%r" % name)
    return target


def _ensure_parent(dest_root, target):
    """建立 target 的父目录；父目录若经符号链接逃逸 dest 则拒绝。"""
    parent = target.parent
    probe = parent
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    if not probe.resolve().is_relative_to(dest_root):
        raise ValueError("非法 zip 条目（符号链接逃逸）：%r" % target)
    parent.mkdir(parents=True, exist_ok=True)


def extract(zpath, dest):
    """解压 zip 到 dest（保留 Unix 权限位）。带进度。

    每个条目都先经 _safe_target / _ensure_parent 校验，
    越界或借符号链接逃逸时抛 ValueError，绝不写盘。
    """
    dest_root = Path(dest).resolve()
    dest_root.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zpath) as z:
        infos = z.infolist()
        n = len(infos)
        t0 = time.time()
        written = 0
        for i, info in enumerate(infos, 1):
            name = info.filename
            target = _safe_target(dest_root, name)
            if name.endswith("/"):
                _ensure_parent(dest_root, target)
                target.mkdir(exist_ok=True)
                mode = (info.external_attr >> 16) & 0o7777
                if mode:
                    try:
                        os.chmod(target, mode)
                    except OSError:
                        pass
            else:
                _ensure_parent(dest_root, target)

                mode = (info.external_attr >> 16) & 0o7777
                is_link = (info.external_attr >> 16) & 0o170000 == 0o120000
                data = z.read(info)
                if is_link:
                    if target.is_symlink() or target.exists():
                        target.unlink()
                    try:
                        target.symlink_to(data.decode())
                    except OSError:
                        pass
                else:
                    # 同名链接先移除，避免写入时跟随它落到 dest 之外
                    if target.is_symlink():
                        target.unlink()
                    # 落盘交给 stdlib：ZipFile.extract 内部同样会规范化成员名
                    # （剥离盘符/前导斜杠/..），与本函数的校验构成双保险。
                    z.extract(info, path=str(dest_root))
                    written += info.file_size
                    if mode:
                        try:
                            os.chmod(target, mode)
                        except OSError:
                            pass
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