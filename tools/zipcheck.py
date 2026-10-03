#!/system/bin/python3
# -*- coding: utf-8 -*-
"""
zipcheck.py —— 模块自带完整性校验工具（与 zippack.py 配套，零外部依赖）

作用：
  1. 把 zip 解压到指定目录（保留权限位，用于比对）；
  2. 对「源目录」与「解压目录」分别生成 SHA256 清单并比对；
  3. 汇总差异并给出可读报告。

这样即使系统没有 `unzip` 二进制（或行为不一致），也能完成打包自检。

用法:
    python3 zipcheck.py <zip> <源目录> <解压临时目录>
    python3 zipcheck.py --manifest <目录>        # 只打印清单
    python3 zipcheck.py --sha256 <文件>          # 打印单文件 SHA256

退出码: 0=一致  1=有差异/失败
"""
import hashlib
import os
import sys
import zipfile

EXCLUDE_NAMES = {"ZhangProtect-Android.zip"}
EXCLUDE_DIRS = {"__pycache__"}
BUF = 1 << 20


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(BUF)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def manifest(root):
    """返回 {相对路径: (sha256, 大小)}；符号链接按目标文本哈希。"""
    root = os.path.abspath(root)
    out = {}
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in EXCLUDE_DIRS]
        for f in fns:
            if f in EXCLUDE_NAMES:
                continue
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, root)
            if os.path.islink(full):
                data = os.readlink(full).encode()
                out[rel] = (hashlib.sha256(data).hexdigest(), len(data))
            else:
                out[rel] = (sha256_file(full), os.path.getsize(full))
    return out


def extract(zpath, dest):
    """解压 zip 到 dest（保留 Unix 权限位）。"""
    dest = os.path.abspath(dest)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zpath) as z:
        for info in z.infolist():
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
                continue

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
                continue

            with open(target, "wb") as fh:
                fh.write(data)
            if mode:
                try:
                    os.chmod(target, mode)
                except OSError:
                    pass
    return 0


def compare(src, dest):
    ms = manifest(src)
    md = manifest(dest)
    only_src = sorted(set(ms) - set(md))
    only_dst = sorted(set(md) - set(ms))
    diff = sorted(k for k in (set(ms) & set(md)) if ms[k] != md[k])

    if not only_src and not only_dst and not diff:
        print("[+] 完整性校验通过: 共 %d 个文件, SHA256 全部一致" % len(ms))
        return 0

    print("[!] 完整性校验未通过:")
    for k in only_src[:20]:
        print("    仅源目录存在: %s" % k)
    for k in only_dst[:20]:
        print("    仅zip内存在:  %s" % k)
    for k in diff[:20]:
        print("    内容不一致:  %s (src=%s zip=%s)" % (k, ms[k][0][:12], md[k][0][:12]))
    if len(only_src) + len(only_dst) + len(diff) > 60:
        print("    ...（其余略）")
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
    extract(zpath, dest)
    sys.exit(compare(src, dest))