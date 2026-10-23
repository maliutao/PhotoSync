"""配置读写：config.json 保存在 exe（或脚本）旁。

{
  "port": 8765,
  "save_root": "D:/PhotoBackup",
  "reserve_mb": 5120,
  "devices": {"手机A": "<32位hex>", "手机B": "<32位hex>"}
}
"""
import json
import os
import secrets
import sys
from pathlib import Path

DEFAULT_PORT = 8765
DEFAULT_RESERVE_MB = 5120


def config_path() -> Path:
    if getattr(sys, 'frozen', False):          # PyInstaller 打包后
        return Path(sys.executable).parent / 'config.json'
    return Path(__file__).resolve().parent / 'config.json'


def _new_token() -> str:
    return secrets.token_hex(16)


def _default_cfg() -> dict:
    return {
        'port': DEFAULT_PORT,
        'save_root': str(Path.home() / 'PhotoBackup'),
        'reserve_mb': DEFAULT_RESERVE_MB,
        'devices': {'手机A': _new_token(), '手机B': _new_token()},
    }


def load() -> dict:
    p = config_path()
    cfg = None
    if p.exists():
        try:
            with open(p, encoding='utf-8') as f:
                cfg = json.load(f)
        except (json.JSONDecodeError, OSError):
            cfg = None          # 配置损坏则重建
    if not cfg:
        cfg = _default_cfg()
        save(cfg)
    # 兜底：字段缺失时补齐
    d = _default_cfg()
    for k, v in d.items():
        cfg.setdefault(k, v)
        if isinstance(v, dict) and not cfg[k]:
            cfg[k] = v
    return cfg


def save(cfg: dict) -> None:
    p = config_path()
    tmp = p.with_name(p.name + '.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, p)          # 原子落盘
