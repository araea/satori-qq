#!/usr/bin/env python3
"""生成发布附带的 update.json。

同一份文件有两个读者：Magisk / KernelSU / APatch 的模块页（认顶层的 version、versionCode、zipUrl、
changelog，module.prop 里的 updateJson 指向它）和模块管理页（另认 tag、notes、page、模块包的 sha256 / size；
--apk 生成的 app 一节如今没有读者，留作可选）。版本号从安装包本身读，不靠手填，所以清单永远与上传的文件一致。

用法：
    release-manifest.py --repo araea/satori-qq --tag 154-0.34.0 --notes NOTES.md \
        --module build/SatoriQQ-module.zip --out build/update.json
    --base URL    下载地址的前缀，默认是该 tag 的 GitHub 发布页；本机验收时指到本地服务器
satori-qq 与 satori-wx 共用同一份。
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, zipfile


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            digest.update(chunk)
    return digest.hexdigest()


def module_prop(zip_path):
    with zipfile.ZipFile(zip_path) as z:
        text = z.read('module.prop').decode('utf-8')
    props = dict(line.split('=', 1) for line in text.splitlines() if '=' in line)
    for key in ('id', 'version', 'versionCode'):
        if not props.get(key):
            sys.exit('module.prop 缺少 %s：%s' % (key, zip_path))
    return props


def apk_version(apk):
    aapt = os.environ.get('AAPT') or shutil.which('aapt') or os.path.expanduser(
        '~/android/android-sdk-tools/build-tools/aapt')
    out = subprocess.run([aapt, 'dump', 'badging', apk], capture_output=True, text=True, check=True).stdout
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not match:
        sys.exit('读不出 APK 的版本：%s' % apk)
    return match.group(1), int(match.group(2)), match.group(3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', required=True)
    ap.add_argument('--tag', required=True)
    ap.add_argument('--notes', required=True)
    ap.add_argument('--module')
    ap.add_argument('--apk')
    ap.add_argument('--out', required=True)
    ap.add_argument('--base')
    args = ap.parse_args()
    if not args.module and not args.apk:
        sys.exit('至少要有 --module 或 --apk')

    base = (args.base or 'https://github.com/%s/releases/download/%s' % (args.repo, args.tag)).rstrip('/')
    with open(args.notes, encoding='utf-8') as f:
        notes = f.read().strip()
    manifest = {
        'tag': args.tag,
        'notes': notes,
        'page': 'https://github.com/%s/releases/tag/%s' % (args.repo, args.tag),
    }
    if args.module:
        props = module_prop(args.module)
        name = os.path.basename(args.module)
        manifest.update({
            'version': props['version'],
            'versionCode': int(props['versionCode']),
            'zipUrl': '%s/%s' % (base, name),
            'changelog': '%s/CHANGELOG.md' % base,
            'sha256': sha256(args.module),
            'size': os.path.getsize(args.module),
        })
    if args.apk:
        package, code, version = apk_version(args.apk)
        manifest['app'] = {
            'package': package,
            'version': version,
            'versionCode': code,
            'url': '%s/%s' % (base, os.path.basename(args.apk)),
            'sha256': sha256(args.apk),
            'size': os.path.getsize(args.apk),
        }
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('update.json: %s（模块 %s，应用 %s）' % (
        args.out, manifest.get('version', '无'), manifest.get('app', {}).get('version', '无')))


if __name__ == '__main__':
    main()
