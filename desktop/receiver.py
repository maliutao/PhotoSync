"""HTTP 接收服务：GET /ping、GET /status、PUT /upload。

核心语义见 docs/PROTOCOL.md：
- 令牌鉴权（X-Token → 设备名，决定子目录）
- .part 临时文件 + 原子改名，任何失败路径删除临时文件，启动时清理遗留 .part
- 30 秒无数据超时（read1 不预读，持续有数据永不超时）
- 磁盘保留空间检查，不足返回 507
"""
import json
import os
import secrets
import shutil
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

from paths import sanitize_segment, resolve_final
from netinfo import disk_free

RECV_TIMEOUT = 30              # 连续 30 秒无任何字节 → 判定失败
CHUNK = 64 * 1024


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.total = 0
        self.recent = deque(maxlen=200)     # (时间戳, 设备, 相对路径, 字节)

    def record(self, device: str, rel: str, size: int):
        with self.lock:
            self.total += 1
            self.recent.append((time.time(), device, rel, size))


def device_root(save_root: Path, device: str) -> Path:
    return save_root / sanitize_segment(device)


class ReceiverServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, cfg: dict, events=None):
        self.cfg = cfg                      # UI 会原地修改并持久化，请求时实时读取
        self.events = events                # queue.Queue，供 UI 消费；可为 None
        self.stats = Stats()
        super().__init__(('0.0.0.0', int(cfg['port'])), Handler)

    def cleanup_parts(self) -> int:
        """启动清理：删除保存目录下所有遗留 .part（PRD F7）。"""
        root = Path(self.cfg['save_root'])
        if not root.exists():
            return 0
        n = 0
        for p in root.rglob('*.part'):
            try:
                p.unlink()
                n += 1
            except OSError:
                pass
        return n

    def handle_error(self, request, client_address):
        """静音 socketserver 的默认 traceback（对端中途断开属正常路径）。"""
        pass


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    timeout = RECV_TIMEOUT     # 头部阶段读超时（StreamRequestHandler.setup 生效）

    # ---------- 基础 ----------
    @property
    def cfg(self):
        return self.server.cfg

    def _device(self):
        tok = self.headers.get('X-Token', '')
        for name, t in self.cfg['devices'].items():
            if tok == t:
                return name
        return None

    def _send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        try:
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            if self.close_connection:
                self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            # 对端已断开（上传中途取消/断网），响应写不进去；忽略即可，
            # .part 的清理由 finally 保证
            self.close_connection = True

    def log_message(self, fmt, *args):
        pass                              # 静音 stderr，事件走 UI

    # ---------- 路由 ----------
    def do_GET(self):
        u = urlparse(self.path)
        device = self._device()
        if device is None:
            self._send_json(401, {'error': 'bad token'})
            return
        if u.path == '/ping':
            self._send_json(200, {'app': 'photo-sync-receiver', 'version': 1})
            return
        if u.path == '/status':
            self._do_status(device, parse_qs(u.query))
            return
        self._send_json(404, {'error': 'not found'})

    def _do_status(self, device, q):
        try:
            key = q['key'][0]
            size = int(q['size'][0])
            mid = int(q.get('mid', ['0'])[0])
        except (KeyError, IndexError, ValueError):
            self._send_json(400, {'error': 'bad params'})
            return
        root = Path(self.cfg['save_root'])
        try:
            final, hit = resolve_final(device_root(root, device), key, size, mid)
        except ValueError as e:
            self._send_json(400, {'error': str(e)})
            return
        reserve = int(self.cfg['reserve_mb']) * 1024 * 1024
        self._send_json(200, {
            'exists': hit,
            'free': disk_free(root),
            'reserve': reserve,
        })

    def do_PUT(self):
        u = urlparse(self.path)
        device = self._device()
        if device is None:
            self._send_json(401, {'error': 'bad token'})
            return
        if u.path != '/upload':
            self._send_json(404, {'error': 'not found'})
            return
        q = parse_qs(u.query)
        try:
            key = q['key'][0]
            total = int(q['total'][0])
            mid = int(q.get('mid', ['0'])[0])
        except (KeyError, IndexError, ValueError):
            self._send_json(400, {'error': 'bad params'})
            return
        if int(self.headers.get('Content-Length', '-1')) != total:
            self._send_json(400, {'error': 'content-length mismatch'})
            return

        root = Path(self.cfg['save_root'])
        droot = device_root(root, device)

        try:
            final, hit = resolve_final(droot, key, total, mid)
        except ValueError as e:
            self._send_json(400, {'error': str(e)})
            return

        rel = str(final.relative_to(root))
        if hit:
            # 已有同名同大小完整文件：不写盘、不读 body，返回去重结果
            self.close_connection = True
            self._send_json(200, {'saved_as': rel, 'dedup': True})
            return

        # 接收前磁盘空间检查（PRD F6；去重命中不占新空间，先于本检查）
        reserve = int(self.cfg['reserve_mb']) * 1024 * 1024
        free = disk_free(root)
        if free >= 0 and free < total + reserve:
            self.close_connection = True   # body 不读，直接断开
            self._send_json(507, {'error': 'insufficient storage'})
            return

        final.parent.mkdir(parents=True, exist_ok=True)
        part = final.parent / f'{final.name}.{secrets.token_hex(3)}.part'
        try:
            self.connection.settimeout(RECV_TIMEOUT)
            remaining = total
            with open(part, 'wb') as f:
                while remaining > 0:
                    chunk = self.rfile.read1(min(CHUNK, remaining))
                    if not chunk:                       # 对端提前断开 → 视为中断
                        raise ConnectionError('client closed early')
                    f.write(chunk)
                    remaining -= len(chunk)
                f.flush()
                os.fsync(f.fileno())
            if part.stat().st_size != total:            # 保险
                raise ConnectionError('size mismatch after receive')
            os.replace(part, final)                     # 原子改名 → 正式文件
            self.server.stats.record(device, rel, total)
            if self.server.events:
                self.server.events.put(
                    {'type': 'received', 'device': device, 'path': rel, 'size': total})
            self._send_json(200, {'saved_as': rel, 'dedup': False})
        except TimeoutError:                            # socket.timeout == TimeoutError (3.10+)
            self.close_connection = True
            self._send_json(504, {'error': 'receive timeout'})
        except ConnectionError:
            self.close_connection = True
            self._send_json(500, {'error': 'incomplete upload'})
        except OSError as e:
            self.close_connection = True
            if e.errno in (28, 112) or 'disk' in str(e).lower():    # ENOSPC
                self._send_json(507, {'error': 'disk full'})
            else:
                self._send_json(500, {'error': str(e)})
        finally:
            part.unlink(missing_ok=True)                # 所有失败路径统一删 .part
