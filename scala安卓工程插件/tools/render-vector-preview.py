#!/usr/bin/env python3
"""把 Android VectorDrawable（仅 M/L/H/V/C/Z 子集）光栅化成 PNG，用来肉眼检查图标。

不依赖 PIL：自己解析 pathData（含相对指令与三次贝塞尔展开），
填充用非零环绕规则，描边用"点到折线距离 <= strokeWidth/2"。
"""
import math
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

NS = "{http://schemas.android.com/apk/res/android}"


def parse_color(text):
    if not text or text in ("none", "@null"):
        return None
    text = text.lstrip("#")
    if len(text) == 6:
        text += "ff"
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4, 6))


def tokenize(path):
    return re.findall(r"[MmLlHhVvCcZz]|-?\d*\.?\d+(?:e-?\d+)?", path)


def flatten(d, steps=12):
    """pathData -> [ (points, closed), ... ]，曲线按 steps 段折线近似。"""
    toks = tokenize(d)
    i = 0
    subpaths = []
    pts = []
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    cmd = None
    closed = False

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                if pts:
                    subpaths.append((pts, True))
                    pts = []
                cur = start
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = num(), num()
            if rel:
                x, y = cur[0] + x, cur[1] + y
            if pts:
                subpaths.append((pts, closed))
            pts = [(x, y)]
            cur = start = (x, y)
            cmd = "l" if rel else "L"  # 后续隐式 lineto
        elif c == "L":
            x, y = num(), num()
            if rel:
                x, y = cur[0] + x, cur[1] + y
            pts.append((x, y))
            cur = (x, y)
        elif c == "H":
            x = num()
            if rel:
                x = cur[0] + x
            pts.append((x, cur[1]))
            cur = (x, cur[1])
        elif c == "V":
            y = num()
            if rel:
                y = cur[1] + y
            pts.append((cur[0], y))
            cur = (cur[0], y)
        elif c == "C":
            x1, y1, x2, y2, x, y = (num() for _ in range(6))
            if rel:
                x1, y1 = cur[0] + x1, cur[1] + y1
                x2, y2 = cur[0] + x2, cur[1] + y2
                x, y = cur[0] + x, cur[1] + y
            x0, y0 = cur
            for s in range(1, steps + 1):
                t_ = s / steps
                mt = 1 - t_
                bx = (mt ** 3 * x0 + 3 * mt ** 2 * t_ * x1
                      + 3 * mt * t_ ** 2 * x2 + t_ ** 3 * x)
                by = (mt ** 3 * y0 + 3 * mt ** 2 * t_ * y1
                      + 3 * mt * t_ ** 2 * y2 + t_ ** 3 * y)
                pts.append((bx, by))
            cur = (x, y)
        else:
            raise ValueError("不支持的指令: " + c)
    if pts:
        subpaths.append((pts, closed))
    return subpaths


def winding_inside(subpaths, px, py):
    total = 0
    for pts, closed in subpaths:
        n = len(pts)
        for k in range(n if closed else n - 1):
            x1, y1 = pts[k]
            x2, y2 = pts[(k + 1) % n]
            if y1 <= py:
                if y2 > py and (x2 - x1) * (py - y1) - (px - x1) * (y2 - y1) > 0:
                    total += 1
            else:
                if y2 <= py and (x2 - x1) * (py - y1) - (px - x1) * (y2 - y1) < 0:
                    total -= 1
    return total != 0


def seg_distance(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def stroke_hit(subpaths, px, py, half):
    for pts, closed in subpaths:
        n = len(pts)
        for k in range(n if closed else n - 1):
            x1, y1 = pts[k]
            x2, y2 = pts[(k + 1) % n]
            if seg_distance(px, py, x1, y1, x2, y2) <= half:
                return True
    return False


def render(xml_path, size, ss=3):
    root = ET.parse(xml_path).getroot()
    vw = float(root.get(NS + "viewportWidth"))
    vh = float(root.get(NS + "viewportHeight"))
    paths = []
    for p in root.findall("path"):
        d = p.get(NS + "pathData")
        fill = parse_color(p.get(NS + "fillColor"))
        stroke = parse_color(p.get(NS + "strokeColor"))
        sw = float(p.get(NS + "strokeWidth") or 0)
        sub = flatten(d)
        if fill or stroke:
            paths.append((sub, fill, stroke, sw))
        # 只做填充的路径：sample 精度足够
    pixels = []
    for oy in range(size):
        row = []
        for ox in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    ux = (ox + (sx + 0.5) / ss) * vw / size
                    uy = (oy + (sy + 0.5) / ss) * vh / size
                    cr, cg, cb, ca = 0.0, 0.0, 0.0, 0.0
                    for sub, fill, stroke, sw in paths:
                        hit = False
                        for k in range(3):  # 覆盖顺序：填充优先，其次描边
                            if fill and winding_inside(sub, ux, uy):
                                hit = True
                            elif stroke and stroke_hit(sub, ux, uy, sw / 2.0):
                                hit = True
                            if hit:
                                break
                        if not hit:
                            continue
                        col = (fill if (fill and winding_inside(sub, ux, uy)) else stroke)
                        sr, sg, sb, sa = [v / 255.0 for v in col]
                        na = sa + ca * (1 - sa)
                        if na > 0:
                            cr = (sr * sa + cr * ca * (1 - sa)) / na
                            cg = (sg * sa + cg * ca * (1 - sa)) / na
                            cb = (sb * sa + cb * ca * (1 - sa)) / na
                        ca = na
                    r += cr
                    g += cg
                    b += cb
                    a += ca
            n = ss * ss
            row.append((r / n, g / n, b / n, a / n))
        pixels.append(row)
    return pixels


def over(bg, fg):
    fr, fg_, fb, fa = fg
    br, bg_, bb, ba = bg
    na = fa + ba * (1 - fa)
    if na <= 0:
        return (1.0, 1.0, 1.0, 1.0)
    return (((fr * fa + br * ba * (1 - fa)) / na,
             (fg_ * fa + bg_ * ba * (1 - fa)) / na,
             (fb * fa + bb * ba * (1 - fa)) / na, na))


def write_png(path, w, h, rgba_rows):
    raw = b""
    for row in rgba_rows:
        raw += b"\x00" + b"".join(struct.pack("BBBB", *[max(0, min(255, int(round(v * 255)))) for v in px])
                                  for px in row)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    icons = sys.argv[1:] or [
        os.path.join(root, "app/src/main/res/drawable/ic_scala_project.xml"),
        os.path.join(root, "app/src/main/res/drawable/ic_file_scala.xml"),
        os.path.join(root, "app/src/main/res/drawable/ic_file_sbt.xml"),
    ]
    big = 132
    small = 40
    cache = {}
    for icon in icons:
        print("渲染", icon.split("/")[-1], flush=True)
        cache[icon] = (render(icon, big, ss=3), render(icon, small, ss=4))

    pad = 12
    W = pad + len(icons) * (big + pad)
    H = pad + big + pad + small + pad
    rows = [[(0.94, 0.94, 0.95, 1.0)] * W for _ in range(H)]
    for idx, icon in enumerate(icons):
        bx = pad + idx * (big + pad)
        for y in range(big):
            for x in range(big):
                rows[pad + y][bx + x] = over(rows[pad + y][bx + x], cache[icon][0][y][x])
        sy = pad + big + pad
        for y in range(small):
            for x in range(small):
                rows[sy + y][pad + idx * (small + pad) + x] = over(
                    rows[sy + y][pad + idx * (small + pad) + x], cache[icon][1][y][x])
    write_png("/tmp/icons_preview.png", W, H, rows)
    print("已写出 /tmp/icons_preview.png")


if __name__ == "__main__":
    main()
