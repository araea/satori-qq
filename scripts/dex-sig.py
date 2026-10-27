#!/usr/bin/env python3
"""查 QQ 安装包里类的签名：适配新接口、核对回调形状时用，不必开 jadx。

    scripts/dex-sig.py <类名正则>              列出匹配类的父类、接口、字段与方法
    scripts/dex-sig.py -m <方法名正则>         内核包里有这个方法的类与方法签名
    scripts/dex-sig.py -m <方法名正则> -a      同上，但搜整个安装包（慢，约 30 秒）

安装包默认取 ~/archive/qq-apk/ 里最新的一份，`--apk <路径>` 换别的；`--apk installed` 用
`su` 读手机上装着的那份。类名正则对完整类名（点号形式）做 search；描述符里的
`com/tencent/qqnt/kernel(public)/nativeinterface/` 前缀打印时省掉。

内核包（kernel / kernelpublic 的 nativeinterface）解析一次后缓存在 ~/.cache/satori-qq/，
按安装包的大小与修改时间作废，第二次查不到一秒。
"""
import argparse
import pathlib
import pickle
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from dexlib import load  # noqa: E402

ARCHIVE = pathlib.Path.home() / 'archive/qq-apk'
CACHE = pathlib.Path.home() / '.cache/satori-qq'
KERNEL = ('Lcom/tencent/qqnt/kernel/nativeinterface/', 'Lcom/tencent/qqnt/kernelpublic/nativeinterface/')
SHORT = re.compile(r'Lcom/tencent/qqnt/kernel(?:public)?/nativeinterface/')


def apk_path(arg):
    if arg == 'installed':
        out = CACHE / 'installed-base.apk'
        CACHE.mkdir(parents=True, exist_ok=True)
        path = subprocess.check_output(['su', '-c', 'pm path com.tencent.mobileqq'], text=True)
        base = next(l[8:] for l in path.split() if l.endswith('base.apk'))
        with open(out, 'wb') as f:
            subprocess.check_call(['su', '-c', 'cat ' + base], stdout=f)
        return out
    if arg:
        return pathlib.Path(arg)
    apks = sorted(ARCHIVE.glob('qq-*.apk'), key=lambda p: [int(x) for x in re.findall(r'\d+', p.name)])
    if not apks:
        sys.exit('没有 %s/qq-*.apk，用 --apk 指定' % ARCHIVE)
    return apks[-1]


def kernel(apk):
    st = apk.stat()
    key = CACHE / ('kernel-%s-%d-%d.pickle' % (apk.stem, st.st_size, int(st.st_mtime)))
    if key.exists():
        return pickle.loads(key.read_bytes())
    classes = load(apk, lambda c: c.startswith(KERNEL))
    CACHE.mkdir(parents=True, exist_ok=True)
    for old in CACHE.glob('kernel-%s-*.pickle' % apk.stem):
        old.unlink()
    key.write_bytes(pickle.dumps(classes))
    return classes


def dotted(desc):
    return desc[1:-1].replace('/', '.')


def show(cls, lines, only=None):
    print(SHORT.sub('', dotted(cls)))
    for line in sorted(lines):
        if only and not (line.startswith('M ') and only.search(line[2:].split('(')[0])):
            continue
        print('   ' + SHORT.sub('', line))


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument('pattern', help='类名正则；带 -m 时是方法名正则')
    ap.add_argument('-m', '--method', action='store_true', help='按方法名找')
    ap.add_argument('-a', '--all', action='store_true', help='搜整个安装包，不止内核包')
    ap.add_argument('--apk', help='安装包路径，或 installed')
    args = ap.parse_args()
    apk = apk_path(args.apk)
    rx = re.compile(args.pattern)
    print('# ' + str(apk), file=sys.stderr)

    if args.method:
        classes = load(apk, lambda c: True) if args.all else kernel(apk)
        for cls in sorted(classes):
            if any(l.startswith('M ') and rx.search(l[2:].split('(')[0]) for l in classes[cls]):
                show(cls, classes[cls], rx)
        return 0

    in_kernel = kernel(apk)
    hits = {c: v for c, v in in_kernel.items() if rx.search(dotted(c))}
    if not hits:
        hits = load(apk, lambda c: bool(rx.search(dotted(c))))
    for cls in sorted(hits):
        show(cls, hits[cls])
    return 0 if hits else 1


if __name__ == '__main__':
    sys.exit(main())
