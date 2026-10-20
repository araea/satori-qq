#!/usr/bin/env python3
"""比较两个 QQ 安装包里、模块用得到的类的签名。

    scripts/dex-diff.py <旧 base.apk> <新 base.apk>

`internal/compat` 只能在已经装上新 QQ 的手机上核对表里的东西；这个脚本在升级前后都能跑，
覆盖面也更大：kernel / kernelpublic 的 nativeinterface 全包，加上源码里点名的其他 QQ 类。
输出三段：源码点名的类在新版里没了的；类的增删数目；源码提到（按简单类名匹配）的类里
成员的增删。方法以 dex 描述符比较，参数类型变了也算一增一删。

只读安装包、不执行其中任何代码；纯 Python，不需要 jadx / baksmali。
"""
import pathlib
import re
import struct
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
KERNEL = ('Lcom/tencent/qqnt/kernel/nativeinterface/', 'Lcom/tencent/qqnt/kernelpublic/nativeinterface/')
QQ_LITERAL = re.compile(r'"((?:com\.tencent|mqq|oicq)\.[A-Za-z0-9_.$]+)"')


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        s += 7
        if x < 0x80:
            return r, o


def parse(b, wanted, out):
    """把 dex 里 wanted(类描述符) 为真的类定义收进 out：{类: {成员行}}。"""
    u = lambda o: struct.unpack_from('<I', b, o)[0]
    _, _, tid_n, tid_o, pid_n, pid_o, fid_n, fid_o, mid_n, mid_o, cd_n, cd_o = \
        [u(0x38 + 4 * i) for i in range(12)]
    sid_o = u(0x3c)

    def string(i):
        o = u(sid_o + 4 * i)
        _, o = uleb(b, o)
        return b[o:b.index(0, o)].decode('utf-8', 'replace')

    def typ(i):
        return string(u(tid_o + 4 * i))

    def proto(i):
        o = pid_o + 12 * i
        args, po = [], u(o + 8)
        if po:
            args = [typ(struct.unpack_from('<H', b, po + 4 + 2 * k)[0]) for k in range(u(po))]
        return '(' + ''.join(args) + ')' + typ(u(o + 4))

    def field(i):
        _, t, n = struct.unpack_from('<HHI', b, fid_o + 8 * i)
        return string(n) + ':' + typ(t)

    def method(i):
        _, p, n = struct.unpack_from('<HHI', b, mid_o + 8 * i)
        return string(n) + proto(p)

    for c in range(cd_n):
        o = cd_o + 32 * c
        cls = typ(u(o))
        if not wanted(cls):
            continue
        lines = out.setdefault(cls, set())
        sup, itf, data = u(o + 8), u(o + 12), u(o + 24)
        if sup != 0xffffffff:
            lines.add('extends ' + typ(sup))
        if itf:
            for k in range(u(itf)):
                lines.add('implements ' + typ(struct.unpack_from('<H', b, itf + 4 + 2 * k)[0]))
        if not data:
            continue
        p = data
        sf, p = uleb(b, p)
        inf, p = uleb(b, p)
        dm, p = uleb(b, p)
        vm, p = uleb(b, p)
        idx = 0
        for k in range(sf + inf):
            if k == sf:
                idx = 0
            d, p = uleb(b, p)
            _, p = uleb(b, p)
            idx += d
            lines.add('F ' + field(idx))
        idx = 0
        for k in range(dm + vm):
            if k == dm:
                idx = 0
            d, p = uleb(b, p)
            _, p = uleb(b, p)
            _, p = uleb(b, p)
            idx += d
            lines.add('M ' + method(idx))


def load(apk, wanted):
    out = {}
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                parse(z.read(name), wanted, out)
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    sources = ''.join(p.read_text(encoding='utf-8') for p in (ROOT / 'src').rglob('*.java'))
    named = {'L' + n.replace('.', '/') + ';' for n in QQ_LITERAL.findall(sources)}
    wanted = lambda c: c.startswith(KERNEL) or c in named
    old, new = load(sys.argv[1], wanted), load(sys.argv[2], wanted)
    words = set(re.findall(r'[A-Za-z_][A-Za-z0-9_]*', sources))

    print('== 源码点名、新版没有的类')
    for c in sorted(named):
        if c in old and c not in new:
            print('   ' + c)
    # 混淆出来的短名（a、ab$c…）每版都会重排，删增只看有意义的名字。
    real = lambda c: not re.fullmatch(r'[a-z]{1,3}(\$.*)?', c.rsplit('/', 1)[1].rstrip(';'))
    gone = sorted(c for c in set(old) - set(new) if real(c))
    added = sorted(c for c in set(new) - set(old) if real(c))
    print('== 类：删 %d，增 %d（不计混淆短名）' % (len(gone), len(added)))
    for c in gone:
        print('   - ' + c)
    print('== 源码提到的类的成员变化')
    for c in sorted(set(old) & set(new)):
        simple = c.rsplit('/', 1)[1].rstrip(';').split('$')[0]
        if old[c] == new[c] or (c not in named and simple not in words):
            continue
        print(c)
        for m in sorted(old[c] - new[c]):
            print('   - ' + m)
        for m in sorted(new[c] - old[c]):
            print('   + ' + m)
    return 0


sys.exit(main())
