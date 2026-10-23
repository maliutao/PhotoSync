"""电脑端入口：加载配置 → 启动接收服务 → 打开界面。

用法：
  python server.py            # 带界面运行
  python server.py --cli      # 无界面运行（调试/联调，Ctrl+C 退出）
  python server.py --port 9000
"""
import argparse
import queue
import sys
import threading
from pathlib import Path

import config_store
from discovery import Announcer
from receiver import ReceiverServer


def _utf8_console():
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except (AttributeError, OSError):
        pass


def main():
    _utf8_console()
    ap = argparse.ArgumentParser()
    ap.add_argument('--cli', action='store_true', help='无界面运行')
    ap.add_argument('--port', type=int, default=None, help='覆盖配置中的端口')
    args = ap.parse_args()

    cfg = config_store.load()
    if args.port:
        cfg['port'] = args.port
    Path(cfg['save_root']).mkdir(parents=True, exist_ok=True)

    events = queue.Queue()
    server = ReceiverServer(cfg, events)
    server.cleanup_parts()
    threading.Thread(target=server.serve_forever, daemon=True).start()
    Announcer(cfg).start()          # 局域网自动发现广播

    print(f'接收服务运行中: 0.0.0.0:{cfg["port"]}  保存目录: {cfg["save_root"]}')
    for name, token in cfg['devices'].items():
        print(f'  令牌 {name}: {token}')

    if args.cli:
        try:
            threading.Event().wait()
        except KeyboardInterrupt:
            pass
        return

    import tkinter as tk
    from ui import ReceiverUI
    root = tk.Tk()
    ReceiverUI(root, cfg, server, events)
    root.mainloop()


if __name__ == '__main__':
    main()
