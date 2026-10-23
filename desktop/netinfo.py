"""本机网络与磁盘信息。"""
import re
import shutil
import socket
import subprocess
from pathlib import Path


def local_ipv4s() -> list[str]:
    """枚举本机所有 IPv4（排除回环）。多网卡时全列，由用户选与手机同网段的。"""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith('127.'):
                ips.add(ip)
    except socket.gaierror:
        pass
    return sorted(ips)


_IP_RE = re.compile(r'(?<!\d)(\d{1,3}(?:\.\d{1,3}){3})(?!\d)')
# 无窗口 exe 中调用控制台程序(ipconfig)会闪黑窗，必须禁止创建窗口
_NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


def interface_broadcasts() -> list[str]:
    """各 IPv4 接口的定向广播地址（如 172.20.9.255）。

    Windows 对 255.255.255.255 全局广播可能只从部分网卡发出（实测跳过 WLAN），
    向每个接口的子网定向广播各发一份可保证覆盖。按数值模式解析 ipconfig，
    与界面语言无关；非 Windows 无 ipconfig 时返回空。
    """
    try:
        out = subprocess.run(
            ['ipconfig'], capture_output=True, timeout=5, text=True, errors='replace',
            creationflags=_NO_WINDOW
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    lines = out.splitlines()
    result = []
    for i, line in enumerate(lines):
        m = _IP_RE.search(line)
        if not m or m.group(1).startswith('255.'):
            continue
        ip = m.group(1)
        mask = None
        for j in range(i + 1, min(i + 4, len(lines))):   # 掩码行紧随地址行
            mm = _IP_RE.search(lines[j])
            if mm and mm.group(1).startswith('255.'):
                mask = mm.group(1)
                break
        if mask is None:
            continue
        try:
            ip_b = [int(x) for x in ip.split('.')]
            mask_b = [int(x) for x in mask.split('.')]
        except ValueError:
            continue
        if any(v > 255 for v in ip_b + mask_b):
            continue
        bc = '.'.join(str(b | (255 - m)) for b, m in zip(ip_b, mask_b))
        if bc != '255.255.255.255':
            result.append(bc)
    return list(dict.fromkeys(result))


def disk_free(path: Path) -> int:
    """path 所在磁盘剩余字节；目录可能不存在则向上找最近的祖先。失败返回 -1。"""
    p = Path(path)
    while not p.exists() and p != p.parent:
        p = p.parent
    try:
        return shutil.disk_usage(p).free
    except OSError:
        return -1
