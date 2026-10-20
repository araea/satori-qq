#!/usr/bin/env python3
"""让 qq/Compat 的三张表跟上源码真正点名的内核类型。

    scripts/compat-table.py --check   # test.sh 跑这个；表与源码对不上就退出 1
    scripts/compat-table.py --write   # 就地重新生成 TYPES

TYPES 是 Compat 之外源码里全部 `com.tencent.qqnt.(kernel|kernelpublic).nativeinterface.X` 字面量。
CALLBACKS 与 STRUCT_FIELDS 仍是手写（参数个数与字段名要看 dex），但每一项都必须是源码在用的
类型，源码用到的每个 `I…Callback` 也必须列进 CALLBACKS。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPAT = ROOT / 'src/com/satori/qq/qq/Compat.java'
LITERAL = re.compile(r'com\.tencent\.qqnt\.(?:kernel|kernelpublic)\.nativeinterface\.[A-Za-z0-9_]+')
TYPES_BLOCK = re.compile(r'(private static final String\[\] TYPES = \{\n)(.*?)(\n    \};)', re.S)


def used_types():
    names = set()
    for path in (ROOT / 'src').rglob('*.java'):
        if path != COMPAT:
            names.update(LITERAL.findall(path.read_text(encoding='utf-8')))
    return sorted(names)


def table(src, name):
    block = re.search(r'private static final String\[\]\[\] ' + name + r' = \{\n(.*?)\n    \};', src, re.S)
    return re.findall(r'\{"([A-Za-z0-9_]+)"', block.group(1))


def render(types):
    rows = []
    for i in range(0, len(types), 2):
        rows.append('            ' + ' '.join('"%s",' % t for t in types[i:i + 2]))
    return '\n'.join(rows)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else '--check'
    src = COMPAT.read_text(encoding='utf-8')
    types = used_types()
    simple = {t.rsplit('.', 1)[1] for t in types}
    block = TYPES_BLOCK.search(src)
    if mode == '--write':
        COMPAT.write_text(src[:block.start(2)] + render(types) + src[block.end(2):], encoding='utf-8')
        print('TYPES: %d' % len(types))
        return 0
    problems = []
    listed = re.findall(r'"([^"]+)"', block.group(2))
    for t in sorted(set(types) - set(listed)):
        problems.append('TYPES missing ' + t)
    for t in sorted(set(listed) - set(types)):
        problems.append('TYPES lists unused ' + t)
    callbacks = table(src, 'CALLBACKS')
    for c in sorted(s for s in simple if s.startswith('I') and s.endswith('Callback')):
        if c not in callbacks:
            problems.append('CALLBACKS missing ' + c)
    for name in ('CALLBACKS', 'STRUCT_FIELDS'):
        for c in table(src, name):
            if c not in simple:
                problems.append('%s lists unused %s' % (name, c))
    for p in problems:
        print('   ' + p)
    if problems:
        print('   run scripts/compat-table.py --write, then fix CALLBACKS / STRUCT_FIELDS by hand')
        return 1
    print('   %d types, %d callbacks, %d structs in step' % (len(types), len(callbacks), len(table(src, 'STRUCT_FIELDS'))))
    return 0


sys.exit(main())
