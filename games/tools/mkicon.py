#!/usr/bin/env python3
"""Icon generator for JARs: pixel art from a text grid -> PNG, no external libraries.

Grid format: rows of equal length, each character = a colour from the palette (see ICONS).
Space/'.' = transparent. The result is scaled to ~24x24, the icon size
of the Series 30+ menu.
"""
import zlib, struct, sys, os

def png(pixels, w, h):
    raw = b''
    for y in range(h):
        raw += b'\x00' + b''.join(pixels[y*w + x] for x in range(w))
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))

def render(grid, palette, scale):
    gh = len(grid); gw = max(len(r) for r in grid)
    w, h = gw*scale, gh*scale
    px = []
    for y in range(h):
        row = grid[y//scale]
        for x in range(w):
            ch = row[x//scale] if x//scale < len(row) else ' '
            px.append(palette.get(ch, (0,0,0,0)))
    return png([struct.pack('BBBB', *p) for p in px], w, h)

def build(name, grid, palette, outdir, scale=2):
    data = render(grid, palette, scale)
    p = os.path.join(outdir, name + '.png')
    open(p, 'wb').write(data)
    return p, len(data)
