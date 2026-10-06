"""Checks a hatch output with readers that share no code with hatch.

usage: python ci/check_output.py OUTPUT.tif [SOURCE]

- tifffile: the IFD chain, and every level a tiled JPEG image half the size of the one before,
  the smallest fitting in 1024x1024.
- libtiff (through Pillow): sampled tiles of every level decoded by libtiff's JPEG codec, which
  trusts the TIFF Photometric/YCbCrSubSampling tags, must equal a standalone decode of the same
  JPEG stream, which trusts its own markers. A difference means the tags contradict the tiles.
- OpenSlide: opens the pyramid and sees every level.
- With an OpenSlide-readable SOURCE: level 0 must match the source's pixels.

Exit status 0 if everything holds, 1 otherwise. Needs numpy, tifffile, pillow, openslide-python
and openslide-bin.
"""
import io
import logging
import struct
import sys
import warnings

import numpy as np
import openslide
import tifffile
from PIL import Image

problems = []


class Collect(logging.Handler):
    def emit(self, record):
        problems.append(f'tifffile: {record.getMessage()}')


logging.getLogger('tifffile').addHandler(Collect())
logging.getLogger('tifffile').setLevel(logging.WARNING)


def one_tile_tiff(raw, tw, th, photometric, subsampling):
    """A classic TIFF holding one JPEG tile, tagged like the level it came from."""
    entries = [(256, 3, 1, tw), (257, 3, 1, th), (258, 3, 3, None), (259, 3, 1, 7),
               (262, 3, 1, photometric), (277, 3, 1, 3), (284, 3, 1, 1),
               (322, 3, 1, tw), (323, 3, 1, th), (324, 4, 1, None), (325, 4, 1, len(raw))]
    if subsampling is not None:
        entries.append((530, 3, 2, None))
    entries.sort()
    ifd_offset = 8
    bps_offset = ifd_offset + 2 + 12 * len(entries) + 4
    data_offset = bps_offset + 6
    out = bytearray(b'II*\x00' + struct.pack('<I', ifd_offset))
    out += struct.pack('<H', len(entries))
    for tag, typ, count, value in entries:
        if tag == 258:
            out += struct.pack('<HHII', tag, typ, count, bps_offset)
        elif tag == 324:
            out += struct.pack('<HHII', tag, typ, count, data_offset)
        elif tag == 530:
            out += struct.pack('<HHIHH', tag, typ, count, subsampling[0], subsampling[1])
        elif typ == 3:
            out += struct.pack('<HHIHH', tag, typ, count, value, 0)
        else:
            out += struct.pack('<HHII', tag, typ, count, value)
    out += struct.pack('<I', 0)
    out += struct.pack('<HHH', 8, 8, 8)
    out += raw
    return bytes(out)


def check_tiles(fh, level, page):
    tw, th = page.tilewidth, page.tilelength
    nx = -(-page.imagewidth // tw)
    ny = -(-page.imagelength // th)
    photometric = int(page.photometric)
    subsampling = tuple(page.tags[530].value) if 530 in page.tags else None
    worst = 0.0
    for tx, ty in sorted({(0, 0), (nx // 2, ny // 2), (nx - 1, ny - 1), (nx - 1, 0), (0, ny - 1)}):
        i = ty * nx + tx
        if page.databytecounts[i] == 0:
            problems.append(f'level {level} tile {tx},{ty} is not stored')
            continue
        fh.seek(page.dataoffsets[i])
        raw = fh.read(page.databytecounts[i])
        try:
            alone = np.asarray(Image.open(io.BytesIO(raw)).convert('RGB'), dtype=np.int16)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                tiled = Image.open(io.BytesIO(one_tile_tiff(raw, tw, th, photometric, subsampling)))
                tiled.load()
            via_libtiff = np.asarray(tiled.convert('RGB'), dtype=np.int16)
        except Exception as e:  # noqa: BLE001 - any decoder failure is a finding
            problems.append(f'level {level} tile {tx},{ty}: {type(e).__name__}: {e}')
            continue
        worst = max(worst, float(np.abs(via_libtiff - alone).mean()))
    if worst > 3:
        problems.append(f'level {level}: libtiff decodes tiles differently from their own markers '
                        f'(mean |diff| {worst:.1f}); the tags contradict the JPEG streams')
    return worst


def main(out, source=None):
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter('always')
        tf = tifffile.TiffFile(out)
    problems.extend(f'tifffile: {w.message}' for w in caught)
    pages = tf.pages
    print(f'{out}: {len(pages)} levels')
    with open(out, 'rb') as fh:
        for level, page in enumerate(pages):
            if not page.is_tiled or page.compression != tifffile.COMPRESSION.JPEG:
                problems.append(f'level {level} is not a tiled JPEG image')
                continue
            if level > 0:
                prev = pages[level - 1]
                if (page.imagewidth, page.imagelength) != (-(-prev.imagewidth // 2), -(-prev.imagelength // 2)):
                    problems.append(f'level {level} {page.imagewidth}x{page.imagelength} is not half of level {level - 1}')
            worst = check_tiles(fh, level, page)
            print(f'  level {level}: {page.imagewidth}x{page.imagelength}, photometric {page.photometric.name}, '
                  f'libtiff vs standalone decode {worst:.2f}')
    last = pages[-1]
    if last.imagewidth > 1024 or last.imagelength > 1024:
        problems.append(f'smallest level {last.imagewidth}x{last.imagelength} exceeds 1024x1024')

    slide = openslide.OpenSlide(out)
    if slide.level_count != len(pages):
        problems.append(f'OpenSlide sees {slide.level_count} levels, the file has {len(pages)}')
    if source:
        src = openslide.OpenSlide(source)
        if src.dimensions != slide.dimensions:
            problems.append(f'level 0 is {slide.dimensions}, the source {src.dimensions}')
        width, height = src.dimensions
        for fx, fy in ((0.5, 0.5), (0.2, 0.7), (0.8, 0.3)):
            x, y = int(width * fx), int(height * fy)
            a = np.asarray(src.read_region((x, y), 0, (256, 256)).convert('RGB'), dtype=np.int16)
            b = np.asarray(slide.read_region((x, y), 0, (256, 256)).convert('RGB'), dtype=np.int16)
            diff = float(np.abs(a - b).mean())
            print(f'  level 0 vs source at ({x}, {y}): mean |diff| {diff:.2f}')
            if diff > 1:
                problems.append(f'level 0 differs from the source at ({x}, {y}) (mean |diff| {diff:.1f})')

    print('RESULT:', 'OK' if not problems else 'PROBLEMS')
    for p in problems:
        print('  -', p)
    return 0 if not problems else 1


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
