"""局域网自动发现：每 2 秒向 UDP 广播端口广播本机接收服务信息。

手机端监听同一端口即可列出局域网内所有正在运行的接收软件，
选中后自动填入 IP/端口/令牌，免手动配置（见 docs/PROTOCOL.md）。
"""
import json
import socket
import threading

from netinfo import interface_broadcasts

DISCOVERY_PORT = 52000
INTERVAL = 2.0


class Announcer(threading.Thread):
    daemon = True

    def __init__(self, cfg: dict):
        super().__init__(name='discovery-announcer')
        self.cfg = cfg          # 端口可能被 UI 热修改，每次广播实时读取
        self._stop = threading.Event()

    def run(self):
        while not self._stop.wait(INTERVAL):
            try:
                self._announce()
            except OSError:
                pass

    def _announce(self):
        msg = json.dumps({
            'app': 'photo-sync-receiver',
            'version': 1,
            'hostname': socket.gethostname(),
            'port': int(self.cfg['port']),
            'devices': dict(self.cfg['devices']),
        }, ensure_ascii=False).encode('utf-8')
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            # 全局广播 + 每个接口的定向广播（Windows 的全局广播可能跳过
            # 某些网卡，实测会漏掉 WLAN，定向广播保证覆盖）
            targets = [('255.255.255.255', DISCOVERY_PORT)]
            targets += [(b, DISCOVERY_PORT) for b in interface_broadcasts()]
            for t in targets:
                try:
                    s.sendto(msg, t)
                except OSError:
                    pass
        finally:
            s.close()

    def stop(self):
        self._stop.set()
