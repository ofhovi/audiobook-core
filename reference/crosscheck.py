#!/usr/bin/env python3
"""Cross-implementation check: the Python reference and the Kotlin port must
produce identical output for every fixture.

  crosscheck.py --fixtures DIR   build fixtures, write DIR/py.json + DIR/py_hash.tsv
  crosscheck.py --compare  DIR   diff py.json against DIR/kt.tsv and hashes

Split into two phases because the Kotlin run happens between them (see
scripts/verify.sh). Exits non-zero on any divergence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import shutil
import struct
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mp4_audiobook import Mp4Error, content_id, extract_cover, parse_audiobook  # noqa: E402

CHAPS = """;FFMETADATA1
title=Test Book
artist=Test Author
album=Test Series
[CHAPTER]
TIMEBASE=1/1000
START=0
END=10000
title=Chapter One: The Beginning
[CHAPTER]
TIMEBASE=1/1000
START=10000
END=22000
title=Chapter Two
[CHAPTER]
TIMEBASE=1/1000
START=22000
END=30000
title=Chapter Three: \u00dcn\u00efcod\u00e9 T\u00eftle
"""


def ff(*args):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], check=True)


def build_fixtures(fx: str) -> None:
    shutil.rmtree(fx, ignore_errors=True)
    os.makedirs(fx)
    with open(f"{fx}/m.txt", "w", encoding="utf-8") as f:
        f.write(CHAPS)

    ff("-f", "lavfi", "-i", "sine=frequency=200:duration=30",
       "-c:a", "aac", "-b:a", "32k", f"{fx}/plain.m4a")
    ff("-i", f"{fx}/plain.m4a", "-i", f"{fx}/m.txt", "-map_metadata", "1",
       "-c", "copy", f"{fx}/book.m4b")
    ff("-i", f"{fx}/plain.m4a", "-i", f"{fx}/m.txt", "-map_metadata", "1",
       "-c", "copy", "-movflags", "+faststart", f"{fx}/faststart.m4b")
    ff("-f", "lavfi", "-i", "sine=frequency=100:duration=3600",
       "-c:a", "aac", "-b:a", "16k", f"{fx}/long.m4a")

    # Cover art: audio + chapters + an embedded image (covr atom), one JPEG
    # and one PNG so both ports' format-sniffing paths get exercised.
    ff("-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", f"{fx}/cover.jpg")
    ff("-f", "lavfi", "-i", "color=c=red:s=64x64", "-frames:v", "1", f"{fx}/cover.png")
    for fmt in ("jpg", "png"):
        ff("-i", f"{fx}/plain.m4a", "-i", f"{fx}/m.txt", "-i", f"{fx}/cover.{fmt}",
           "-map_metadata", "1", "-map", "0:a", "-map", "2:v",
           "-c", "copy", "-disposition:v:0", "attached_pic", f"{fx}/with_cover_{fmt}.m4b")

    # Nero-only: break the tref/chap reference so the QuickTime path can't fire.
    d = bytearray(open(f"{fx}/book.m4b", "rb").read())
    i = d.find(b"chap")
    d[i:i + 4] = b"xxxx"
    open(f"{fx}/nero_only.m4b", "wb").write(d)

    # Crafted chpl claiming 255 chapters.
    d = bytearray(open(f"{fx}/book.m4b", "rb").read())
    j = d.find(b"chpl")
    d[j + 4] = 1
    d[j + 12] = 0xFF
    i = d.find(b"chap")
    d[i:i + 4] = b"xxxx"
    open(f"{fx}/absurd.m4b", "wb").write(d)

    # 25 irregular chapters, CJK and quote characters in titles.
    random.seed(7)
    bounds = [0]
    for _ in range(25):
        bounds.append(bounds[-1] + random.choice([317, 1489, 40, 7033, 999, 12007]))
    ff("-f", "lavfi", "-i", f"sine=frequency=150:duration={bounds[-1] / 1000 + 1:.3f}",
       "-c:a", "aac", "-b:a", "24k", f"{fx}/big.m4a")
    with open(f"{fx}/m2.txt", "w", encoding="utf-8") as f:
        f.write(";FFMETADATA1\n")
        for i in range(25):
            t = (f'Ch {i + 1} "quoted" & <tag> \u2014 \u65e5\u672c\u8a9e'
                 if i % 3 else f"Part {i + 1}")
            f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={bounds[i]}\n"
                    f"END={bounds[i + 1]}\ntitle={t}\n")
    ff("-i", f"{fx}/big.m4a", "-i", f"{fx}/m2.txt", "-map_metadata", "1",
       "-c", "copy", f"{fx}/many.m4b")

    # Malformed inputs.
    src = open(f"{fx}/book.m4b", "rb").read()
    for frac in (0.1, 0.5, 0.9, 0.99):
        open(f"{fx}/trunc{int(frac * 100)}.m4b", "wb").write(src[:int(len(src) * frac)])
    open(f"{fx}/garbage.bin", "wb").write(bytes(random.getrandbits(8) for _ in range(4096)))
    open(f"{fx}/empty.bin", "wb").write(b"")
    open(f"{fx}/zeroatom.m4b", "wb").write(
        struct.pack(">I4s", 16, b"ftyp") + b"M4A \x00\x00\x02\x00"
        + struct.pack(">I4s", 0, b"moov")
    )

    # content_id window edges: below, exactly at, and just over 2 x 64KiB.
    open(f"{fx}/tiny.bin", "wb").write(os.urandom(3))
    open(f"{fx}/exact128k.bin", "wb").write(os.urandom(131072))
    open(f"{fx}/just_over.bin", "wb").write(os.urandom(131073))


def fixture_files(fx: str) -> list[str]:
    # .jpg/.png here are cover-art source images muxed into fixtures, not fixtures themselves.
    skip = (".json", ".tsv", ".txt", ".jpg", ".png")
    return sorted(f for f in os.listdir(fx) if not f.endswith(skip))


def run_python(fx: str) -> None:
    out: dict = {}
    hashes: dict = {}
    for name in fixture_files(fx):
        path = os.path.join(fx, name)
        hashes[name] = content_id(path)
        try:
            b = parse_audiobook(path)
            cover = extract_cover(path)
            out[name] = {
                "duration_ms": b.duration_ms,
                "chapter_source": b.chapter_source,
                "title": b.title, "author": b.author, "album": b.album,
                "cover_format": b.cover_format,
                "cover_sha256": hashlib.sha256(cover).hexdigest() if cover else None,
                "chapters": [
                    {"index": c.index, "title": c.title,
                     "start_ms": c.start_ms, "end_ms": c.end_ms}
                    for c in b.chapters
                ],
            }
        except Mp4Error as e:
            out[name] = {"error": str(e)}
    json.dump(out, open(f"{fx}/py.json", "w", encoding="utf-8"), ensure_ascii=False)
    with open(f"{fx}/py_hash.tsv", "w") as f:
        for k, v in sorted(hashes.items()):
            f.write(f"{k}\t{v}\n")
    print(f"    {len(out)} fixtures parsed by the Python reference")


def load_tsv(path: str) -> dict[str, str]:
    if not os.path.exists(path):
        return {}
    out: dict = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if "\t" in line:
            k, v = line.split("\t", 1)
            out[k] = v
    return out


def compare(fx: str) -> int:
    py = json.load(open(f"{fx}/py.json", encoding="utf-8"))
    kt = {k: json.loads(v) for k, v in load_tsv(f"{fx}/kt.tsv").items()}

    missing = set(py) - set(kt)
    if missing:
        print(f"    FAIL: Kotlin produced no output for {sorted(missing)}")
        return 1

    bad = 0
    for name in sorted(py):
        a, b = py[name], kt[name]
        if ("error" in a) != ("error" in b):
            print(f"    DIVERGE {name}: py={a.get('error', 'ok')} kt={b.get('error', 'ok')}")
            bad += 1
            continue
        if "error" in a:
            continue
        diffs = []
        for k in ("duration_ms", "chapter_source", "title", "author", "album",
                   "cover_format", "cover_sha256"):
            if a[k] != b[k]:
                diffs.append(f"{k}: {a[k]!r} vs {b[k]!r}")
        if len(a["chapters"]) != len(b["chapters"]):
            diffs.append(f"chapter count {len(a['chapters'])} vs {len(b['chapters'])}")
        else:
            for x, y in zip(a["chapters"], b["chapters"]):
                if x != y:
                    diffs.append(f"ch{x['index']}: {x} vs {y}")
        if diffs:
            bad += 1
            print(f"    DIVERGE {name}")
            for d in diffs[:4]:
                print(f"        {d}")

    py_h = load_tsv(f"{fx}/py_hash.tsv")
    kt_h = load_tsv(f"{fx}/kt_hash.tsv")
    hash_bad = 0
    if kt_h:
        for name, h in sorted(py_h.items()):
            if kt_h.get(name) != h:
                hash_bad += 1
                print(f"    CONTENT_ID MISMATCH {name}\n        py={h}\n        kt={kt_h.get(name)}")
    else:
        print("    (no Kotlin hashes; skipping content_id check)")

    if bad or hash_bad:
        print(f"    FAIL: {bad} parse divergences, {hash_bad} content_id mismatches")
        return 1
    print(f"    OK: {len(py)} fixtures, {len(py_h)} content_ids, 0 divergences")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", metavar="DIR", help="build fixtures and run Python")
    ap.add_argument("--compare", metavar="DIR", help="diff Python against Kotlin output")
    args = ap.parse_args()
    if args.fixtures:
        build_fixtures(args.fixtures)
        run_python(args.fixtures)
        return 0
    if args.compare:
        return compare(args.compare)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
