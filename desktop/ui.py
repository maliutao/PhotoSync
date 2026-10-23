"""tkinter 界面：状态/端口/本机 IP/保存目录/令牌/磁盘空间/最近接收。

线程模型：HTTP handler 线程绝不碰 tk 控件，只往 queue 塞事件；
UI 主线程 root.after(800) 轮询消费（tkinter 非线程安全）。
"""
import queue
import threading
import time
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog

import config_store
from netinfo import local_ipv4s, disk_free
from receiver import ReceiverServer


def _fmt_size(n) -> str:
    if n is None or n < 0:
        return '未知'
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if n < 1024 or unit == 'TB':
            return f'{n:.1f}{unit}' if unit != 'B' else f'{n}B'
        n /= 1024
    return f'{n:.1f}TB'


class ReceiverUI:
    def __init__(self, root: tk.Tk, cfg: dict, server: ReceiverServer, events: queue.Queue):
        self.root = root
        self.cfg = cfg
        self.server = server
        self.events = events
        self.running = True

        root.title('相册同步 - 电脑端接收')
        root.geometry('620x560')
        root.minsize(560, 500)
        root.protocol('WM_DELETE_WINDOW', self._on_close)

        pad = {'padx': 10, 'pady': 4}
        main = ttk.Frame(root, padding=8)
        main.pack(fill='both', expand=True)

        # --- 状态 + 端口 ---
        row = ttk.Frame(main)
        row.pack(fill='x', **pad)
        self.status_var = tk.StringVar(value='运行中')
        self.status_label = tk.Label(row, textvariable=self.status_var,
                                     fg='green', font=('', 12, 'bold'))
        self.status_label.pack(side='left')
        ttk.Label(row, text='端口').pack(side='left', padx=(16, 2))
        self.port_var = tk.StringVar(value=str(cfg['port']))
        ttk.Entry(row, textvariable=self.port_var, width=8).pack(side='left')
        ttk.Button(row, text='重启服务', command=self._restart_server).pack(side='left', padx=8)

        # --- 本机 IP ---
        row = ttk.Frame(main)
        row.pack(fill='x', **pad)
        ttk.Label(row, text='本机 IP（手机填与手机同网段的那个）:').pack(side='left')
        ttk.Button(row, text='刷新', command=self._refresh_ips).pack(side='right')
        self.ip_var = tk.StringVar(value='')
        ttk.Label(main, textvariable=self.ip_var, foreground='#555').pack(
            fill='x', padx=14, anchor='w')

        # --- 保存目录 + 保留空间 ---
        row = ttk.Frame(main)
        row.pack(fill='x', **pad)
        ttk.Label(row, text='保存目录:').pack(side='left')
        self.dir_var = tk.StringVar(value=cfg['save_root'])
        ttk.Label(row, textvariable=self.dir_var, foreground='#555').pack(
            side='left', padx=6)
        ttk.Button(row, text='选择目录', command=self._choose_dir).pack(side='right')
        row = ttk.Frame(main)
        row.pack(fill='x', **pad)
        ttk.Label(row, text='保留空间(MB):').pack(side='left')
        self.reserve_var = tk.StringVar(value=str(cfg['reserve_mb']))
        ttk.Spinbox(row, from_=0, to=1024 * 1024, increment=512,
                    textvariable=self.reserve_var, width=10,
                    command=self._save_reserve).pack(side='left', padx=6)
        self.disk_var = tk.StringVar(value='')
        self.disk_label = ttk.Label(row, textvariable=self.disk_var)
        self.disk_label.pack(side='right')

        # --- 设备令牌 ---
        box = ttk.LabelFrame(main, text='配对令牌（填到手机 App「设置」中）', padding=6)
        box.pack(fill='x', **pad)
        for name, token in cfg['devices'].items():
            r = ttk.Frame(box)
            r.pack(fill='x', pady=2)
            ttk.Label(r, text=name, width=8).pack(side='left')
            e = ttk.Entry(r)
            e.insert(0, token)
            e.configure(state='readonly')
            e.pack(side='left', fill='x', expand=True, padx=4)
            ttk.Button(r, text='复制',
                       command=lambda t=token: self._copy(t)).pack(side='left')

        # --- 最近接收 ---
        box = ttk.LabelFrame(main, text='最近接收', padding=6)
        box.pack(fill='both', expand=True, **pad)
        cols = ('时间', '设备', '文件', '大小')
        self.tree = ttk.Treeview(box, columns=cols, show='headings', height=10)
        for c, w in zip(cols, (80, 60, 360, 80)):
            self.tree.heading(c, text=c)
            self.tree.column(c, width=w, anchor='w')
        self.tree.pack(fill='both', expand=True)
        self.total_var = tk.StringVar(value='累计接收: 0 个文件')
        ttk.Label(main, textvariable=self.total_var).pack(anchor='w', padx=14)

        self._refresh_ips()
        root.after(800, self._poll)
        root.after(15000, self._slow_poll)

    # ---------- 交互 ----------
    def _copy(self, text: str):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)

    def _refresh_ips(self):
        self.ip_var.set('  '.join(local_ipv4s()) or '（未找到，请检查网络）')

    def _choose_dir(self):
        d = filedialog.askdirectory(initialdir=self.cfg['save_root'])
        if not d:
            return
        self.cfg['save_root'] = d
        config_store.save(self.cfg)
        self.dir_var.set(d)
        Path(d).mkdir(parents=True, exist_ok=True)

    def _save_reserve(self):
        try:
            self.cfg['reserve_mb'] = int(self.reserve_var.get())
        except ValueError:
            return
        config_store.save(self.cfg)

    def _restart_server(self):
        try:
            new_port = int(self.port_var.get())
        except ValueError:
            self.status_var.set('端口无效')
            return
        self.server.shutdown()
        self.server.server_close()
        self.cfg['port'] = new_port
        config_store.save(self.cfg)
        Path(self.cfg['save_root']).mkdir(parents=True, exist_ok=True)
        self.server = ReceiverServer(self.cfg, self.events)
        self.server.cleanup_parts()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def _on_close(self):
        self.running = False
        try:
            self.server.shutdown()
            self.server.server_close()
        finally:
            self.root.destroy()

    # ---------- 轮询 ----------
    def _poll(self):
        """消费接收事件 + 刷新磁盘剩余。"""
        if not self.running:
            return
        while True:
            try:
                ev = self.events.get_nowait()
            except queue.Empty:
                break
            if ev.get('type') == 'received':
                t = time.strftime('%H:%M:%S', time.localtime(ev.get('time', time.time())))
                self.tree.insert('', 0, values=(
                    t, ev['device'], ev['path'], _fmt_size(ev['size'])))
        with self.server.stats.lock:
            self.total_var.set(f'累计接收: {self.server.stats.total} 个文件')
        root_save = Path(self.cfg['save_root'])
        free = disk_free(root_save)
        reserve = int(self.cfg['reserve_mb']) * 1024 * 1024
        text = f'磁盘剩余: {_fmt_size(free)}'
        self.disk_var.set(text)
        if 0 <= free < reserve:
            self.disk_label.configure(foreground='red')
            self.status_var.set('空间不足：低于保留值，不再接收新文件')
            self.status_label.configure(fg='red')
        else:
            self.disk_label.configure(foreground='')
        self.root.after(800, self._poll)

    def _slow_poll(self):
        """IP 列表与运行状态的低频刷新。"""
        if not self.running:
            return
        self._refresh_ips()
        self.root.after(15000, self._slow_poll)
