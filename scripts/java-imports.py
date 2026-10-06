#!/usr/bin/env python3
"""Java 源码的 import 规范：行内全限定类名收成 import，import 排成一块（静态在前，其余按 ASCII 序），
没用到的删掉。

用法：
    java-imports.py FILE...            改写文件
    java-imports.py --check FILE...    只检查，有文件需要改就返回 1

同名冲突（同包已有同名类、两个不同的全限定名同一个简单名）的保持全限定写法，不强改。
satori-qq 与 satori-wx 共用同一份。
"""
import re, sys, os

FQN = re.compile(r'(?<![\w.])((?:java|javax|android|org\.json|org\.w3c|org\.xml|com\.satori)\.(?:[a-z_][a-z0-9_]*\.)*)([A-Z]\w*)')

def mask(src):
    """Return list of (start,end,kind) regions for comments/strings; kind in c,s."""
    regions = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            regions.append((i, j)); i = j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            regions.append((i, j)); i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j > 0 and src[j-1] == '\\': j = src.find('"""', j + 1)
            j = n if j < 0 else j + 3
            regions.append((i, j)); i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            regions.append((i, j + 1)); i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            regions.append((i, j + 1)); i = j + 1
        else:
            i += 1
    return regions

def in_region(pos, regions, starts):
    import bisect
    k = bisect.bisect_right(starts, pos) - 1
    return k >= 0 and regions[k][0] <= pos < regions[k][1]

def package_of(src):
    m = re.search(r'^package ([\w.]+);', src, re.M)
    return m.group(1) if m else ''

PACKAGES = {}   # 包名 -> 这个包里的类名；按声明的包统计，与文件所在目录无关（测试类常常不在同名目录）


def register(path):
    src = open(path, encoding='utf-8').read()
    PACKAGES.setdefault(package_of(src), set()).add(os.path.basename(path)[:-5])


def simple_names_in_package(pkg):
    return PACKAGES.get(pkg, set())


def declared_types(src):
    return set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)', src))

def process(path, check=False):
    src = open(path, encoding='utf-8').read()
    orig = src
    pkg = package_of(src)
    # split off header (package + imports)
    m_imports = list(re.finditer(r'^import (static )?([\w.]+(?:\.\*)?);[ \t]*\n', src, re.M))
    existing = {}
    for m in m_imports:
        if m.group(1): continue
        full = m.group(2)
        existing[full.rsplit('.', 1)[-1]] = full
    regions = mask(src)
    starts = [r[0] for r in regions]
    body_start = m_imports[-1].end() if m_imports else (re.search(r'^package .*\n', src, re.M).end() if pkg else 0)
    same_pkg = simple_names_in_package(pkg)
    codeonly = list(src)
    for a,b in regions:
        for k in range(a,min(b,len(codeonly))): codeonly[k]=' '
    declared = declared_types(''.join(codeonly))
    wanted = {}   # simple -> full
    conflicts = set()
    edits = []
    for m in FQN.finditer(src):
        if m.start() < body_start: continue
        if in_region(m.start(), regions, starts): continue
        prefix, simple = m.group(1), m.group(2)
        full = prefix + simple
        if prefix[:-1] == pkg:   # same package, no import needed
            edits.append((m.start(), m.end(), simple)); continue
        # simple-name clash?
        if simple in existing and existing[simple] != full: conflicts.add(simple); continue
        if simple in wanted and wanted[simple] != full: conflicts.add(simple); continue
        if simple in declared or (simple in same_pkg and not full.startswith(pkg + '.')): conflicts.add(simple); continue
        wanted[simple] = full
        edits.append((m.start(), m.end(), simple))
    # drop edits whose simple name ended up conflicting
    edits = [e for e in edits if src[e[0]:e[1]].rsplit('.', 1)[-1] not in conflicts]
    for s, e, rep in sorted(edits, reverse=True):
        src = src[:s] + rep + src[e:]
    # rebuild import block
    imports = set()
    statics = set()
    for m in m_imports:
        (statics if m.group(1) else imports).add(m.group(2))
    for simple, full in wanted.items():
        if simple in conflicts: continue
        if not full.startswith(pkg + '.') or full.count('.') != pkg.count('.') + 1:
            imports.add(full)
    # remove imports now in same package or java.lang
    imports = {i for i in imports if not (i.startswith('java.lang.') and i.count('.') == 2)}
    imports = {i for i in imports if not (pkg and i.rsplit('.', 1)[0] == pkg)}
    # unused import removal
    body = re.sub(r'^import .*\n', '', src, flags=re.M)
    def used(imp):
        name = imp.rsplit('.', 1)[-1]
        return name == '*' or re.search(r'\b' + re.escape(name) + r'\b', body) is not None
    imports = {i for i in imports if used(i)}
    block = ''
    if statics: block += ''.join('import static %s;\n' % s for s in sorted(statics)) + '\n'
    block += ''.join('import %s;\n' % i for i in sorted(imports))
    # replace import region
    if m_imports:
        first, last = m_imports[0].start(), m_imports[-1].end()
        # recompute positions in edited src: find import region again
        mi = list(re.finditer(r'^import (static )?[\w.]+(?:\.\*)?;[ \t]*\n', src, re.M))
        first, last = mi[0].start(), mi[-1].end()
        mid = src[first:last]
        # keep non-import lines (comments) out: assume contiguous
        src = src[:first] + block + src[last:]
        src = re.sub(r'(\n){3,}', '\n\n', src[:first + len(block) + 200]) + src[first + len(block) + 200:]
    elif imports:
        mp = re.search(r'^package .*\n', src, re.M)
        src = src[:mp.end()] + '\n' + block + src[mp.end():].lstrip('\n').join(['\n', '']) if False else src[:mp.end()] + '\n' + block + '\n' + src[mp.end():].lstrip('\n')
    if conflicts:
        print('  conflicts in', path, sorted(conflicts), file=sys.stderr)
    if src != orig:
        if check: print('需要整理 import：', path)
        else: open(path, 'w', encoding='utf-8').write(src)
    return src != orig

if __name__ == '__main__':
    args = sys.argv[1:]
    check = '--check' in args
    files = [a for a in args if not a.startswith('--')]
    for f in files:
        register(f)
    changed = sum(process(f, check) for f in files)
    print(('需要改写' if check else '已改写'), changed, '个文件，共', len(files), '个')
    sys.exit(1 if check and changed else 0)
