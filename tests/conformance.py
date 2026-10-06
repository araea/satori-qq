#!/usr/bin/env python3
"""Black-box Satori v1 conformance probe. Read-only: it never sends a chat message.

  conformance.py --base http://127.0.0.1:3001 [--token T] [--listen SECONDS] [--slow] [--dump]

It talks to a running SDK (satori-qq, satori-wx or any other) and checks what the protocol
prescribes at https://satori.chat: the status-code table and feature rules (protocol/api),
READY / PING / resume (protocol/events), the meta and resource routes (advanced/meta,
advanced/resource), list and message shapes (resources/*), snake_case wire keys, and, with
--listen, the shape of live events including resource promotion.

Every line is PASS / FAIL / WARN / INFO; the exit status is the number of FAILs. INFO lines record
behaviour the spec leaves open, so two implementations can be compared side by side.
"""
import argparse, base64, hashlib, http.client, json, os, re, socket, struct, sys, time, uuid
from urllib.parse import urlparse

ap = argparse.ArgumentParser()
ap.add_argument('--base', required=True)
ap.add_argument('--token', default='')
ap.add_argument('--listen', type=int, default=0, help='seconds to collect live events and validate their shape')
ap.add_argument('--slow', action='store_true', help='also wait out the 10 s IDENTIFY deadline')
ap.add_argument('--dump', action='store_true', help='with --listen: print every event as received')
args = ap.parse_args()
U = urlparse(args.base)
HOST, PORT = U.hostname, U.port or 80

counts = {'PASS': 0, 'FAIL': 0, 'WARN': 0}
def out(kind, name, detail=''):
    counts[kind] = counts.get(kind, 0) + 1
    print(f'{kind:4} {name}' + (f' — {detail}' if detail else ''))
def check(cond, name, detail='', warn=False):
    out('PASS' if cond else ('WARN' if warn else 'FAIL'), name, '' if cond else detail)
    return bool(cond)

def error_shape(name, status, body):
    """Every non-2xx body from our servers is {"code": <slug>, "message": <text>} — clients branch on code."""
    if isinstance(body, (bytes, bytearray)):
        try: body = json.loads(body)
        except ValueError: body = None
    ok = isinstance(body, dict) and isinstance(body.get('code'), str) and body['code'] \
        and isinstance(body.get('message'), str)
    check(ok, f'errors.shape[{name}]', f'{status} {str(body)[:120]}')

def http_call(method, path, body=None, headers=None, raw=False, timeout=15):
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    h = dict(headers or {})
    try:
        c.request(method, path, body, h)
        r = c.getresponse()
        data = r.read()
        hd = {k.lower(): v for k, v in r.getheaders()}
        if raw:
            return r.status, hd, data
        try:
            return r.status, hd, json.loads(data) if data.strip() else None
        except ValueError:
            return r.status, hd, data
    finally:
        c.close()

AUTH = {'Authorization': 'Bearer ' + args.token} if args.token else {}
IDS = {}
def rpc(method, params=None, ids=True, auth=True, hdr=None):
    h = {'Content-Type': 'application/json'}
    if auth: h.update(AUTH)
    if ids and IDS: h.update({'Satori-Platform': IDS['platform'], 'Satori-User-ID': IDS['user']})
    h.update(hdr or {})
    return http_call('POST', '/v1/' + method, json.dumps(params or {}).encode(), h)

def camel_keys(v, path=''):
    bad = []
    if isinstance(v, dict):
        for k, x in v.items():
            if k.startswith('_') or k == 'referrer':
                continue
            if re.search(r'[a-z][A-Z]', k): bad.append(path + '.' + k)
            bad += camel_keys(x, path + '.' + k)
    elif isinstance(v, list):
        for i, x in enumerate(v[:20]): bad += camel_keys(x, path + f'[{i}]')
    return bad

STANDARD = ['channel.get','channel.list','channel.create','channel.update','channel.delete','channel.mute',
 'message.create','message.update','message.delete','message.get','message.list',
 'reaction.create','reaction.delete','reaction.clear','reaction.list','upload.create',
 'guild.get','guild.list','guild.member.get','guild.member.list','guild.member.kick','guild.member.mute',
 'guild.member.role.set','guild.member.role.unset','guild.member.role.list',
 'guild.role.list','guild.role.create','guild.role.update','guild.role.delete',
 'login.get','user.get','user.channel.create','friend.list','friend.delete',
 'friend.approve','guild.approve','guild.member.approve']

def is_ms(ts):
    return isinstance(ts, int) and 10**11 < ts < 10**14

# ---------------------------------------------------------------- meta / auth
print('# meta & auth')
st, hd, meta = http_call('POST', '/v1/meta', b'{}', {'Content-Type': 'application/json', **AUTH})
if st == 401 and not args.token:
    out('FAIL', 'meta.requires-token', 'pass --token'); sys.exit(1)
check(st == 200 and isinstance(meta, dict), 'meta.200', f'status {st}')
check(isinstance(meta.get('logins'), list) and isinstance(meta.get('proxy_urls'), list), 'meta.shape', 'logins[] and proxy_urls[] required')
check('application/json' in hd.get('content-type', ''), 'meta.content-type', hd.get('content-type'))
login = (meta.get('logins') or [None])[0]
if not login:
    out('WARN', 'meta.login', 'no login: account-scoped checks skipped')
else:
    IDS.update(platform=login.get('platform'), user=(login.get('user') or {}).get('id'))
    for k in ('sn', 'adapter', 'status'):
        check(k in login, f'login.{k}', 'missing')
    check(isinstance(login.get('sn'), int), 'login.sn-number', repr(login.get('sn')))
    check(isinstance(login.get('adapter'), str) and login['adapter'], 'login.adapter-string')
    check(login.get('status') in (0, 1, 2, 3, 4), 'login.status-enum', repr(login.get('status')))
    check(isinstance(login.get('platform'), str) and login['platform'], 'login.platform')
    check(isinstance((login.get('user') or {}).get('id'), str), 'login.user.id-string', repr((login.get('user') or {}).get('id')))
    check(isinstance(login.get('features'), list) and all(isinstance(f, str) for f in login.get('features', [])), 'login.features-strings')
    check(not camel_keys(meta), 'meta.snake-case', str(camel_keys(meta)))
features = set((login or {}).get('features') or [])

if args.token:
    st, _, body = http_call('POST', '/v1/meta', b'{}', {'Content-Type': 'application/json'})
    check(st == 401, 'auth.missing-token=401', f'got {st}')
    error_shape('401', st, body)
    st, _, _ = http_call('POST', '/v1/meta', b'{}', {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + 'x' * len(args.token)})
    check(st == 403, 'auth.wrong-token=403', f'got {st}')
else:
    st, _, _ = http_call('POST', '/v1/meta', b'{}', {'Content-Type': 'application/json', 'Authorization': 'Bearer wrong'})
    out('INFO', 'auth.open-server', f'no token configured; wrong token answered {st}')

if not login:
    sys.exit(counts['FAIL'])

# ---------------------------------------------------------------- routing
print('# routing & status codes')
st, _, body = rpc('nosuch.method')
check(st == 404, 'route.unknown-method=404', f'got {st}')
error_shape('404', st, body)
st, _, body = http_call('GET', '/v1/login.get', None, {**AUTH, 'Satori-Platform': IDS['platform'], 'Satori-User-ID': IDS['user']})
check(st == 405, 'route.get-on-rpc=405', f'got {st}')
error_shape('405', st, body)
st, _, body = rpc('login.get', ids=False)
out('INFO', 'headers.missing-login-headers', f'login.get without Satori-* headers -> {st}')
st, _, body = rpc('login.get', hdr={'Satori-User-ID': 'no-such-user-0'})
check(st in (403, 404), 'headers.unknown-user', f'got {st}', warn=True)
out('INFO', 'headers.unknown-user-status', f'{st} (upstream @satorijs/server answers 403)')
st, _, body = rpc('login.get', hdr={'Satori-Platform': 'no-such-platform'})
out('INFO', 'headers.unknown-platform-status', str(st))

SAMPLE = {'channel_id': 'x', 'guild_id': 'x', 'user_id': 'x', 'message_id': 'x', 'content': 'x', 'emoji_id': '1',
          'role_id': 'x', 'data': {}, 'approve': False, 'enable': False, 'duration': 0}
unsupported = [m for m in STANDARD if m not in features and m not in ('upload.create', 'login.get')]
if 'login.get' not in features:
    out('FAIL', 'features.login.get', 'login.get is implemented by every SDK and belongs in features')
for m in unsupported:
    st, _, _ = rpc(m, SAMPLE)
    if st != 404:
        out('FAIL', f'unsupported.{m}=404', f'got {st} (spec: 404 for an unsupported standard API, 501 only if the platform supports it but the adapter does not)')
    else:
        counts['PASS'] += 1
out('INFO', 'unsupported.count', f'{len(unsupported)} standard methods not in features; all must be 404')
for m in sorted(features):
    if m not in STANDARD and not re.match(r'^[a-z][a-z.\-]*$', m):
        out('FAIL', f'features.name.{m}', 'not a dotted lowercase name')
    if m.count('.') >= 1 and m.rsplit('.', 1)[0] in [s for s in STANDARD] and m not in STANDARD:
        out('INFO', f'features.extra.{m}', 'API extra feature')
extra = [f for f in features if f not in STANDARD]
out('INFO', 'features.extras', ', '.join(sorted(extra)) or 'none')

# ---------------------------------------------------------------- login.get
print('# login.get')
if True:
    st, _, lg = rpc('login.get')
    if check(st == 200 and isinstance(lg, dict), 'login.get.200', f'{st} {lg}'):
        same = {k: lg.get(k) for k in ('adapter', 'platform', 'status')} == {k: login.get(k) for k in ('adapter', 'platform', 'status')}
        check(same, 'login.get.matches-meta', f'{lg} vs {login}')
        check(sorted(lg.get('features', [])) == sorted(features), 'login.get.features-match-meta')
        check((lg.get('user') or {}).get('id') == IDS['user'], 'login.get.user-id')

# ---------------------------------------------------------------- lists / resources
print('# resources')
def listing(method, params, max_pages=3, label=None):
    label = label or method
    items, nxt, pages = [], None, 0
    seen = set()
    while pages < max_pages:
        p = dict(params)
        if nxt: p['next'] = nxt
        st, _, body = rpc(method, p)
        if not check(st == 200 and isinstance(body, dict) and isinstance(body.get('data'), list), f'{label}.list-shape', f'{st} {str(body)[:200]}'):
            return items
        items += body['data']
        check(not camel_keys(body), f'{label}.snake-case', str(camel_keys(body)[:3]))
        nxt = body.get('next')
        if not nxt:
            break
        if not check(isinstance(nxt, str) and nxt not in seen, f'{label}.next-token', f'repeated or non-string next {nxt!r}'):
            break
        seen.add(nxt); pages += 1
    return items

guilds = listing('guild.list', {}) if 'guild.list' in features else []
for g in guilds[:5]:
    check(isinstance(g.get('id'), str) and g['id'], 'guild.id-string', repr(g.get('id')))
    check(g.get('name') is None or isinstance(g['name'], str), 'guild.name-string')
friends = listing('friend.list', {}) if 'friend.list' in features else []
for f in friends[:5]:
    check(isinstance((f.get('user') or {}).get('id'), str), 'friend.user.id-string', repr(f))
guild = guilds[0] if guilds else None
if guild:
    gid = guild['id']
    st, _, g1 = rpc('guild.get', {'guild_id': gid}) if 'guild.get' in features else (0, 0, 0)
    if 'guild.get' in features:
        check(st == 200 and g1.get('id') == gid, 'guild.get', f'{st} {g1}')
    st, _, bad = rpc('guild.get', {}) if 'guild.get' in features else (400, 0, 0)
    check(st == 400, 'guild.get.missing-param=400', f'got {st}')
    chans = listing('channel.list', {'guild_id': gid}) if 'channel.list' in features else []
    for ch in chans[:3]:
        check(isinstance(ch.get('id'), str) and ch.get('type') in (0, 1, 2, 3), 'channel.shape', repr(ch))
    members = listing('guild.member.list', {'guild_id': gid}, max_pages=1) if 'guild.member.list' in features else []
    for m in members[:5]:
        check(isinstance((m.get('user') or {}).get('id'), str), 'member.user.id-string', repr(m)[:160])
        if m.get('joined_at') is not None: check(is_ms(m['joined_at']), 'member.joined_at-ms', repr(m['joined_at']))
        check('joinedAt' not in m, 'member.no-camelCase')
    if members and 'guild.member.get' in features:
        uid = members[0]['user']['id']
        st, _, mm = rpc('guild.member.get', {'guild_id': gid, 'user_id': uid})
        check(st == 200 and (mm.get('user') or {}).get('id') == uid, 'guild.member.get', f'{st} {str(mm)[:160]}')
    if 'guild.role.list' in features:
        roles = listing('guild.role.list', {'guild_id': gid}, max_pages=1)
        check(all(isinstance(r.get('id'), str) for r in roles), 'role.id-string')
    if members and 'guild.member.role.list' in features:
        st, _, rl = rpc('guild.member.role.list', {'guild_id': gid, 'user_id': members[0]['user']['id']})
        check(st == 200 and isinstance(rl.get('data'), list), 'guild.member.role.list', f'{st}')
if 'user.get' in features:
    st, _, u = rpc('user.get', {'user_id': IDS['user']})
    check(st == 200 and u.get('id') == IDS['user'], 'user.get.self', f'{st} {str(u)[:160]}')
    check(not camel_keys(u), 'user.snake-case', str(camel_keys(u)))
    st, _, u = rpc('user.get', {})
    check(st == 400, 'user.get.missing-param=400', f'got {st}')

# ---------------------------------------------------------------- messages
print('# messages')
def msg_shape(m, label):
    check(isinstance(m.get('id'), str) and m['id'], f'{label}.id-string', repr(m.get('id')))
    check(m.get('content') is None or isinstance(m['content'], str), f'{label}.content-string')
    if m.get('created_at') is not None:
        check(is_ms(m['created_at']), f'{label}.created_at-ms', repr(m['created_at']))
    check(not camel_keys(m), f'{label}.snake-case', str(camel_keys(m)))

chan = None
if guild and 'message.list' in features:
    chan = guild['id']
    # a group's channel is discovered by channel.list when there is one
    cs = listing('channel.list', {'guild_id': guild['id']}, max_pages=1) if 'channel.list' in features else []
    if cs: chan = cs[0]['id']
if chan:
    st, _, page = rpc('message.list', {'channel_id': chan})
    if check(st == 200 and isinstance(page, dict) and isinstance(page.get('data'), list), 'message.list.default', f'{st} {str(page)[:200]}'):
        data = page['data']
        for m in data[:3]: msg_shape(m, 'message.list.item')
        ts = [m.get('created_at') for m in data if m.get('created_at')]
        check(ts == sorted(ts), 'message.list.default-order=asc', 'default order must be asc regardless of direction')
        if data:
            check(all(m.get('user') for m in data[:5]), 'message.list.user-present', 'list requires the user resource', warn=True)
            check(len(data) < 50 or page.get('prev') is not None or page.get('next') is not None, 'message.list.token-present', 'a full page must carry a continuation token')
            if 'prev' in page and 'next' in page:
                check(page['prev'] == page['next'], 'message.list.before:prev==next', f"prev={page['prev']!r} next={page['next']!r}")
        st, _, big = rpc('message.list', {'channel_id': chan, 'limit': 100000})
        check(st == 200, 'message.list.limit-clamped', f'huge limit must be clamped, not rejected (got {st})')
        st, _, small = rpc('message.list', {'channel_id': chan, 'limit': 2})
        if check(st == 200, 'message.list.limit=2', str(st)):
            check(len(small['data']) <= 2, 'message.list.limit-respected', str(len(small['data'])))
            tok = small.get('next') or small.get('prev')
            if tok:
                st, _, older = rpc('message.list', {'channel_id': chan, 'limit': 2, 'next': tok, 'direction': 'before'})
                if check(st == 200, 'message.list.next-token', f'{st} {str(older)[:160]}'):
                    a = {m['id'] for m in small['data']}; b = {m['id'] for m in older['data']}
                    check(not (a & b), 'message.list.pages-disjoint', str(a & b))
        st, _, desc = rpc('message.list', {'channel_id': chan, 'limit': 5, 'order': 'desc'})
        if st == 200 and len(desc['data']) > 1:
            t2 = [m.get('created_at') for m in desc['data'] if m.get('created_at')]
            check(t2 == sorted(t2, reverse=True), 'message.list.order=desc', str(t2))
        st, _, aft = rpc('message.list', {'channel_id': chan, 'direction': 'after', 'next': ''})
        out('INFO', 'message.list.after-without-token', f'{st}  (spec: empty next => newest, direction must be before)')
        if data and 'message.get' in features:
            mid = data[-1]['id']
            st, _, one = rpc('message.get', {'channel_id': chan, 'message_id': mid})
            if check(st == 200 and one.get('id') == mid, 'message.get', f'{st} {str(one)[:200]}'):
                msg_shape(one, 'message.get')
                check(one.get('content') == data[-1].get('content'), 'message.get.content-equals-list', f"{one.get('content')!r} vs {data[-1].get('content')!r}", warn=True)
            st, _, none = rpc('message.get', {'channel_id': chan, 'message_id': 'definitely-not-a-message-id'})
            check(st in (404, 400), 'message.get.unknown-id', f'got {st} (404 expected)')
            check(st == 404, 'message.get.unknown-id=404', f'got {st}', warn=True)
    st, _, none = rpc('message.list', {})
    check(st == 400, 'message.list.missing-param=400', f'got {st}')
if 'message.create' in features:
    st, _, none = rpc('message.create', {'content': 'x'})
    check(st == 400, 'message.create.missing-channel=400', f'got {st}')
    st, _, none = rpc('message.create', {'channel_id': 'x'})
    check(st == 400, 'message.create.missing-content=400', f'got {st}')
if 'user.channel.create' in features and friends:
    uid = friends[0]['user']['id']
    st, _, dc = rpc('user.channel.create', {'user_id': uid})
    if check(st == 200 and isinstance(dc.get('id'), str) and dc.get('type') == 1, 'user.channel.create', f'{st} {dc}'):
        pass

# ---------------------------------------------------------------- upload + proxy
print('# resources: upload & proxy')
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')
if 'upload.create' in features:
    b = uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="foo"; filename="a.png"\r\nContent-Type: image/png\r\n\r\n').encode() + PNG + \
           (f'\r\n--{b}\r\nContent-Disposition: form-data; name="bar"; filename="b.png"\r\nContent-Type: image/png\r\n\r\n').encode() + PNG + f'\r\n--{b}--\r\n'.encode()
    h = {'Content-Type': f'multipart/form-data; boundary={b}', **AUTH, 'Satori-Platform': IDS['platform'], 'Satori-User-ID': IDS['user']}
    st, hd, up = http_call('POST', '/v1/upload.create', body, h)
    if check(st == 200 and isinstance(up, dict), 'upload.create.200', f'{st} {str(up)[:200]}'):
        check(set(up) == {'foo', 'bar'}, 'upload.create.keys-are-field-names', str(up))
        for k, url in up.items():
            check(isinstance(url, str) and url.startswith(f"internal:{IDS['platform']}/{IDS['user']}/"), f'upload.create.internal-url[{k}]', str(url))
        url = up.get('foo', '')
        if url:
            check('/_tmp/' in url, 'upload.create.uses-reserved-_tmp', url)
            st, hd, data = http_call('GET', '/v1/proxy/' + url, None, {}, raw=True)
            check(st == 200 and data == PNG, 'proxy.internal-roundtrip', f'{st} len={len(data)}')
            check(hd.get('content-type', '').startswith('image/png'), 'proxy.content-type', hd.get('content-type'))
            st, hd, data = http_call('HEAD', '/v1/proxy/' + url, None, {}, raw=True)
            check(st == 200 and not data, 'proxy.head', f'{st} body={len(data)}B')
            st, hd, data = http_call('GET', '/v1/proxy/' + url, None, {'Range': 'bytes=0-3'}, raw=True)
            check(st == 206 and data == PNG[:4] and hd.get('content-range', '').startswith('bytes 0-3/'), 'proxy.range',
                  f"{st} {hd.get('content-range')} len={len(data)}")
            st, hd, _ = http_call('GET', '/v1/proxy/' + url, None, {'Range': f'bytes={len(PNG) + 10}-'}, raw=True)
            check(st == 416, 'proxy.range-unsatisfiable', f'{st}')
            check('access-control-allow-origin' in hd, 'proxy.cors-header', 'spec: may add Access-Control-Allow-Origin', warn=True)
    st, _, none = http_call('POST', '/v1/upload.create', b'not multipart', {'Content-Type': 'application/json', **AUTH, 'Satori-Platform': IDS['platform'], 'Satori-User-ID': IDS['user']})
    check(st in (400, 415), 'upload.create.bad-body=400', f'got {st}')
    error_shape('upload.create.bad-body', st, none)
else:
    out('INFO', 'upload.create', 'not in features (spec: core SDK provides the default implementation)')
for path, want, name in [('not a url', 400, 'proxy.invalid-url=400'),
                         ('internal:bad', 400, 'proxy.internal-malformed=400'),
                         (f"internal:{IDS['platform']}/nobody-0000/_tmp/x", 404, 'proxy.internal-unknown-login=404'),
                         ('https://example.invalid/x.png', 403, 'proxy.unlisted-http=403')]:
    st, hd, data = http_call('GET', '/v1/proxy/' + path.replace(' ', '%20'), None, AUTH, raw=True)
    check(st == want, name, f'got {st}')
    error_shape(name, st, data)

# ---------------------------------------------------------------- internal / webhook
print('# internal & webhook')
st, _, _ = rpc('internal/definitely-not-a-thing')
check(st in (404, 400), 'internal.unknown=404', f'got {st}', warn=(st == 400))
st, _, _ = http_call('POST', '/v1/meta/webhook.create', b'{}', {'Content-Type': 'application/json', **AUTH})
out('INFO', 'webhook.create-empty', f'{st} (optional feature; 404 if unsupported, 400 if supported)')

# ---------------------------------------------------------------- capabilities
# internal/capabilities 是两个实现端共用的能力声明口径，acumen 连上后按它协商。
print('# capabilities')
st, _, caps = rpc('internal/capabilities')
if check(st == 200 and isinstance(caps, dict), 'capabilities.200', f'{st} {str(caps)[:120]}'):
    check(caps.get('adapter') == login.get('adapter'), 'capabilities.adapter', f"{caps.get('adapter')!r} vs {login.get('adapter')!r}")
    check(caps.get('platform') == login.get('platform'), 'capabilities.platform', f"{caps.get('platform')!r} vs {login.get('platform')!r}")
    check(isinstance(caps.get('version'), str) and caps['version'], 'capabilities.version')
    methods = caps.get('standard_methods')
    if check(isinstance(methods, list) and all(isinstance(m, str) for m in methods), 'capabilities.standard_methods-strings', str(methods)[:80]):
        check(set(methods) == features - {'guild.plain'}, 'capabilities.standard_methods==login.features',
              f'only in capabilities: {sorted(set(methods) - features)}; only in features: {sorted(features - set(methods) - {"guild.plain"})}')
    unsup = caps.get('unsupported')
    if check(isinstance(unsup, list), 'capabilities.unsupported-list'):
        check(not (set(unsup) & features), 'capabilities.unsupported-disjoint-from-features', str(sorted(set(unsup) & features)))
    for key in ('event_types', 'message_elements'):
        check(isinstance(caps.get(key), list) and caps[key] and all(isinstance(v, str) for v in caps[key]), f'capabilities.{key}-strings')
    limits = caps.get('limits')
    if check(isinstance(limits, dict), 'capabilities.limits-object'):
        check(isinstance(limits.get('upload_bytes'), int) and limits['upload_bytes'] > 0, 'capabilities.limits.upload_bytes', repr(limits.get('upload_bytes')))

# ---------------------------------------------------------------- websocket
print('# websocket')
class WS:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=12)
        self.buf = b''
        key = base64.b64encode(os.urandom(16))
        self.s.sendall(b'GET /v1/events HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                       b'Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: ' + key + b'\r\n\r\n')
        head = self._until(b'\r\n\r\n', 12)
        assert b' 101 ' in head.split(b'\r\n')[0], head
    def _fill(self, deadline):
        left = deadline - time.time()
        if left <= 0: raise socket.timeout()
        self.s.settimeout(left)
        d = self.s.recv(65536)
        if not d: raise EOFError('closed')
        self.buf += d
    def _until(self, marker, timeout):
        deadline = time.time() + timeout
        while marker not in self.buf: self._fill(deadline)
        i = self.buf.index(marker) + len(marker)
        out, self.buf = self.buf[:i], self.buf[i:]
        return out
    def _take(self, n, deadline):
        while len(self.buf) < n: self._fill(deadline)
        out, self.buf = self.buf[:n], self.buf[n:]
        return out
    def send(self, obj):
        p = json.dumps(obj).encode(); mask = os.urandom(4)
        n = len(p)
        hdr = bytes([0x81])
        hdr += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack('!H', n)
        self.s.sendall(hdr + mask + bytes(c ^ mask[i % 4] for i, c in enumerate(p)))
    def recv(self, timeout=12):
        deadline = time.time() + timeout
        while True:
            b1, b2 = self._take(2, deadline)
            n = b2 & 127
            if n == 126: n = struct.unpack('!H', self._take(2, deadline))[0]
            elif n == 127: n = struct.unpack('!Q', self._take(8, deadline))[0]
            data = self._take(n, deadline)
            op = b1 & 15
            if op == 8: return 'close', struct.unpack('!H', data[:2])[0] if len(data) >= 2 else None
            if op in (9, 10): continue
            return 'text', json.loads(data)
    def close(self):
        try: self.s.close()
        except OSError: pass

def ident(sn=None, token=args.token):
    w = WS()
    body = {}
    if token: body['token'] = token
    if sn is not None: body['sn'] = sn
    w.send({'op': 3, 'body': body})
    return w

w = ident()
try:
    kind, ready = w.recv()
    if check(kind == 'text' and ready.get('op') == 4, 'ws.ready', str(ready)[:200]):
        body = ready.get('body', {})
        check(isinstance(body.get('logins'), list) and body['logins'], 'ws.ready.logins')
        check(isinstance(body.get('proxy_urls'), list), 'ws.ready.proxy_urls')
        check(not camel_keys(body), 'ws.ready.snake-case', str(camel_keys(body)))
        check(body.get('logins', [{}])[0].get('adapter') == login['adapter'], 'ws.ready.matches-meta')
    w.send({'op': 1, 'body': {}})
    got = None
    deadline = time.time() + 6
    while time.time() < deadline:
        try:
            kind, m = w.recv(timeout=max(0.5, deadline - time.time()))
        except (socket.timeout, EOFError):
            break
        if kind == 'text' and m.get('op') == 2:
            got = m; break
    check(got is not None, 'ws.ping->pong', 'no PONG within 6 s')
finally:
    w.close()

if args.token:
    w = ident(token='y' * len(args.token))
    try:
        kind, m = w.recv(timeout=6)
        check(kind == 'close' or (kind == 'text' and m.get('op') != 4), 'ws.bad-token-rejected', str(m)[:100])
    except (socket.timeout, EOFError):
        out('PASS', 'ws.bad-token-rejected', '')
    finally:
        w.close()

def resume_probe(label, sn):
    w = ident(sn=sn)
    try:
        try:
            kind, m = w.recv(timeout=5)
            desc = f'{kind} ' + (f"op={m.get('op')}" if kind == 'text' else f'code={m}')
        except (socket.timeout, EOFError) as e:
            desc = 'no frame in 5 s'
            kind, m = None, None
        return kind, m, desc
    finally:
        w.close()

kind, m, desc = resume_probe('ancient', 1)
out('INFO', 'ws.resume.ancient-sn=1', desc)
check(kind == 'text' and m.get('op') == 4, 'ws.resume.ancient-sn-still-gets-READY', desc + ' (a client with a stale cursor must be able to reconnect)')
kind, m, desc = resume_probe('future', 2**52)
out('INFO', 'ws.resume.future-sn', desc)
check(kind == 'text' and m.get('op') == 4, 'ws.resume.future-sn-still-gets-READY', desc, warn=True)

if args.slow:
    w = WS()
    t0 = time.time()
    try:
        kind, m = w.recv(timeout=16)
        check(kind == 'close' and 9 <= time.time() - t0 <= 12, 'ws.identify-deadline-10s', f'{kind} {m} after {time.time()-t0:.1f}s')
    except socket.timeout:
        out('FAIL', 'ws.identify-deadline-10s', 'connection stayed open past 16 s')
    except EOFError:
        check(9 <= time.time() - t0 <= 12, 'ws.identify-deadline-10s', f'closed after {time.time()-t0:.1f}s')
    finally:
        w.close()

# ---------------------------------------------------------------- live events
if args.listen:
    print(f'# live events ({args.listen}s)')
    w = ident()
    seen = 0
    try:
        w.recv(timeout=10)
        end = time.time() + args.listen
        last = time.time()
        while time.time() < end:
            try:
                kind, m = w.recv(timeout=max(0.5, min(9, end - time.time())))
            except socket.timeout:
                w.send({'op': 1, 'body': {}}); continue
            if kind != 'text' or m.get('op') != 0:
                continue
            e = m['body']; seen += 1
            if args.dump: print('EVENT', json.dumps(e, ensure_ascii=False))
            t = e.get('type', '')
            check(isinstance(e.get('sn'), int), 'event.sn-int', repr(e.get('sn')))
            check(is_ms(e.get('timestamp')), 'event.timestamp-ms', repr(e.get('timestamp')))
            lg = e.get('login') or {}
            if not t.startswith('login-'):
                check(lg.get('platform') == IDS['platform'] and (lg.get('user') or {}).get('id') == IDS['user'], 'event.login.platform+user', str(lg)[:120])
                check('sn' in lg, 'event.login.sn', str(lg)[:120])
            check(not camel_keys(e), 'event.snake-case', str(camel_keys(e)[:3]))
            msg = e.get('message') or {}
            if t.startswith('message'):
                check(e.get('channel', {}).get('id'), f'{t}.channel-required')
                check(e.get('user', {}).get('id'), f'{t}.user-required')
                check(not any(k in msg for k in ('user', 'channel', 'guild', 'member')), f'{t}.resource-promotion', f'message still nests {[k for k in ("user","channel","guild","member") if k in msg]}')
                check(isinstance(msg.get('id'), str), f'{t}.message.id-string')
                check(isinstance(msg.get('content'), str) or t == 'message-deleted', f'{t}.message.content-string')
            # resource.md: a link tied to this server's own address breaks the moment the client is elsewhere.
            # Media in received messages should be internal: links (or a public CDN), never loopback/bind address.
            local = re.findall(r'src="(https?://(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1?\])[^"]*)"', msg.get('content') or '')
            check(not local, f'{t}.media-not-bound-to-server-address', local[0][:100] if local else '')
            out('INFO', 'event', f"{t} sn={e.get('sn')} keys={sorted(k for k in e if k not in ('sn','type','timestamp','login'))}")
    finally:
        w.close()
    if not seen: out('INFO', 'events', 'none arrived in the window')

print(f"\n{counts.get('PASS',0)} pass, {counts.get('WARN',0)} warn, {counts.get('FAIL',0)} fail")
sys.exit(counts.get('FAIL', 0))
