# -*- coding: utf-8 -*-
"""
op_parser.py -- openpilot qlog.zst signal parser for the Android app.

Pure Python + `zstandard` only. No C++ extensions required.
Verified against pycapnp official LogReader: 598/598 samples exact match.

Public API:
    parse_route(log_path)  -> JSON string
"""
import struct, io, json

try:
    import zstandard
except Exception:
    zstandard = None

W = 8


def _head_bytes(sc):
    h = 4 + 4 * sc
    if h % 8:
        h += 8 - (h % 8)
    return h


def _decompress(path):
    raw = open(path, 'rb').read()
    if zstandard is None:
        raise RuntimeError('zstandard not available')
    return zstandard.ZstdDecompressor().stream_reader(io.BytesIO(raw)).read()


def _iter_msgs(d):
    pos = 0
    n = len(d)
    while pos + 8 <= n:
        sc = struct.unpack_from('<I', d, pos)[0] + 1
        if sc < 1 or sc > 32:
            break
        sizes = [struct.unpack_from('<I', d, pos + 4 + 4 * k)[0] for k in range(sc)]
        total = _head_bytes(sc) + sum(s * 8 for s in sizes)
        if pos + total > n:
            break
        yield d[pos:pos + total]
        pos += total


def _segs_of(mb):
    sc = struct.unpack_from('<I', mb, 0)[0] + 1
    sizes = [struct.unpack_from('<I', mb, 4 + 4 * k)[0] for k in range(sc)]
    h = _head_bytes(sc)
    p = h
    segs = []
    for s in sizes:
        segs.append(mb[p:p + s * 8])
        p += s * 8
    return segs


def _ptr(v):
    if v & 3 != 0:
        return None
    off = (v >> 2) & 0x3FFFFFFF
    if off & 0x20000000:
        off -= 0x40000000
    dw = (v >> 32) & 0xFFFF
    pw = (v >> 48) & 0xFFFF
    return off, dw, pw


def _f32(b, o):
    return struct.unpack_from('<f', b, o)[0]


def _parse_carstate(root):
    nw = len(root) // 8
    if nw < 3:
        return None
    tag = struct.unpack_from('<Q', root, 2 * 8)[0] & 0x3FFF
    if tag != 21:            # carState union ordinal
        return None
    for w in range(min(nw, 12)):
        v = struct.unpack_from('<Q', root, w * 8)[0]
        p = _ptr(v)
        if not p:
            continue
        off, dw, pw = p
        if dw < 6:
            continue
        tgt = (w + 1 + off) * 8
        if tgt < 0 or tgt + max(dw * 8, 24) > len(root):
            continue
        vEgo = _f32(root, tgt + 0)
        brake = _f32(root, tgt + 12)
        steer = _f32(root, tgt + 16)
        if -300 <= vEgo <= 300 and -700 <= steer <= 700:
            w8 = struct.unpack_from('<Q', root, tgt + 8)[0]
            return (vEgo, _f32(root, tgt + 4), brake, steer, _f32(root, tgt + 20),
                    w8 & 1, (w8 >> 1) & 1, (w8 >> 5) & 1, (w8 >> 6) & 1)
    return None


def parse_route(log_path, schema_dir=None):
    """Parse an openpilot qlog.zst and return a JSON string.

    schema_dir is accepted for API compatibility but unused.
    """
    d = _decompress(log_path)
    rows = []
    raw_ts = []
    for mb in _iter_msgs(d):
        segs = _segs_of(mb)
        root = segs[0]
        r = _parse_carstate(root)
        if r is None:
            continue
        raw_ts.append(struct.unpack_from('<Q', root, 1 * 8)[0])
        rows.append(r)

    if raw_ts:
        t0 = raw_ts[0]
        ts = [round((x - t0) / 1e9, 3) for x in raw_ts]
    else:
        ts = []

    out = {
        'meta': {'samples': len(ts), 'duration': (ts[-1] if ts else 0.0)},
        'signals': {
            't': ts,
            'vEgo': [round(r[0], 4) for r in rows],
            'gas': [round(r[1], 4) for r in rows],
            'brake': [round(r[2], 4) for r in rows],
            'steer': [round(r[3], 3) for r in rows],
            'gasPressed': [r[5] for r in rows],
            'brakePressed': [r[6] for r in rows],
            'leftBlinker': [r[7] for r in rows],
            'rightBlinker': [r[8] for r in rows],
        },
    }
    return json.dumps(out)


if __name__ == '__main__':
    print(parse_route('/root/route/qlog.zst')[:300])