"""读 QQ 安装包里的 dex：类定义的父类、接口、字段与方法签名。

纯 Python、只读、不执行包里任何代码。dex-diff.py 与 dex-sig.py 共用。
"""
import re
import struct
import zipfile


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
