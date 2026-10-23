"""M1 验收脚本：自建临时服务实例，跑 9 组用例（纯 stdlib）。

用法：python test_client.py
用例：
 1. 令牌正确 ping → 200；令牌错误 → 401
 2. 正常上传 10MB → 200，正式文件存在，无 .part 残留
 3. 上传一半强断连接 → 服务器删除 .part，无残留
 4. 路径穿越 key → 400
 5. 磁盘保留空间不足 → 507（独立实例，避免影响后续用例）
 6. 同名同大小同 mid：/status exists=true；重复 PUT 去重不产生第二份
 7. 同名不同大小不同 mid → 产生 _m<mid> 新版本，原文件不覆盖
 8. 两部手机（不同令牌）→ 各自目录互不干扰
 9. 启动清理：遗留 .part 被清除
"""
import os
import socket
import sys
import tempfile
import threading
import time
import urllib.parse
import http.client
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from receiver import ReceiverServer  # noqa: E402

PASS, FAIL = [], []
DATA = os.urandom(10 * 1024 * 1024)      # 10MB 随机数据
SMALL = os.urandom(64 * 1024)            # 64KB，用于去重/冲突用例


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(f'[{"PASS" if cond else "FAIL"}] {name}' + (f'  {detail}' if not cond else ''))


def make_server(save_root: Path, reserve_mb: int):
    cfg = {
        'port': 0,
        'save_root': str(save_root),
        'reserve_mb': reserve_mb,
        'devices': {'手机A': 'a' * 32, '手机B': 'b' * 32},
    }
    srv = ReceiverServer(cfg, None)
    port = srv.server_address[1]          # 服务端绑 0.0.0.0，客户端连 127.0.0.1
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, cfg, '127.0.0.1', port


def http_req(host, port, method, path, token, body=b'', timeout=10):
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    headers = {'X-Token': token, 'Content-Length': str(len(body))}
    conn.request(method, path, body=body, headers=headers)
    r = conn.getresponse()
    data = r.read()
    conn.close()
    return r.status, data


def q(**kw):
    return urllib.parse.urlencode(kw)


def upload_key(key, total, mid=0):
    return f'/upload?{q(key=key, total=total, mid=mid)}'


def wait_no_part(root: Path, seconds=5):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if not list(root.rglob('*.part')):
            return True
        time.sleep(0.2)
    return False


def main():
    tmp = Path(tempfile.mkdtemp(prefix='psync_test_'))
    print(f'临时保存目录: {tmp}')
    srv, cfg, host, port = make_server(tmp, reserve_mb=0)
    tokA, tokB = cfg['devices']['手机A'], cfg['devices']['手机B']
    rootA, rootB = tmp / '手机A', tmp / '手机B'
    key = 'Camera/2026/10/IMG_0001.jpg'

    # --- 1. ping / 401 ---
    st, body = http_req(host, port, 'GET', '/ping', tokA)
    check('1a 正确令牌 ping → 200', st == 200, f'got {st}')
    st, _ = http_req(host, port, 'GET', '/ping', 'x' * 32)
    check('1b 错误令牌 ping → 401', st == 401, f'got {st}')

    # --- 2. 正常上传 10MB ---
    st, body = http_req(host, port, 'PUT', upload_key(key, len(DATA), mid=7), tokA, DATA)
    f = rootA / 'Camera/2026/10/IMG_0001.jpg'
    check('2a 上传 10MB → 200', st == 200, f'got {st} {body!r}')
    check('2b 正式文件存在且大小正确', f.exists() and f.stat().st_size == len(DATA))
    check('2c 无 .part 残留', not list(tmp.rglob('*.part')))

    # --- 3. 中断上传 ---
    s = socket.create_connection((host, port), timeout=5)
    req = (f'PUT {upload_key("Camera/2026/10/BROKEN.mp4", len(DATA), mid=8)} HTTP/1.1\r\n'
           f'Host: t\r\nX-Token: {tokA}\r\nContent-Length: {len(DATA)}\r\n\r\n').encode()
    s.sendall(req)
    s.sendall(DATA[:len(DATA) // 2])
    s.close()
    time.sleep(1.0)
    check('3 中断上传后无 .part / 无正式文件',
          wait_no_part(tmp) and not (rootA / 'Camera/2026/10/BROKEN.mp4').exists())

    # --- 4. 路径穿越 ---
    st, _ = http_req(host, port, 'PUT', upload_key('../evil/x.jpg', 2), tokA, b'zz')
    check('4a 上传路径穿越 → 400', st == 400, f'got {st}')
    st, _ = http_req(host, port, 'GET',
                     f'/status?{q(key="../evil/x.jpg", size=1, mid=1)}', tokA)
    check('4b status 路径穿越 → 400', st == 400, f'got {st}')

    # --- 6. 去重（用 64KB 小文件，避免服务器不读 body 时的缓冲问题）---
    skey = 'Camera/2026/10/SMALL.jpg'
    st, _ = http_req(host, port, 'PUT', upload_key(skey, len(SMALL), mid=3), tokA, SMALL)
    check('6a 上传 SMALL → 200', st == 200)
    st, body = http_req(host, port, 'GET',
                        f'/status?{q(key=skey, size=len(SMALL), mid=3)}', tokA)
    check('6b /status 已传文件 exists=true', st == 200 and b'"exists": true' in body,
          f'got {st} {body!r}')
    st, body = http_req(host, port, 'PUT', upload_key(skey, len(SMALL), mid=3), tokA, SMALL)
    n = len(list((rootA / 'Camera/2026/10').glob('SMALL*')))
    check('6c 重复 PUT → 200 去重，目录仍只有一个文件', st == 200 and n == 1,
          f'got {st}, files={n}')

    # --- 7. 同名不同大小（编辑后的新版本）---
    edited = os.urandom(1024)
    st, _ = http_req(host, port, 'PUT', upload_key(skey, len(edited), mid=9), tokA, edited)
    f2 = rootA / 'Camera/2026/10/SMALL_m9.jpg'
    check('7a 编辑后重传 → 另存 _m<mid>，原文件不覆盖',
          st == 200 and f2.exists() and f2.stat().st_size == 1024
          and (rootA / 'Camera/2026/10/SMALL.jpg').stat().st_size == len(SMALL),
          f'got {st}, m9={f2.exists()}')
    st, _ = http_req(host, port, 'PUT', upload_key(skey, len(edited), mid=9), tokA, edited)
    check('7b 重传同版本仍去重（不产生第三份）',
          st == 200 and len(list((rootA / 'Camera/2026/10').glob('SMALL*'))) == 2)

    # --- 8. 两部手机互不覆盖 ---
    st, _ = http_req(host, port, 'PUT', upload_key('Camera/2026/10/SAME.jpg',
                                                   len(SMALL), mid=1), tokB, SMALL)
    check('8 手机B 独立目录', st == 200 and (rootB / 'Camera/2026/10/SAME.jpg').exists()
          and not (rootA / 'Camera/2026/10/SAME.jpg').exists())

    # --- 5. 磁盘空间不足（独立实例 + 小 body：服务器收流前即拒绝断开）---
    srv3, cfg3, host3, port3 = make_server(tmp, reserve_mb=99999999)    # ~95TB
    st, body = http_req(host3, port3, 'PUT', upload_key('Camera/2026/10/BIG.mp4', 2),
                        cfg3['devices']['手机A'], b'zz')
    check('5 保留空间不足 → 507', st == 507, f'got {st} {body!r}')
    check('5b 拒绝后无 .part 残留', not list(tmp.rglob('*.part')))
    srv3.shutdown()
    srv3.server_close()

    # --- 9. 启动清理 ---
    stray = rootA / 'Camera/2026/10/STRAY.jpg.abc123.part'
    stray.write_bytes(b'half')
    n = srv.cleanup_parts()
    check('9 启动清理遗留 .part', n == 1 and not stray.exists(), f'n={n}')

    # --- 10. 自动发现广播 ---
    import socket as _sock
    import json as _json
    from discovery import Announcer
    cfg10 = {'port': 43210, 'devices': {'测试X': 'c' * 32}}
    ann = Announcer(cfg10)
    ann.start()
    listener = _sock.socket(_sock.AF_INET, _sock.SOCK_DGRAM)
    listener.settimeout(4)
    listener.bind(('0.0.0.0', 52000))
    got = None
    try:
        for _ in range(5):
            data, _ = listener.recvfrom(2048)
            o = _json.loads(data)
            if o.get('port') == 43210:          # 区分本机其他实例的广播
                got = o
                break
    except _sock.timeout:
        pass
    finally:
        listener.close()
        ann.stop()
    check('10 自动发现广播（app/hostname/port/devices）',
          got is not None and got.get('app') == 'photo-sync-receiver'
          and '测试X' in got.get('devices', {}))

    srv.shutdown()
    srv.server_close()
    print(f'\n结果: {len(PASS)} 通过, {len(FAIL)} 失败')
    if FAIL:
        print('失败用例:', ', '.join(FAIL))
        sys.exit(1)


if __name__ == '__main__':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except (AttributeError, OSError):
        pass
    main()
