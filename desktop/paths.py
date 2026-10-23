"""路径处理：key 校验（防穿越）与同名冲突的确定性改名算法。

规则见 docs/PROTOCOL.md，两端共同遵守。
"""
import re
from pathlib import Path

_MAX_SEG = 128
_ILLEGAL = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def sanitize_segment(name: str) -> str:
    """清洗 Windows 非法字符（设备名等也走这里）。"""
    s = _ILLEGAL.sub('_', name).strip().rstrip('. ')
    return s[:_MAX_SEG] or '_'


def parse_key(key: str) -> list[str]:
    """校验 key 并返回段列表；不合法抛 ValueError。"""
    if not key:
        raise ValueError('key 为空')
    segs = []
    for seg in key.split('/'):
        if not seg or seg in ('.', '..'):
            raise ValueError(f'非法路径段: {seg!r}')
        if ':' in seg or any(ord(c) < 0x20 for c in seg) or len(seg) > _MAX_SEG:
            raise ValueError(f'非法路径段: {seg!r}')
        segs.append(seg)
    return segs


def safe_join(root: Path, segs: list[str]) -> Path:
    """拼接并确保结果仍在 root 内（双保险）。"""
    root = root.resolve()
    p = root.joinpath(*segs).resolve()
    if p == root or root not in p.parents:
        raise ValueError('路径越界')
    return p


def resolve_final(root: Path, key: str, size: int, mid: int) -> tuple[Path, bool]:
    """返回 (正式文件路径, 是否去重命中)。

    候选：原名 → 原名_m<mid> → 原名_m<mid>_2 …
    第一个「存在且大小==size」返回 True；否则第一个不存在的路径返回 False，
    绝不覆盖大小不同的同名文件。
    """
    base = safe_join(root, parse_key(key))
    stem, ext = base.stem, base.suffix
    candidates = [base, base.with_name(f'{stem}_m{mid}{ext}')]
    candidates += [base.with_name(f'{stem}_m{mid}_{n}{ext}') for n in range(2, 100)]
    for p in candidates:
        if p.exists() and p.stat().st_size == size:
            return p, True
    for p in candidates:
        if not p.exists():
            return p, False
    raise RuntimeError('同名冲突候选耗尽')
